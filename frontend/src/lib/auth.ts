import "server-only";

import bcrypt from "bcryptjs";
import { createHash, createHmac, randomBytes, timingSafeEqual } from "node:crypto";
import { isIP } from "node:net";
import { cookies, headers } from "next/headers";
import { redirect } from "next/navigation";

export const SESSION_COOKIE_NAME = "noizy_team_session";
const SESSION_TTL_SECONDS = 8 * 60 * 60;

type SessionPayload = { sub: string; iat: number; exp: number; nonce: string; generation: string };
type Attempt = { allowed: boolean; retryAfter: number };

function requiredEnv(name: string): string {
  const raw = process.env[name];
  if (!raw) throw new Error(`${name} is not configured`);
  const value = ((raw.startsWith("'") && raw.endsWith("'")) || (raw.startsWith('"') && raw.endsWith('"'))) ? raw.slice(1, -1) : raw;
  if ((name === "TEAM_SESSION_SECRET" || name === "AUTH_SERVICE_SECRET") && value.length < 32)
    throw new Error(`${name} must contain at least 32 characters`);
  return value;
}

function equal(value: string, expected: string): boolean {
  const actualBuffer = Buffer.from(value);
  const expectedBuffer = Buffer.from(expected);
  return actualBuffer.length === expectedBuffer.length && timingSafeEqual(actualBuffer, expectedBuffer);
}
function digest(value: string): string { return createHash("sha256").update(value).digest("hex"); }
function generation(): string {
  return digest(JSON.stringify([requiredEnv("TEAM_USER"), requiredEnv("TEAM_PASSWORD_HASH"), requiredEnv("TEAM_SESSION_SECRET")]));
}
function signature(value: string): string {
  // Version-separated signing prevents a legacy rollback from accepting new revoked cookies.
  const key = createHmac("sha256", requiredEnv("TEAM_SESSION_SECRET")).update("noizy-session-v2").digest();
  return createHmac("sha256", key).update(value).digest("base64url");
}
function trustedProxy(values: Pick<Headers, "get">): boolean {
  try { return equal(values.get("x-noizy-auth-key") ?? "", requiredEnv("AUTH_SERVICE_SECRET")); }
  catch { return false; }
}
function publicOrigin(): string {
  const configured = process.env.NOIZY_PUBLIC_ORIGIN ?? "https://tsa.noizycologne.de";
  const url = new URL(configured);
  if (url.protocol !== "https:" || url.origin !== configured || url.username || url.password)
    throw new Error("One explicit HTTPS public origin is required");
  return configured;
}

export function isSameOriginRequest(request: Request): boolean {
  if (!trustedProxy(request.headers)) return false;
  const site = request.headers.get("sec-fetch-site");
  const origin = request.headers.get("origin");
  try {
    if (["GET", "HEAD"].includes(request.method))
      return (origin === null || origin === publicOrigin()) && (site === null || site === "same-origin" || site === "none");
    return origin === publicOrigin() && (site === null || site === "same-origin");
  } catch { return false; }
}

async function store(operation: "register" | "validate" | "revoke" | "attempt" | "clear", body: object): Promise<unknown> {
  const base = new URL(process.env.NOIZY_INTERNAL_API_URL ?? "http://127.0.0.1:8080");
  if (base.protocol !== "http:" || !["api", "127.0.0.1", "localhost"].includes(base.hostname)
      || base.username || base.password || base.pathname !== "/" || base.search || base.hash)
    throw new Error("Invalid internal authentication endpoint");
  const response = await fetch(new URL(`/internal/auth/${operation}`, base), {
    method: "POST", cache: "no-store", redirect: "error", signal: AbortSignal.timeout(2000),
    headers: { "Content-Type": "application/json", "X-Noizy-Auth-Key": requiredEnv("AUTH_SERVICE_SECRET") },
    body: JSON.stringify(body),
  });
  if (!response.ok) throw new Error("Authentication store unavailable");
  return response.json() as Promise<unknown>;
}

