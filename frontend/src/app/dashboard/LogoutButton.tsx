"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";

export default function LogoutButton() {
  const router = useRouter();
  const [isLoggingOut, setIsLoggingOut] = useState(false);

  async function logout() {
    setIsLoggingOut(true);
    try {
      await fetch("/api/auth/logout", {
        method: "POST",
        credentials: "same-origin",
        headers: { "Content-Type": "application/json" },
      });
    } finally {
      router.replace("/login");
      router.refresh();
    }
  }

  return (
    <button
      className="rounded-lg border border-white/15 px-3 py-2 text-sm text-neutral-300 transition hover:border-white/40 hover:text-white focus:outline-none focus:ring-2 focus:ring-white/30 disabled:opacity-60"
      disabled={isLoggingOut}
      onClick={logout}
      type="button"
    >
      {isLoggingOut ? "Abmelden …" : "Abmelden"}
    </button>
  );
}
