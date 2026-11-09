import { randomBytes } from "node:crypto";
import { NextResponse } from "next/server";
import { authenticate, getSession, isSameOriginRequest, loginRateLimit, clearLoginRateLimit } from "@/lib/auth";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

const CHALLENGE_TTL_MS = 2 * 60 * 1000;
const MAX_CHALLENGES = 64;
const challenges = new Map<string, { sessionNonce: string; expires: number; revision: string | null }>();
const headers = { "Cache-Control": "no-store" };

function failure(error: string, status: number, outcomeUnknown = false) {
  return NextResponse.json({ error, outcomeUnknown }, { status, headers });
}

function internalConfiguration(): { url: string; secret: string } | null {
  const secret = process.env.FACTORY_RESET_SECRET ?? "";
  if (secret.length < 32) return null;
  try {
    const base = new URL(process.env.NOIZY_INTERNAL_API_URL ?? "http://127.0.0.1:8080");
    if (base.protocol !== "http:" || !["api", "127.0.0.1", "localhost"].includes(base.hostname)
        || base.username || base.password || base.pathname !== "/" || base.search || base.hash) return null;
    return { url: new URL("/internal/maintenance/factory-reset", base).toString(), secret };
  } catch { return null; }
}

async function invokeBackend(method: "GET" | "POST", revision?: string) {
  const config = internalConfiguration();
  if (!config) return failure("Werkseinstellungen sind nicht korrekt serverseitig eingerichtet. Kein Reset gestartet.", 503);
  if (method === "POST" && !revision) return failure("Geprüfte Vorschau fehlt. Kein Reset gestartet.", 409);
  try {
    const response = await fetch(config.url, {
      method, cache: "no-store", redirect: "error", signal: AbortSignal.timeout(method === "POST" ? 30000 : 15000),
      headers: {
        "X-Noizy-Reset-Key": config.secret,
        ...(method === "POST" ? { "Content-Type": "application/json" } : {}),
      },
      ...(method === "POST" ? { body: JSON.stringify({ phrase: "Kompletter Reset", revision }) } : {}),
    });
    const data = await response.json() as unknown;
    if (!response.ok) {
      const details = data && typeof data === "object" ? data as Record<string, unknown> : {};
      const message = [details.message, details.detail, details.error].find(value => typeof value === "string" && value.length > 0 && value.length < 250);
      return failure(typeof message === "string" ? message : "Der Reset wurde nicht bestätigt. Bitte den Zustand prüfen.", response.status,
        method === "POST" && response.status >= 500);
    }
    if (!data || typeof data !== "object" || Array.isArray(data)) throw new Error("Invalid reset response");
    const values = data as Record<string, unknown>;
    if (method === "POST" && (typeof values.partial !== "boolean" ||
        ![values.deletedRecords, values.deletedFiles, values.filesNotDeleted].every(value => typeof value === "number" && Number.isSafeInteger(value) && value >= 0)))
      throw new Error("Invalid reset outcome");
    return NextResponse.json(data, { headers });
  } catch {
    return method === "POST"
      ? failure("Reset-Ergebnis nicht bestätigt. Die Datenbank kann bereits zurückgesetzt sein. Bitte den Administrator informieren und den Zustand prüfen; keinen weiteren Reset anfordern.", 503, true)
      : failure("Die interne Reset-Vorschau ist nicht erreichbar. Kein Reset gestartet.", 503);
  }
}

