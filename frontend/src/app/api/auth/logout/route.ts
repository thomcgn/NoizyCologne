import { NextResponse } from "next/server";

import {
  isSameOriginRequest,
  revokeCurrentSession,
  SESSION_COOKIE_NAME,
  sessionCookieOptions,
} from "@/lib/auth";

export async function POST(request: Request) {
  if (!isSameOriginRequest(request)) {
    return NextResponse.json({ error: "Ungültige Anfrage." }, { status: 403 });
  }

  await revokeCurrentSession();
  const response = NextResponse.json({ ok: true });
  response.cookies.set(SESSION_COOKIE_NAME, "", { ...sessionCookieOptions(), maxAge: 0 });
  return response;
}
