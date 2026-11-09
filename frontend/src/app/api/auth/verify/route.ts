import { NextResponse } from "next/server";
import { getSession } from "@/lib/auth";

const headers = { "Cache-Control": "no-store" };
export async function GET() {
  return (await getSession()) ? new NextResponse(null, {status: 204, headers})
    : NextResponse.json({error: "Anmeldung erforderlich."}, {status: 401, headers});
}
export const POST = GET;
