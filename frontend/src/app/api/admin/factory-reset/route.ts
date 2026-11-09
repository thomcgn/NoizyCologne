import { randomBytes } from "node:crypto";
import { NextResponse } from "next/server";

import { authenticate, getSession, isSameOriginRequest, loginRateLimit, clearLoginRateLimit } from "@/lib/auth";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

const CHALLENGE_TTL_MS = 2 * 60 * 1000;
const challenges = new Map<string, { sessionNonce: string; expires: number }>();
const headers = { "Cache-Control": "no-store" };

function failure(error: string, status: number) {
  return NextResponse.json({ error }, { status, headers });
}

function internalConfiguration(): { url: string; secret: string } | null {
  const secret = process.env.FACTORY_RESET_SECRET ?? "";
  if (secret.length < 32) return null;
  const baseUrl = process.env.NOIZY_INTERNAL_API_URL ?? "http://127.0.0.1:8080";
  return { url: new URL("/internal/maintenance/factory-reset", baseUrl).toString(), secret };
}

async function invokeBackend(method: "GET" | "POST") {
  const config = internalConfiguration();
  if (!config) return failure("Werkseinstellungen sind noch nicht serverseitig eingerichtet.", 503);
  try {
    const response = await fetch(config.url, {
      method,
      cache: "no-store",
      headers: {
        "X-Noizy-Reset-Key": config.secret,
        ...(method === "POST" ? { "Content-Type": "application/json" } : {}),
      },
      ...(method === "POST" ? { body: JSON.stringify({ phrase: "Kompletter Reset" }) } : {}),
    });
    if (!response.ok) {
      const details = await response.json().catch(() => ({})) as { message?: unknown };
      const message = typeof details.message === "string" && details.message.length < 250
        ? details.message : "Der Werksreset konnte nicht ausgeführt werden.";
      return failure(message, response.status);
    }
    return NextResponse.json(await response.json() as unknown, { headers });
  } catch {
    return failure("Die interne Reset-Schnittstelle ist nicht erreichbar. Keine Änderung bestätigt.", 503);
  }
}

export async function GET(request: Request) {
  if (!isSameOriginRequest(request)) return failure("Ungültige Anfrage.", 403);
  const session = await getSession();
  if (!session) return failure("Anmeldung erforderlich.", 401);
  const previewResponse = await invokeBackend("GET");
  if (!previewResponse.ok) return previewResponse;
  const preview = await previewResponse.json() as Record<string, unknown>;
  const now = Date.now();
  for (const [token, value] of challenges) {
    if (value.expires < now) challenges.delete(token);
  }
  // A challenge only authorizes an authenticated two-step UI flow, never a reset by itself.
  const challenge = randomBytes(24).toString("hex");
  challenges.set(challenge, { sessionNonce: session.nonce, expires: now + CHALLENGE_TTL_MS });
  return NextResponse.json({ ...preview, challenge }, { headers });
}

export async function POST(request: Request) {
  if (!isSameOriginRequest(request)) return failure("Ungültige Anfrage.", 403);
  const session = await getSession();
  if (!session) return failure("Anmeldung erforderlich.", 401);
  if (Number(request.headers.get("content-length") ?? 0) > 2048)
    return failure("Ungültige Anfrage.", 400);
  let body: unknown;
  try { body = await request.json(); }
  catch { return failure("Ungültige Bestätigung.", 400); }
  if (!body || typeof body !== "object") return failure("Ungültige Bestätigung.", 400);
  const fields = body as Record<string, unknown>;
  if (fields.phrase !== "Kompletter Reset"
      || typeof fields.password !== "string" || !fields.password
      || typeof fields.challenge !== "string")
    return failure("Bitte den Bestätigungstext und dein aktuelles Passwort eingeben.", 400);
  let rate: { allowed: boolean; retryAfter: number };
  try { rate = await loginRateLimit(request); }
  catch { return failure("Anmeldespeicher nicht erreichbar. Kein Reset bestätigt.", 503); }
  if (!rate.allowed) return failure("Zu viele Versuche. Bitte später erneut versuchen.", 429);
  const confirmed = challenges.get(fields.challenge);
  challenges.delete(fields.challenge);
  if (!confirmed || confirmed.sessionNonce !== session.nonce || confirmed.expires < Date.now())
    return failure("Bestätigung abgelaufen. Bitte die Vorschau erneut öffnen.", 409);
  if (!(await authenticate(session.sub, fields.password)))
    return failure("Das aktuelle Passwort ist nicht korrekt.", 401);
  try { await clearLoginRateLimit(request); }
  catch { return failure("Anmeldespeicher nicht erreichbar. Kein Reset bestätigt.", 503); }
  return invokeBackend("POST");
}
