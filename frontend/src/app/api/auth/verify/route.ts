import { NextResponse } from "next/server";

import { getSession } from "@/lib/auth";

export async function GET() {
  return (await getSession())
    ? new NextResponse(null, { status: 204 })
    : NextResponse.json({ error: "Anmeldung erforderlich." }, { status: 401 });
}

export const POST = GET;
