import { NextResponse } from "next/server";

import {
  authenticate,
  clearLoginRateLimit,
  createSession,
  isSameOriginRequest,
  loginRateLimit,
  SESSION_COOKIE_NAME,
  sessionCookieOptions,
} from "@/lib/auth";

export async function POST(request: Request) {
  if (!isSameOriginRequest(request)) {
    return NextResponse.json({ error: "Ungültige Anfrage." }, { status: 403 });
  }

  let body: unknown;
  try {
    body = await request.json();
  } catch {
    return NextResponse.json({ error: "Bitte Benutzername und Passwort eingeben." }, { status: 400 });
  }

  const username =
    typeof body === "object" && body !== null && "username" in body && typeof body.username === "string"
      ? body.username.trim()
      : "";
  const password =
    typeof body === "object" && body !== null && "password" in body && typeof body.password === "string"
      ? body.password
      : "";

  if (!username || !password) {
    return NextResponse.json({ error: "Bitte Benutzername und Passwort eingeben." }, { status: 400 });
  }

  const rateLimit = loginRateLimit(request, username);
  if (!rateLimit.allowed) {
    return NextResponse.json(
      { error: "Zu viele Anmeldeversuche. Bitte später erneut versuchen." },
      { status: 429, headers: { "Retry-After": String(rateLimit.retryAfter) } },
    );
  }

  if (!(await authenticate(username, password))) {
    return NextResponse.json({ error: "Benutzername oder Passwort ist nicht korrekt." }, { status: 401 });
  }

  clearLoginRateLimit(request, username);
  const response = NextResponse.json({ ok: true });
  response.cookies.set(SESSION_COOKIE_NAME, createSession(username), sessionCookieOptions());
  return response;
}
