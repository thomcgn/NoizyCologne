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
    <main className="app-page flex min-h-dvh items-center justify-center bg-[#050505] px-5 py-10 text-white">
      <div className="fixed right-4 top-4 sm:right-8 sm:top-8">
        <ThemeToggle />
      </div>
      <section className="w-full max-w-md rounded-2xl border border-white/10 bg-neutral-950/80 p-7 shadow-2xl shadow-black/40 sm:p-10">
        <div className="mb-10 text-center">
          <div className="theme-brand mx-auto w-fit rounded-2xl bg-black px-6 py-4">
            <Image
              src="/noizy-logo-white.png"
              alt="NoizyCologne"
              width={240}
              height={80}
              className="h-auto w-52"
              priority
            />
          </div>
          <p className="mt-6 text-xs font-medium uppercase tracking-[0.28em] text-neutral-500">
            Team Dashboard
          </p>
          <h1 className="mt-3 text-3xl font-semibold tracking-tight">Willkommen zurück</h1>
          <p className="mt-3 text-sm leading-6 text-neutral-400">
            Melde dich an, um das NoizyCologne-Team-Dashboard zu öffnen.
          </p>
        </div>

        <form className="space-y-5" onSubmit={handleSubmit} noValidate>
          <div>
            <label className="mb-2 block text-sm font-medium text-neutral-200" htmlFor="username">
              Benutzername
            </label>
            <input
              autoComplete="username"
              className="h-12 w-full rounded-lg border border-white/15 bg-black px-4 text-white outline-none transition placeholder:text-neutral-600 focus:border-white/50 focus:ring-2 focus:ring-white/10"
              id="username"
              name="username"
              onChange={(event) => setUsername(event.target.value)}
              required
              type="text"
              value={username}
            />
          </div>

          <div>
            <label className="mb-2 block text-sm font-medium text-neutral-200" htmlFor="password">
              Passwort
            </label>
            <div className="relative">
              <input
                autoComplete="current-password"
                className="h-12 w-full rounded-lg border border-white/15 bg-black px-4 pr-24 text-white outline-none transition placeholder:text-neutral-600 focus:border-white/50 focus:ring-2 focus:ring-white/10"
                id="password"
                name="password"
                onChange={(event) => setPassword(event.target.value)}
                required
                type={showPassword ? "text" : "password"}
                value={password}
              />
              <button
                aria-pressed={showPassword}
                className="absolute right-3 top-1/2 -translate-y-1/2 rounded px-2 py-1 text-xs font-medium text-neutral-400 hover:text-white focus:outline-none focus:ring-2 focus:ring-white/30"
                onClick={() => setShowPassword((visible) => !visible)}
                type="button"
              >
                {showPassword ? "Verbergen" : "Anzeigen"}
              </button>
            </div>
          </div>

          {error ? (
            <p aria-live="polite" className="rounded-lg border border-red-400/30 bg-red-950/30 px-4 py-3 text-sm text-red-200" role="alert">
              {error}
            </p>
          ) : null}

          <button
            className="h-12 w-full rounded-lg bg-white px-5 font-semibold text-black transition hover:bg-neutral-200 focus:outline-none focus:ring-2 focus:ring-white/60 disabled:cursor-not-allowed disabled:opacity-60"
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
