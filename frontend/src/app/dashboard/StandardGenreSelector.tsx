"use client";

import { useEffect, useState } from "react";

export type ActiveGenre = { id: string; displayName: string; active: boolean };

export function useActiveGenres() {
  const [genres, setGenres] = useState<ActiveGenre[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  useEffect(() => {
    let cancelled = false;
    async function load() {
      try {
        const response = await fetch("/api/v1/genres", { credentials: "same-origin", cache: "no-store" });
        if (!response.ok) throw new Error(`Die Genreverwaltung antwortete mit HTTP ${response.status}.`);
        const body: unknown = await response.json();
        if (!Array.isArray(body)) throw new Error("Die Genreverwaltung lieferte kein gültiges Listenformat.");
        const active = body.filter((item): item is ActiveGenre => {
          if (typeof item !== "object" || item === null) return false;
          const value = item as Record<string, unknown>;
          return typeof value.id === "string" && typeof value.displayName === "string" && value.active === true;
        }).sort((a, b) => a.displayName.localeCompare(b.displayName, "de-DE"));
        if (!cancelled) setGenres(active);
      } catch (cause) {
        if (!cancelled) setError(cause instanceof Error ? cause.message : "Genres konnten nicht geladen werden.");
      } finally {
        if (!cancelled) setLoading(false);
      }
    }
    void load();
    return () => { cancelled = true; };
  }, []);
  return { genres, loading, error };
}

export default function StandardGenreSelector({ genres, loading, error, value, onChange, disabled = false }: {
  genres: ActiveGenre[]; loading: boolean; error: string; value: string[];
  onChange: (value: string[]) => void; disabled?: boolean;
}) {
  const [search, setSearch] = useState("");
  const matches = (name: string) => name.toLocaleLowerCase("de-DE").includes(search.trim().toLocaleLowerCase("de-DE"));
  const remove = (name: string) => onChange(value.filter(item => item !== name));
  const available = genres.filter(genre => matches(genre.displayName));
  const unavailable = value.filter(name => !genres.some(genre => genre.displayName === name));
  return <fieldset className="mt-6 rounded-xl border border-white/10 p-5">
    <legend className="px-2 text-lg font-semibold">Standardgenres</legend>
    <p className="mt-1 text-sm text-neutral-400">Diese Genres werden bei neuen Redaktionsentwürfen vorausgewählt. Special-Event-Zusätze bleiben eventbezogen.</p>
    <label className="mt-3 block text-sm">Standardgenres durchsuchen<input type="search" className="mt-1 w-full rounded-lg border border-white/20 bg-neutral-900 p-3" value={search} onChange={event => setSearch(event.target.value)} disabled={disabled} /></label>
    <div className="cc-tags mt-3" aria-label="Ausgewählte Standardgenres">{value.map(name => <span key={name}>{name}<button type="button" aria-label={`Genre ${name} entfernen`} className="ml-2 min-h-11 px-2" disabled={disabled} onClick={() => remove(name)}>×</button></span>)}</div>
    {loading && <p className="mt-4 text-sm text-neutral-500" role="status">Genres werden geladen ...</p>}
    {error && <p className="mt-4 text-sm text-red-300" role="alert">{error}</p>}
    {!loading && !error && genres.length === 0 && <p className="mt-4 text-sm text-neutral-500">Keine aktiven Genres in den Einstellungen vorhanden.</p>}
    {!loading && !error && search && available.length === 0 && <p className="mt-4 text-sm">Keine passenden aktiven Genres.</p>}
    <div className="mt-4 grid grid-cols-2 gap-2 sm:grid-cols-3">
      {available.map(genre => <label key={genre.id} className="flex min-h-11 items-center gap-2 rounded-lg bg-neutral-900 px-3 py-2 text-sm">
        <input type="checkbox" className="size-4 accent-white" checked={value.includes(genre.displayName)} onChange={() => value.includes(genre.displayName) ? remove(genre.displayName) : onChange([...value, genre.displayName])} disabled={disabled || loading || !!error} />
        <span>{genre.displayName}</span>
      </label>)}
      {!loading && !error && unavailable.filter(matches).map(name => <label key={`unavailable-${name}`} className="flex min-h-11 items-center gap-2 rounded-lg bg-neutral-900 px-3 py-2 text-sm">
        <input type="checkbox" className="size-4 accent-white" checked onChange={() => remove(name)} disabled={disabled} />
        <span>{name} <span className="text-neutral-400">(nicht mehr aktiv oder verfügbar; zum Entfernen abwählen)</span></span>
      </label>)}
    </div>
  </fieldset>;
}
