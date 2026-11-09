import { NextResponse } from "next/server";
import { isSameOriginRequest, revokeCurrentSession, SESSION_COOKIE_NAME, sessionCookieOptions } from "@/lib/auth";

const headers = { "Cache-Control": "no-store" };
export async function POST(request: Request) {
  if (!isSameOriginRequest(request)) return NextResponse.json({error: "Ungültige Anfrage."}, {status: 403, headers});
  try { await revokeCurrentSession(); }
  catch { return NextResponse.json({error: "Abmeldung nicht bestätigt. Bitte erneut versuchen."}, {status: 503, headers}); }
  const response = NextResponse.json({ok: true}, {headers});
  response.cookies.set(SESSION_COOKIE_NAME, "", {...sessionCookieOptions(), maxAge: 0});
  return response;
}
