import { NextResponse } from "next/server";
import { authenticate, clearLoginRateLimit, createSession, isSameOriginRequest, loginRateLimit, SESSION_COOKIE_NAME, sessionCookieOptions } from "@/lib/auth";

const headers = { "Cache-Control": "no-store" };
function failure(error: string, status: number) { return NextResponse.json({error}, {status, headers}); }
async function credentials(request: Request): Promise<unknown> {
  const reader = request.body?.getReader();
  if (!reader) return null;
  const chunks: Uint8Array[] = [];
  let bytes = 0;
  try {
    for (;;) {
      const chunk = await reader.read();
      if (chunk.done) break;
      bytes += chunk.value.byteLength;
      if (bytes > 4096) { await reader.cancel(); throw new Error("Login body limit"); }
      chunks.push(chunk.value);
    }
    return JSON.parse(Buffer.concat(chunks).toString("utf8")) as unknown;
  } finally { reader.releaseLock(); }
}
export async function POST(request: Request) {
  if (!isSameOriginRequest(request)) return failure("Ungültige Anfrage.", 403);
  if (!/^application\/json(?:\s*;.*)?$/i.test(request.headers.get("content-type") ?? ""))
    return failure("JSON-Anfrage erforderlich.", 415);
  let body: unknown;
  try { body = await credentials(request); }
  catch { return failure("Bitte gültige Zugangsdaten eingeben (höchstens 4 KiB).", 400); }
  const username = typeof body === "object" && body !== null && "username" in body && typeof body.username === "string" ? body.username.trim() : "";
  const password = typeof body === "object" && body !== null && "password" in body && typeof body.password === "string" ? body.password : "";
  if (!username || username.length > 100 || !password || password.length > 1024)
    return failure("Bitte Benutzername und Passwort eingeben.", 400);
  try {
    const rate = await loginRateLimit(request);
    if (!rate.allowed) return NextResponse.json({error: "Zu viele Anmeldeversuche. Bitte später erneut versuchen."}, {
      status: 429, headers: {...headers, "Retry-After": String(rate.retryAfter)},
    });
    if (!(await authenticate(username, password))) return failure("Benutzername oder Passwort ist nicht korrekt.", 401);
    await clearLoginRateLimit(request);
    const token = await createSession(username);
    const response = NextResponse.json({ok: true}, {headers});
    response.cookies.set(SESSION_COOKIE_NAME, token, sessionCookieOptions());
    return response;
  } catch { return failure("Anmeldung derzeit nicht verfügbar. Bitte erneut versuchen.", 503); }
}
