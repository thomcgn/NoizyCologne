import "server-only";

import bcrypt from "bcryptjs";
import { createHmac, randomBytes, timingSafeEqual } from "node:crypto";
import { cookies } from "next/headers";
import { redirect } from "next/navigation";

export const SESSION_COOKIE_NAME = "noizy_team_session";
const SESSION_TTL_SECONDS = 8 * 60 * 60;
const MAX_LOGIN_ATTEMPTS = 5;
const RATE_LIMIT_WINDOW_MS = 15 * 60 * 1000;

type SessionPayload = {
  sub: string;
  iat: number;
  exp: number;
  nonce: string;
};

type LoginAttempt = {
  count: number;
  resetAt: number;
};

const loginAttempts = new Map<string, LoginAttempt>();
const revokedSessions = new Map<string, number>();

function requiredEnv(name: string): string {
  const rawValue = process.env[name];
  if (!rawValue) {
    throw new Error(`${name} is not configured`);
  }

  const value =
    (rawValue.startsWith("'") && rawValue.endsWith("'")) ||
    (rawValue.startsWith('"') && rawValue.endsWith('"'))
      ? rawValue.slice(1, -1)
      : rawValue;

  if (name === "TEAM_SESSION_SECRET" && value.length < 32) {
    throw new Error("TEAM_SESSION_SECRET must contain at least 32 characters");
  }
  return value;
}

function encode(value: string): string {
  return Buffer.from(value, "utf8").toString("base64url");
}

function decode(value: string): string {
  return Buffer.from(value, "base64url").toString("utf8");
}

function signature(input: string): string {
  return createHmac("sha256", requiredEnv("TEAM_SESSION_SECRET"))
    .update(input)
    .digest("base64url");
}

function signedToken(payload: SessionPayload): string {
  const encodedPayload = encode(JSON.stringify(payload));
  return `${encodedPayload}.${signature(encodedPayload)}`;
}

function validSignature(value: string, expected: string): boolean {
  const actualBuffer = Buffer.from(value);
  const expectedBuffer = Buffer.from(expected);
  return (
    actualBuffer.length === expectedBuffer.length &&
    timingSafeEqual(actualBuffer, expectedBuffer)
  );
}

function parseToken(token: string | undefined): SessionPayload | null {
  if (!token) return null;

  const revokedUntil = revokedSessions.get(token);
  if (revokedUntil) {
    if (revokedUntil > Math.floor(Date.now() / 1000)) return null;
    revokedSessions.delete(token);
  }

  const [encodedPayload, providedSignature] = token.split(".");
  if (!encodedPayload || !providedSignature) return null;
  if (!validSignature(providedSignature, signature(encodedPayload))) return null;

  try {
    const payload = JSON.parse(decode(encodedPayload)) as SessionPayload;
    if (
      typeof payload.sub !== "string" ||
      typeof payload.iat !== "number" ||
      typeof payload.exp !== "number" ||
      typeof payload.nonce !== "string" ||
      payload.exp <= Math.floor(Date.now() / 1000)
    ) {
      return null;
    }
    return payload;
  } catch {
    return null;
  }
}

export async function getSession(): Promise<SessionPayload | null> {
  const cookieStore = await cookies();
  return parseToken(cookieStore.get(SESSION_COOKIE_NAME)?.value);
}

export async function revokeCurrentSession(): Promise<void> {
  const cookieStore = await cookies();
  const token = cookieStore.get(SESSION_COOKIE_NAME)?.value;
  if (token) {
    revokedSessions.set(token, Math.floor(Date.now() / 1000) + SESSION_TTL_SECONDS);
  }
}

export async function requireSession(): Promise<SessionPayload> {
  const session = await getSession();
  if (!session) {
    redirect("/login");
  }
  return session as SessionPayload;
}

export function isSameOriginRequest(request: Request): boolean {
  const origin = request.headers.get("origin");
  if (!origin) {
    return request.headers.get("sec-fetch-site") !== "cross-site";
  }

  const requestUrl = new URL(request.url);
  const protocol =
    request.headers.get("x-forwarded-proto")?.split(",")[0].trim() ??
    requestUrl.protocol.slice(0, -1);
  const host =
    request.headers.get("x-forwarded-host")?.split(",")[0].trim() ??
    request.headers.get("host") ??
    requestUrl.host;

  return origin === `${protocol}://${host}`;
}

function attemptKey(request: Request, username: string): string {
  const forwardedFor = request.headers.get("x-forwarded-for")?.split(",")[0];
  const address = request.headers.get("x-real-ip") ?? forwardedFor ?? "unknown";
  return `${address}:${username}`;
}

export function loginRateLimit(request: Request, username: string): {
  allowed: boolean;
  retryAfter: number;
} {
  const key = attemptKey(request, username);
  const now = Date.now();
  const existing = loginAttempts.get(key);

  if (!existing || existing.resetAt <= now) {
    loginAttempts.set(key, { count: 1, resetAt: now + RATE_LIMIT_WINDOW_MS });
    return { allowed: true, retryAfter: 0 };
  }

  if (existing.count >= MAX_LOGIN_ATTEMPTS) {
    return {
      allowed: false,
      retryAfter: Math.ceil((existing.resetAt - now) / 1000),
    };
  }

  existing.count += 1;
  return { allowed: true, retryAfter: 0 };
}

export function clearLoginRateLimit(request: Request, username: string): void {
  loginAttempts.delete(attemptKey(request, username));
}

export async function authenticate(username: string, password: string): Promise<boolean> {
  const configuredUsername = requiredEnv("TEAM_USER");
  const passwordHash = requiredEnv("TEAM_PASSWORD_HASH");

  if (username !== configuredUsername) return false;
  return bcrypt.compare(password, passwordHash);
}

export function createSession(username: string): string {
  const now = Math.floor(Date.now() / 1000);
  return signedToken({
    sub: username,
    iat: now,
    exp: now + SESSION_TTL_SECONDS,
    nonce: randomBytes(16).toString("hex"),
  });
}

export function sessionCookieOptions() {
  return {
    httpOnly: true,
    secure: true,
    sameSite: "lax" as const,
    path: "/",
    maxAge: SESSION_TTL_SECONDS,
  };
}