function sessionKey(session: SessionPayload) { return { nonceHash: digest(session.nonce), generation: session.generation, expiresAt: session.exp }; }
function parseToken(token: string | undefined): SessionPayload | null {
  if (!token || token.length > 2048) return null;
  const parts = token.split(".");
  if (parts.length !== 2 || !parts[0] || !parts[1]) return null;
  try {
    if (!equal(parts[1], signature(parts[0]))) return null;
    const payload: unknown = JSON.parse(Buffer.from(parts[0], "base64url").toString("utf8"));
    if (!payload || typeof payload !== "object") return null;
    const p = payload as Record<string, unknown>;
    const now = Math.floor(Date.now() / 1000);
    if (p.sub !== requiredEnv("TEAM_USER") || typeof p.iat !== "number" || !Number.isSafeInteger(p.iat)
        || typeof p.exp !== "number" || !Number.isSafeInteger(p.exp) || p.exp <= now || p.iat > now + 30
        || p.exp - p.iat !== SESSION_TTL_SECONDS || typeof p.nonce !== "string" || !/^[0-9a-f]{32}$/.test(p.nonce)
        || p.generation !== generation()) return null;
    return p as SessionPayload;
  } catch { return null; }
}

export async function getSession(): Promise<SessionPayload | null> {
  try {
    if (!trustedProxy(await headers())) return null;
    const session = parseToken((await cookies()).get(SESSION_COOKIE_NAME)?.value);
    if (!session) return null;
    const result = await store("validate", sessionKey(session));
    return result && typeof result === "object" && "valid" in result && result.valid === true ? session : null;
  } catch { return null; }
}
export async function requireSession(): Promise<SessionPayload> {
  const session = await getSession();
  if (!session) redirect("/login");
  return session;
}
export async function revokeCurrentSession(): Promise<void> {
  if (!trustedProxy(await headers())) throw new Error("Untrusted proxy");
  const session = parseToken((await cookies()).get(SESSION_COOKIE_NAME)?.value);
  if (session) {
    const result = await store("revoke", sessionKey(session));
    if (!result || typeof result !== "object" || !("valid" in result) || result.valid !== false)
      throw new Error("Session revocation not confirmed");
  }
}
export async function createSession(username: string): Promise<string> {
  if (username !== requiredEnv("TEAM_USER")) throw new Error("Invalid session account");
  const now = Math.floor(Date.now() / 1000);
  const session: SessionPayload = { sub: username, iat: now, exp: now + SESSION_TTL_SECONDS, nonce: randomBytes(16).toString("hex"), generation: generation() };
  const result = await store("register", sessionKey(session));
  if (!result || typeof result !== "object" || !("valid" in result) || result.valid !== true)
    throw new Error("Session registration not confirmed");
  const encoded = Buffer.from(JSON.stringify(session)).toString("base64url");
  return `${encoded}.${signature(encoded)}`;
}
function attemptKeys(request: Request) {
  if (!trustedProxy(request.headers)) throw new Error("Untrusted proxy");
  const address = request.headers.get("x-noizy-client-ip") ?? "";
  return {
    accountHash: digest(`account:${requiredEnv("TEAM_USER")}`),
    addressHash: digest(`address:${isIP(address) ? address : "unknown"}`),
  };
}
export async function loginRateLimit(request: Request): Promise<Attempt> {
  const result = await store("attempt", attemptKeys(request));
  if (!result || typeof result !== "object" || !("allowed" in result) || typeof result.allowed !== "boolean"
      || !("retryAfter" in result) || typeof result.retryAfter !== "number" || !Number.isSafeInteger(result.retryAfter)
      || result.retryAfter < 0 || result.retryAfter > 900) throw new Error("Invalid login limit response");
  return { allowed: result.allowed, retryAfter: result.retryAfter };
}
export async function clearLoginRateLimit(request: Request): Promise<void> {
  await store("clear", attemptKeys(request));
}
export async function authenticate(username: string, password: string): Promise<boolean> {
  const configuredUsername = requiredEnv("TEAM_USER");
  const passwordHash = requiredEnv("TEAM_PASSWORD_HASH");
  const passwordMatches = await bcrypt.compare(password, passwordHash);
  return equal(username, configuredUsername) && passwordMatches;
}
export function sessionCookieOptions() {
  return { httpOnly: true, secure: true, sameSite: "lax" as const, path: "/", maxAge: SESSION_TTL_SECONDS };
}
