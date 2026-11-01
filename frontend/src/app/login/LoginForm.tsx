"use client";

import Image from "next/image";
import { FormEvent, useState } from "react";
import { useRouter } from "next/navigation";
import ThemeToggle from "../ThemeToggle";

export default function LoginForm() {
  const router = useRouter();
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [showPassword, setShowPassword] = useState(false);
  const [error, setError] = useState("");
  const [isSubmitting, setIsSubmitting] = useState(false);

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError("");
    setIsSubmitting(true);

    try {
      const response = await fetch("/api/auth/login", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        credentials: "same-origin",
        body: JSON.stringify({ username, password }),
      });
      const result = (await response.json().catch(() => ({}))) as { error?: string };

      if (!response.ok) {
        setError(result.error ?? "Die Anmeldung ist fehlgeschlagen.");
        return;
      }

      router.replace("/dashboard");
      router.refresh();
    } catch {
      setError("Der Server ist gerade nicht erreichbar. Bitte erneut versuchen.");
    } finally {
      setIsSubmitting(false);
    }
  }

  return (
    <main className="login-page app-page flex min-h-dvh items-center justify-center px-5 py-10">
      <div className="login-controls absolute right-4 top-4 sm:right-8 sm:top-8">
        <ThemeToggle />
      </div>
      <section className="login-card w-full max-w-md rounded-2xl border p-7 sm:p-10">
        <div className="mb-10 text-center">
          <div className="login-logo mx-auto w-fit rounded-2xl px-6 py-4">
            <Image
              src="/noizy-logo-white.png"
              alt="NoizyCologne"
              width={240}
              height={80}
              className="login-logo-dark h-auto w-52"
              priority
            />
            <Image
              src="/noizy-logo-black.png"
              alt="NoizyCologne"
              width={240}
              height={80}
              className="login-logo-light h-auto w-52"
              priority
            />
          </div>
          <p className="login-muted mt-6 text-xs font-medium uppercase tracking-[0.28em]">
            Team Dashboard
          </p>
          <h1 className="mt-3 text-3xl font-semibold tracking-tight">Willkommen zurück</h1>
          <p className="login-muted mt-3 text-sm leading-6">
            Melde dich an, um das NoizyCologne-Team-Dashboard zu öffnen.
          </p>
        </div>

        <form className="space-y-5" onSubmit={handleSubmit} noValidate>
          <div>
            <label className="mb-2 block text-sm font-medium" htmlFor="username">
              Benutzername
            </label>
            <input
              autoComplete="username"
              className="login-input h-12 w-full rounded-lg border px-4 outline-none transition focus:ring-2 focus:ring-current"
              id="username"
              name="username"
              onChange={(event) => setUsername(event.target.value)}
              required
              type="text"
              value={username}
            />
          </div>

          <div>
            <label className="mb-2 block text-sm font-medium" htmlFor="password">
              Passwort
            </label>
            <div className="relative">
              <input
                autoComplete="current-password"
                className="login-input h-12 w-full rounded-lg border px-4 pr-24 outline-none transition focus:ring-2 focus:ring-current"
                id="password"
                name="password"
                onChange={(event) => setPassword(event.target.value)}
                required
                type={showPassword ? "text" : "password"}
                value={password}
              />
              <button
                aria-pressed={showPassword}
                className="login-muted absolute right-3 top-1/2 -translate-y-1/2 rounded px-2 py-1 text-xs font-medium hover:opacity-70 focus:outline-none focus:ring-2 focus:ring-current"
                onClick={() => setShowPassword((visible) => !visible)}
                type="button"
              >
                {showPassword ? "Verbergen" : "Anzeigen"}
              </button>
            </div>
          </div>

          {error ? (
            <p aria-live="polite" className="login-error rounded-lg border px-4 py-3 text-sm" role="alert">
              {error}
            </p>
          ) : null}

          <button
            className="login-submit h-12 w-full rounded-lg px-5 font-semibold transition focus:outline-none focus:ring-2 focus:ring-current disabled:cursor-not-allowed disabled:opacity-60"
            disabled={isSubmitting}
            type="submit"
          >
            {isSubmitting ? "Anmeldung läuft …" : "Anmelden"}
          </button>
        </form>
      </section>
    </main>
  );
}

