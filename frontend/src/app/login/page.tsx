import { redirect } from "next/navigation";

import LoginForm from "./LoginForm";
import { getSession } from "@/lib/auth";

export default async function LoginPage() {
  if (await getSession()) redirect("/dashboard");
  return <LoginForm />;
}
