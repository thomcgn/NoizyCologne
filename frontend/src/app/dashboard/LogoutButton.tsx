"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
import ActionFeedback from "./ActionFeedback";

export default function LogoutButton() {
  const router = useRouter();
  const [isLoggingOut, setIsLoggingOut] = useState(false);
  const [error, setError] = useState("");

  async function logout() {
    if (isLoggingOut) return;
    setIsLoggingOut(true);
    setError("");
    try {
      const response = await fetch("/api/auth/logout", {
        method: "POST",
        credentials: "same-origin",
        headers: { "Content-Type": "application/json" },
      });
      if (!response.ok) {
        setError(response.status === 401
          ? "Die Sitzung ist bereits abgelaufen. Bitte erneut anmelden."
          : "Abmelden fehlgeschlagen. Die Sitzung ist weiter aktiv. Bitte erneut versuchen.");
        return;
      }
      router.replace("/login");
      router.refresh();
    } catch {
      setError("Abmelden fehlgeschlagen. Die Sitzung ist weiter aktiv. Bitte die Verbindung prüfen und erneut versuchen.");
    } finally {
      setIsLoggingOut(false);
    }
  }

  return (
    <>
      <button
        className="control-button control-button-secondary"
        disabled={isLoggingOut}
        onClick={() => void logout()}
        type="button"
        aria-describedby={error ? "logout-error" : undefined}
      >
        {isLoggingOut ? "Abmelden …" : "Abmelden"}
      </button>
      {error && <ActionFeedback kind="error"><span id="logout-error">{error}</span></ActionFeedback>}
    </>
  );
}