export async function GET(request: Request) {
  if (!isSameOriginRequest(request)) return failure("Ungültige Anfrage.", 403);
  let session;
  try { session = await getSession(); }
  catch { return failure("Anmeldespeicher nicht erreichbar. Kein Reset gestartet.", 503); }
  if (!session) return failure("Anmeldung erforderlich.", 401);
  const now = Date.now();
  for (const [token, value] of challenges) {
    if (value.expires <= now || value.sessionNonce === session.nonce) challenges.delete(token);
  }
  if (challenges.size >= MAX_CHALLENGES) return failure("Zu viele offene Reset-Vorschauen. Bitte später erneut versuchen.", 503);
  // Reserve before asynchronous work; concurrent requests cannot exceed the cap.
  const challenge = randomBytes(24).toString("hex");
  const pending = { sessionNonce: session.nonce, expires: now + CHALLENGE_TTL_MS, revision: null as string | null };
  challenges.set(challenge, pending);
  const previewResponse = await invokeBackend("GET");
  if (!previewResponse.ok) { challenges.delete(challenge); return previewResponse; }
  const preview = await previewResponse.json() as Record<string, unknown>;
  if (typeof preview.revision !== "string" || !/^[a-f0-9]{64}$/.test(preview.revision)) {
    challenges.delete(challenge); return failure("Die interne Vorschau hat keine gültige Datenrevision. Kein Reset gestartet.", 502);
  }
  if (challenges.get(challenge) !== pending) return failure("Die Vorschau wurde durch eine neuere Anfrage ersetzt. Bitte neu öffnen.", 409);
  pending.revision = preview.revision;
  pending.expires = Date.now() + CHALLENGE_TTL_MS;
  return NextResponse.json({ ...preview, challenge }, { headers });
}

async function confirmation(request: Request): Promise<unknown> {
  const reader = request.body?.getReader();
  if (!reader) return null;
  const chunks: Uint8Array[] = [];
  let bytes = 0;
  try {
    for (;;) {
      const chunk = await reader.read();
      if (chunk.done) break;
      bytes += chunk.value.byteLength;
      if (bytes > 2048) { await reader.cancel(); throw new Error("Reset confirmation body limit"); }
      chunks.push(chunk.value);
    }
    return JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(Buffer.concat(chunks))) as unknown;
  } finally { reader.releaseLock(); }
}

export async function POST(request: Request) {
  if (!isSameOriginRequest(request)) return failure("Ungültige Anfrage.", 403);
  let session;
  try { session = await getSession(); }
  catch { return failure("Anmeldespeicher nicht erreichbar. Kein Reset gestartet.", 503); }
  if (!session) return failure("Anmeldung erforderlich.", 401);
  if (!/^application\/json(?:\s*;.*)?$/i.test(request.headers.get("content-type") ?? "")) return failure("JSON-Bestätigung erforderlich.", 415);
  if (Number(request.headers.get("content-length") ?? 0) > 2048) return failure("Bestätigung höchstens 2 KiB.", 400);
  let body: unknown;
  try { body = await confirmation(request); }
  catch { return failure("Ungültige Bestätigung (höchstens 2 KiB, UTF-8).", 400); }
  if (!body || typeof body !== "object" || Array.isArray(body)) return failure("Ungültige Bestätigung.", 400);
  const fields = body as Record<string, unknown>;
  if (fields.phrase !== "Kompletter Reset" || typeof fields.password !== "string" || !fields.password || fields.password.length > 1024
      || typeof fields.challenge !== "string" || !/^[a-f0-9]{48}$/.test(fields.challenge))
    return failure("Bitte den Bestätigungstext und dein aktuelles Passwort eingeben.", 400);
  let rate: { allowed: boolean; retryAfter: number };
  try { rate = await loginRateLimit(request); }
  catch { return failure("Anmeldespeicher nicht erreichbar. Kein Reset gestartet.", 503); }
  if (!rate.allowed) return failure("Zu viele Versuche. Bitte später erneut versuchen.", 429);
  const confirmed = challenges.get(fields.challenge);
  challenges.delete(fields.challenge);
  if (!confirmed || confirmed.sessionNonce !== session.nonce || confirmed.expires <= Date.now() || !confirmed.revision)
    return failure("Bestätigung abgelaufen. Bitte die Vorschau erneut öffnen.", 409);
  try {
    if (!(await authenticate(session.sub, fields.password))) return failure("Das aktuelle Passwort ist nicht korrekt.", 401);
    await clearLoginRateLimit(request);
  } catch { return failure("Anmeldespeicher nicht erreichbar. Kein Reset gestartet.", 503); }
  if (confirmed.expires <= Date.now()) return failure("Bestätigung abgelaufen. Bitte die Vorschau erneut öffnen.", 409);
  return invokeBackend("POST", confirmed.revision);
}
