"use client";

import { useEffect, useMemo, useState, type FormEvent } from "react";
import {
  CAPTION_PRETEXT_STORAGE_KEY,
  DEFAULT_CAPTION_PRETEXT,
} from "../../captionSettings";

type GenreAlias = { id: string; value: string };
type Genre = {
  id: string;
  canonicalName: string;
  displayName: string;
  active: boolean;
  aliases: GenreAlias[];
};

const inputStyle = "w-full rounded-lg border border-white/15 bg-neutral-900 px-3 py-2.5 text-sm text-white outline-none focus:border-white/60";

async function apiError(response: Response): Promise<string> {
  try {
    const body = await response.json() as { message?: unknown };
    if (typeof body.message === "string" && body.message.trim()) return body.message;
  } catch { /* Statusmeldung verwenden, wenn die Antwort kein JSON ist. */ }
  if (response.status === 401) return "Die Anmeldung ist abgelaufen. Bitte erneut anmelden.";
  if (response.status === 409) return "Der Name oder Alias ist bereits vergeben.";
  if (response.status >= 500) return "Die Genreverwaltung ist momentan nicht erreichbar.";
  return `Die Genreverwaltung antwortete mit HTTP ${response.status}.`;
}

export default function GenresPage() {
  const [genres, setGenres] = useState<Genre[]>([]);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");
  const [query, setQuery] = useState("");
  const [editorOpen, setEditorOpen] = useState(false);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [canonicalName, setCanonicalName] = useState("");
  const [displayName, setDisplayName] = useState("");
  const [active, setActive] = useState(true);
  const [newAlias, setNewAlias] = useState("");
  const [aliasGenreId, setAliasGenreId] = useState<string | null>(null);
  const [captionPreText, setCaptionPreText] = useState(() => {
    if (typeof window === "undefined") return DEFAULT_CAPTION_PRETEXT;
    return window.localStorage.getItem(CAPTION_PRETEXT_STORAGE_KEY)?.trim() || DEFAULT_CAPTION_PRETEXT;
  });
  const [captionPreTextSaved, setCaptionPreTextSaved] = useState(false);

  async function loadGenres() {
    setLoading(true);
    setError("");
    try {
      const response = await fetch("/api/v1/genres", { credentials: "same-origin" });
      if (!response.ok) throw new Error(await apiError(response));
      setGenres(await response.json() as Genre[]);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Genres konnten nicht geladen werden.");
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    const loadTimer = window.setTimeout(() => { void loadGenres(); }, 0);
    return () => window.clearTimeout(loadTimer);
  }, []);

  function saveCaptionPreText() {
    const value = captionPreText.trim() || DEFAULT_CAPTION_PRETEXT;
    window.localStorage.setItem(CAPTION_PRETEXT_STORAGE_KEY, value);
    setCaptionPreText(value);
    setCaptionPreTextSaved(true);
    window.setTimeout(() => setCaptionPreTextSaved(false), 2200);
  }

  const visibleGenres = useMemo(() => {
    const needle = query.trim().toLocaleLowerCase("de-DE");
    return genres
      .filter((genre) => !needle || [genre.canonicalName, genre.displayName, ...genre.aliases.map((alias) => alias.value)]
        .some((value) => value.toLocaleLowerCase("de-DE").includes(needle)))
      .sort((first, second) => first.displayName.localeCompare(second.displayName, "de-DE"));
  }, [genres, query]);

  function newGenre() {
    setEditorOpen(true); setEditingId(null); setCanonicalName(""); setDisplayName(""); setActive(true); setNewAlias(""); setError("");
  }

  function editGenre(genre: Genre) {
    setEditorOpen(true); setEditingId(genre.id); setCanonicalName(genre.canonicalName); setDisplayName(genre.displayName); setActive(genre.active); setNewAlias(""); setError("");
  }

  async function saveGenre(event: FormEvent) {
    event.preventDefault();
    if (!canonicalName.trim() || !displayName.trim()) { setError("Bitte kanonischen Namen und Anzeigenamen eingeben."); return; }
    setSaving(true); setError("");
    try {
      const response = await fetch(editingId ? `/api/v1/genres/${encodeURIComponent(editingId)}` : "/api/v1/genres", {
        method: editingId ? "PUT" : "POST", credentials: "same-origin",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ canonicalName: canonicalName.trim(), displayName: displayName.trim(), active }),
      });
      if (!response.ok) throw new Error(await apiError(response));
      const saved = await response.json() as Genre;
      setGenres((current) => editingId ? current.map((item) => item.id === saved.id ? saved : item) : [...current, saved]);
      setEditorOpen(true); setEditingId(saved.id); setCanonicalName(saved.canonicalName); setDisplayName(saved.displayName); setActive(saved.active);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Genre konnte nicht gespeichert werden.");
    } finally { setSaving(false); }
  }

  async function toggleActive(genre: Genre) {
    setSaving(true); setError("");
    try {
      const response = await fetch(`/api/v1/genres/${encodeURIComponent(genre.id)}`, {
        method: "PUT", credentials: "same-origin", headers: { "content-type": "application/json" },
        body: JSON.stringify({ canonicalName: genre.canonicalName, displayName: genre.displayName, active: !genre.active }),
      });
      if (!response.ok) throw new Error(await apiError(response));
      const saved = await response.json() as Genre;
      setGenres((current) => current.map((item) => item.id === saved.id ? saved : item));
      if (editingId === saved.id) { setCanonicalName(saved.canonicalName); setDisplayName(saved.displayName); setActive(saved.active); }
    } catch (cause) { setError(cause instanceof Error ? cause.message : "Status konnte nicht geändert werden."); }
    finally { setSaving(false); }
  }

  async function addAlias(event: FormEvent, genre: Genre) {
    event.preventDefault();
    if (!newAlias.trim()) { setError("Bitte einen Alias eingeben."); return; }
    setSaving(true); setError("");
    try {
      const response = await fetch(`/api/v1/genres/${encodeURIComponent(genre.id)}/aliases`, {
        method: "POST", credentials: "same-origin", headers: { "content-type": "application/json" }, body: JSON.stringify({ value: newAlias.trim() }),
      });
      if (!response.ok) throw new Error(await apiError(response));
      const alias = await response.json() as GenreAlias;
      setGenres((current) => current.map((item) => item.id === genre.id ? { ...item, aliases: [...item.aliases, alias] } : item));
      setNewAlias(""); setAliasGenreId(null);
    } catch (cause) { setError(cause instanceof Error ? cause.message : "Alias konnte nicht gespeichert werden."); }
    finally { setSaving(false); }
  }

  async function deleteAlias(genre: Genre, alias: GenreAlias) {
    if (!window.confirm(`Alias „${alias.value}“ entfernen?`)) return;
    setSaving(true); setError("");
    try {
      const response = await fetch(`/api/v1/genres/${encodeURIComponent(genre.id)}/aliases/${encodeURIComponent(alias.id)}`, { method: "DELETE", credentials: "same-origin" });
      if (!response.ok) throw new Error(await apiError(response));
      setGenres((current) => current.map((item) => item.id === genre.id ? { ...item, aliases: item.aliases.filter((value) => value.id !== alias.id) } : item));
    } catch (cause) { setError(cause instanceof Error ? cause.message : "Alias konnte nicht entfernt werden."); }
    finally { setSaving(false); }
  }

  async function editAlias(genre: Genre, alias: GenreAlias) {
    const value = window.prompt("Alias bearbeiten", alias.value);
    if (value === null || !value.trim() || value.trim() === alias.value) return;
    setSaving(true); setError("");
    try {
      const response = await fetch(`/api/v1/genres/${encodeURIComponent(genre.id)}/aliases/${encodeURIComponent(alias.id)}`, {
        method: "PUT", credentials: "same-origin", headers: { "content-type": "application/json" }, body: JSON.stringify({ value: value.trim() }),
      });
      if (!response.ok) throw new Error(await apiError(response));
      const saved = await response.json() as GenreAlias;
      setGenres((current) => current.map((item) => item.id === genre.id ? { ...item, aliases: item.aliases.map((itemAlias) => itemAlias.id === saved.id ? saved : itemAlias) } : item));
    } catch (cause) { setError(cause instanceof Error ? cause.message : "Alias konnte nicht bearbeitet werden."); }
    finally { setSaving(false); }
  }

  return <main className="app-page min-h-dvh bg-black px-4 py-6 text-white sm:py-8 md:px-10">
    <div className="mx-auto max-w-5xl">
      <header className="flex flex-col gap-4 sm:flex-row sm:items-end sm:justify-between">
        <div><p className="text-xs uppercase tracking-[0.2em] text-neutral-500">NoizyCologne / Einstellungen</p><h1 className="mt-2 text-3xl font-bold">Genres</h1><p className="mt-3 text-neutral-400">Aktive Genres und OCR-Aliase für die lokale Flyer-Erkennung.</p></div>
        <button type="button" onClick={newGenre} className="min-h-12 rounded-lg bg-white px-5 py-3 text-sm font-semibold text-black">+ Genre anlegen</button>
      </header>
      <section className="mt-8 rounded-xl border border-white/10 bg-neutral-950 p-4 sm:p-5">
        <h2 className="text-lg font-semibold">Instagram-Caption</h2>
        <p className="mt-2 text-sm leading-6 text-neutral-400">
          Dieser Pre-Text steht am Anfang neuer Captions. Die restliche Vorlage wird aus Veranstaltung, Genres, DJ-Mentions und Location-Handle aufgebaut.
        </p>
        <div className="mt-4 flex flex-col gap-3 sm:flex-row">
          <label className="flex-1">
            <span className="mb-2 block text-sm text-neutral-300">Pre-Text / Accountname</span>
            <input className={inputStyle} value={captionPreText} onChange={(event) => setCaptionPreText(event.target.value)} maxLength={100} placeholder={DEFAULT_CAPTION_PRETEXT} />
          </label>
          <button type="button" onClick={saveCaptionPreText} className="min-h-11 self-end rounded-lg bg-white px-4 py-2 text-sm font-semibold text-black">
            {captionPreTextSaved ? "Gespeichert" : "Pre-Text speichern"}
          </button>
        </div>
        <p className="mt-3 text-xs text-neutral-500">Beispiel: {captionPreText.trim() || DEFAULT_CAPTION_PRETEXT} Samstag 31.10.26 Garage, Punk, Doom mit @dj im @location</p>
      </section>
      <div className="mt-8 flex flex-col gap-3 sm:flex-row"><input className={inputStyle} value={query} onChange={(event) => setQuery(event.target.value)} placeholder="Genres und Aliase durchsuchen" aria-label="Genres durchsuchen" /><button type="button" onClick={() => void loadGenres()} className="min-h-11 rounded-lg border border-white/15 px-4 py-2 text-sm">Neu laden</button></div>
      {error && <p role="alert" className="mt-4 rounded-lg border border-red-400/40 bg-red-950/30 p-3 text-sm text-red-300">{error}</p>}
      {editorOpen ? <form onSubmit={saveGenre} className="mt-6 rounded-xl border border-white/20 bg-neutral-950 p-4 sm:p-6"><h2 className="text-xl font-semibold">{editingId ? "Genre bearbeiten" : "Neues Genre"}</h2><div className="mt-4 grid gap-4 sm:grid-cols-2"><label><span className="mb-2 block text-sm text-neutral-300">Kanonischer Name</span><input className={inputStyle} value={canonicalName} onChange={(event) => setCanonicalName(event.target.value)} maxLength={100} required /></label><label><span className="mb-2 block text-sm text-neutral-300">Anzeigename</span><input className={inputStyle} value={displayName} onChange={(event) => setDisplayName(event.target.value)} maxLength={100} required /></label></div><label className="mt-4 flex min-h-11 items-center gap-3 text-sm"><input type="checkbox" checked={active} onChange={(event) => setActive(event.target.checked)} className="size-4 accent-white" /> Für OCR aktiv</label><div className="mt-5 flex gap-3"><button type="submit" disabled={saving} className="rounded-lg bg-white px-5 py-3 text-sm font-semibold text-black disabled:opacity-60">{saving ? "Speichern ..." : "Speichern"}</button><button type="button" onClick={() => setEditorOpen(false)} className="rounded-lg border border-white/15 px-5 py-3 text-sm">Abbrechen</button></div></form> : null}
      <section className="mt-8 rounded-xl border border-white/10 bg-neutral-950 p-4 sm:p-5">{loading ? <p role="status" className="py-8 text-center text-sm text-neutral-400">Genres werden geladen ...</p> : visibleGenres.length === 0 ? <p className="py-8 text-center text-sm text-neutral-500">Keine Genres gefunden.</p> : <div className="divide-y divide-white/10">{visibleGenres.map((genre) => <article key={genre.id} className="py-5"><div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between"><div><h2 className="font-semibold">{genre.displayName} <span className="ml-2 text-xs font-normal text-neutral-500">{genre.active ? "aktiv" : "inaktiv"}</span></h2><p className="mt-1 text-xs text-neutral-500">Kanonisch: {genre.canonicalName}</p><div className="mt-3 flex flex-wrap gap-2">{genre.aliases.map((alias) => <span key={alias.id} className="inline-flex items-center gap-1 rounded-full border border-white/15 px-2.5 py-1 text-xs text-neutral-300">{alias.value}<button type="button" disabled={saving} onClick={() => void editAlias(genre, alias)} aria-label={`Alias ${alias.value} bearbeiten`} className="text-neutral-500 hover:text-white">✎</button><button type="button" disabled={saving} onClick={() => void deleteAlias(genre, alias)} aria-label={`Alias ${alias.value} entfernen`} className="text-neutral-500 hover:text-white">×</button></span>)}</div><form onSubmit={(event) => void addAlias(event, genre)} className="mt-3 flex gap-2"><input className={inputStyle} value={aliasGenreId === genre.id ? newAlias : ""} onChange={(event) => { setAliasGenreId(genre.id); setNewAlias(event.target.value); }} placeholder="Neuer Alias" aria-label={`Neuer Alias für ${genre.displayName}`} /><button type="submit" disabled={saving} className="rounded-lg border border-white/15 px-3 py-2 text-sm">+</button></form></div><div className="flex shrink-0 gap-2"><button type="button" onClick={() => editGenre(genre)} className="min-h-11 rounded-lg border border-white/15 px-3 py-2 text-sm">Bearbeiten</button><button type="button" disabled={saving} onClick={() => void toggleActive(genre)} className="min-h-11 rounded-lg border border-white/15 px-3 py-2 text-sm">{genre.active ? "Deaktivieren" : "Aktivieren"}</button></div></div></article>)}</div>}</section>
    </div>
  </main>;
}
