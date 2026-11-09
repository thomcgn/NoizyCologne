"use client";

import { useEffect, useState, type FormEvent } from "react";
import {
  CAPTION_PRETEXT_STORAGE_KEY,
  DEFAULT_CAPTION_PRETEXT,
} from "../../captionSettings";
import DashboardModal from "../../DashboardModal";
import DataCollection, { type CollectionColumn } from "../../DataCollection";

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
  const [editorOpen, setEditorOpen] = useState(false);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [canonicalName, setCanonicalName] = useState("");
  const [displayName, setDisplayName] = useState("");
  const [active, setActive] = useState(true);
  const [editorError, setEditorError] = useState("");
  const [notice, setNotice] = useState("");
  const [baseline, setBaseline] = useState({ canonicalName: "", displayName: "", active: true });
  const [aliasDrafts, setAliasDrafts] = useState<Record<string, string>>({});
  const [aliasErrors, setAliasErrors] = useState<Record<string, string>>({});
  const [aliasEditor, setAliasEditor] = useState<{ genreId: string; aliasId: string; value: string } | null>(null);
  const [aliasToDelete, setAliasToDelete] = useState<{ genreId: string; aliasId: string } | null>(null);
  const dirty = canonicalName !== baseline.canonicalName || displayName !== baseline.displayName || active !== baseline.active;

  useEffect(() => {
    if (!editorOpen || !dirty) return;
    function beforeUnload(event: BeforeUnloadEvent) { event.preventDefault(); event.returnValue = ""; }
    window.addEventListener("beforeunload", beforeUnload);
    return () => window.removeEventListener("beforeunload", beforeUnload);
  }, [editorOpen, dirty]);

  useEffect(() => {
    if (!editorError) return;
    document.getElementById("genre-editor-error")?.focus();
  }, [editorError]);

  function closeEditor() {
    if (saving || (dirty && !window.confirm("Ungespeicherte Änderungen verwerfen?"))) return;
    setEditorOpen(false);
  }
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

  function newGenre() {
    setBaseline({ canonicalName: "", displayName: "", active: true });
    setEditingId(null); setCanonicalName(""); setDisplayName(""); setActive(true);
    setEditorError(""); setNotice(""); setEditorOpen(true);
  }

  function editGenre(genre: Genre) {
    setBaseline({ canonicalName: genre.canonicalName, displayName: genre.displayName, active: genre.active });
    setEditingId(genre.id); setCanonicalName(genre.canonicalName); setDisplayName(genre.displayName); setActive(genre.active);
    setEditorError(""); setNotice(""); setEditorOpen(true);
  }

  async function saveGenre(event: FormEvent) {
    event.preventDefault();
    if (saving) return;
    if (!canonicalName.trim() || !displayName.trim()) { setEditorError("Bitte kanonischen Namen und Anzeigenamen eingeben."); return; }
    setSaving(true); setEditorError("");
    try {
      const response = await fetch(editingId ? `/api/v1/genres/${encodeURIComponent(editingId)}` : "/api/v1/genres", {
        method: editingId ? "PUT" : "POST", credentials: "same-origin",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ canonicalName: canonicalName.trim(), displayName: displayName.trim(), active }),
      });
      if (!response.ok) throw new Error(await apiError(response));
      const saved = await response.json() as Genre;
      setGenres((current) => editingId ? current.map((item) => item.id === saved.id ? saved : item) : [...current, saved]);
      setBaseline({ canonicalName: saved.canonicalName, displayName: saved.displayName, active: saved.active });
      setEditorOpen(false);
      setNotice(`Genre „${saved.displayName}“ gespeichert.`);
    } catch (cause) {
      setEditorError(cause instanceof Error ? cause.message : "Genre konnte nicht gespeichert werden.");
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
    if (saving) return;
    const value = (aliasDrafts[genre.id] ?? "").trim();
    if (!value) { setAliasErrors(current => ({ ...current, [genre.id]: "Bitte einen Alias eingeben." })); return; }
    setSaving(true); setAliasErrors(current => ({ ...current, [genre.id]: "" }));
    try {
      const response = await fetch(`/api/v1/genres/${encodeURIComponent(genre.id)}/aliases`, {
        method: "POST", credentials: "same-origin", headers: { "content-type": "application/json" }, body: JSON.stringify({ value }),
      });
      if (!response.ok) throw new Error(await apiError(response));
      const alias = await response.json() as GenreAlias;
      setGenres((current) => current.map((item) => item.id === genre.id ? { ...item, aliases: [...item.aliases, alias] } : item));
      setAliasDrafts(current => ({ ...current, [genre.id]: "" }));
      setNotice(`Alias „${alias.value}“ für „${genre.displayName}“ gespeichert.`);
    } catch (cause) { setAliasErrors(current => ({ ...current, [genre.id]: cause instanceof Error ? cause.message : "Alias konnte nicht gespeichert werden." })); }
    finally { setSaving(false); }
  }

  async function deleteAlias(genre: Genre, alias: GenreAlias) {
    if (saving) return;
    setSaving(true); setAliasErrors(current => ({ ...current, [genre.id]: "" }));
    try {
      const response = await fetch(`/api/v1/genres/${encodeURIComponent(genre.id)}/aliases/${encodeURIComponent(alias.id)}`, { method: "DELETE", credentials: "same-origin" });
      if (!response.ok) throw new Error(await apiError(response));
      setGenres((current) => current.map((item) => item.id === genre.id ? { ...item, aliases: item.aliases.filter((value) => value.id !== alias.id) } : item));
      setAliasToDelete(null);
      setNotice(`Alias „${alias.value}“ entfernt. Das Genre bleibt erhalten.`);
    } catch (cause) { setAliasErrors(current => ({ ...current, [genre.id]: cause instanceof Error ? cause.message : "Alias konnte nicht entfernt werden." })); }
    finally { setSaving(false); }
  }

  async function saveAlias(event: FormEvent, genre: Genre, alias: GenreAlias) {
    event.preventDefault();
    if (saving || aliasEditor?.genreId !== genre.id || aliasEditor.aliasId !== alias.id) return;
    const value = aliasEditor.value.trim();
    if (!value) { setAliasErrors(current => ({ ...current, [genre.id]: "Bitte einen Alias eingeben." })); return; }
    setSaving(true); setAliasErrors(current => ({ ...current, [genre.id]: "" }));
    try {
      const response = await fetch(`/api/v1/genres/${encodeURIComponent(genre.id)}/aliases/${encodeURIComponent(alias.id)}`, {
        method: "PUT", credentials: "same-origin", headers: { "content-type": "application/json" }, body: JSON.stringify({ value }),
      });
      if (!response.ok) throw new Error(await apiError(response));
      const saved = await response.json() as GenreAlias;
      setGenres((current) => current.map((item) => item.id === genre.id ? { ...item, aliases: item.aliases.map((itemAlias) => itemAlias.id === saved.id ? saved : itemAlias) } : item));
      setAliasEditor(null); setNotice(`Alias „${saved.value}“ gespeichert.`);
    } catch (cause) { setAliasErrors(current => ({ ...current, [genre.id]: cause instanceof Error ? cause.message : "Alias konnte nicht bearbeitet werden." })); }
    finally { setSaving(false); }
  }

  function renderAliases(genre: Genre) {
    return <div className="space-y-3">
      <ul className="space-y-2">{genre.aliases.map(alias => <li key={alias.id}>
        {aliasEditor?.genreId === genre.id && aliasEditor.aliasId === alias.id ?
          <form onSubmit={event => void saveAlias(event, genre, alias)} className="flex flex-wrap items-center gap-2">
            <label className="min-w-0 basis-full sm:basis-auto sm:flex-1"><span className="sr-only">Alias {alias.value} für {genre.displayName} bearbeiten</span><input className={inputStyle} value={aliasEditor.value} disabled={saving} maxLength={100} onChange={event => setAliasEditor({ genreId: genre.id, aliasId: alias.id, value: event.target.value })} /></label>
            <button type="submit" disabled={saving} className="cc-button">Alias speichern</button>
            <button type="button" disabled={saving} className="cc-button" onClick={() => setAliasEditor(null)}>Abbrechen</button>
          </form> : <div className="flex flex-wrap items-center gap-2"><span>{alias.value}</span>
            <button type="button" disabled={saving} className="cc-button" aria-label={`Alias ${alias.value} für ${genre.displayName} bearbeiten`} onClick={() => { setAliasEditor({ genreId: genre.id, aliasId: alias.id, value: alias.value }); setAliasToDelete(null); }}>Bearbeiten</button>
            <button type="button" disabled={saving} className="cc-button" aria-label={`Alias ${alias.value} für ${genre.displayName} entfernen`} onClick={() => { setAliasToDelete({ genreId: genre.id, aliasId: alias.id }); setAliasEditor(null); }}>Entfernen</button>
          </div>}
        {aliasToDelete?.genreId === genre.id && aliasToDelete.aliasId === alias.id && <div role="group" aria-label={`Alias ${alias.value} entfernen`} className="mt-2 space-y-2">
          <p>Alias „{alias.value}“ entfernen? Das Genre bleibt erhalten. Dieser Begriff wird nicht mehr als Alias erkannt.</p>
          <div className="flex flex-wrap gap-2"><button type="button" disabled={saving} className="cc-button cc-button-danger" onClick={() => void deleteAlias(genre, alias)}>Alias endgültig entfernen</button><button type="button" disabled={saving} className="cc-button" onClick={() => setAliasToDelete(null)}>Abbrechen</button></div>
        </div>}
      </li>)}</ul>
      <form onSubmit={event => void addAlias(event, genre)} className="flex flex-wrap gap-2">
        <label className="min-w-0 basis-full sm:basis-auto sm:flex-1"><span className="sr-only">Neuer Alias für {genre.displayName}</span><input className={inputStyle} value={aliasDrafts[genre.id] ?? ""} disabled={saving} maxLength={100} onChange={event => setAliasDrafts(current => ({ ...current, [genre.id]: event.target.value }))} placeholder="Neuer Alias" /></label>
        <button type="submit" disabled={saving} className="cc-button" aria-label={`Alias für ${genre.displayName} hinzufügen`}>Alias hinzufügen</button>
      </form>
      {aliasErrors[genre.id] && <p role="alert" className="text-sm text-red-400">{aliasErrors[genre.id]}</p>}
    </div>;
  }

  const columns: CollectionColumn<Genre>[] = [
    { key: "name", label: "Genre", sortValue: genre => genre.displayName, render: genre => <div><strong>{genre.displayName}</strong><p className="mt-1 text-xs text-neutral-500">Kanonisch: {genre.canonicalName}</p><p className="mt-1 text-xs text-neutral-400">{genre.active ? "Aktiv für Genreerkennung und Discovery" : "Inaktiv"}</p></div> },
    { key: "aliases", label: "Aliase", className: "col-span-full", render: renderAliases },
  ];

  return <main className="app-page min-h-dvh bg-black px-4 py-6 text-white sm:py-8 md:px-10">
    <div className="mx-auto max-w-5xl">
      <header className="flex flex-col gap-4 sm:flex-row sm:items-end sm:justify-between">
        <div><p className="text-xs uppercase tracking-[0.2em] text-neutral-500">NoizyCologne / Einstellungen</p><h1 className="mt-2 text-3xl font-bold">Genres</h1><p className="mt-3 text-neutral-400">Genres und Aliase für lokale Flyer-Erkennung und Discovery.</p></div>
        <button type="button" disabled={saving} onClick={newGenre} className="min-h-12 rounded-lg bg-white px-5 py-3 text-sm font-semibold text-black">+ Genre anlegen</button>
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
      <div className="mt-8"><button type="button" disabled={saving || loading} onClick={() => void loadGenres()} className="cc-button">Neu laden</button></div>
      {error && <p role="alert" className="mt-4 rounded-lg border border-red-400/40 bg-red-950/30 p-3 text-sm text-red-300">{error}</p>}
      {notice && <p role="status" className="mt-4 rounded-lg border border-white/15 p-3 text-sm">{notice}</p>}
      <DashboardModal open={editorOpen} onClose={closeEditor} title={editingId ? "Genre bearbeiten" : "Neues Genre"} description="Aktive Genres und Aliase für Genreerkennung und Discovery verwalten.">
        <form onSubmit={saveGenre} className="space-y-4" aria-busy={saving}>
          {editorError && <p id="genre-editor-error" tabIndex={-1} role="alert" className="rounded-lg border border-red-400/40 p-3 text-sm text-red-400">{editorError}</p>}
          <fieldset disabled={saving} className="space-y-4">
            <div className="grid gap-4 sm:grid-cols-2">
              <label><span className="mb-2 block text-sm text-neutral-300">Kanonischer Name</span><input className={inputStyle} value={canonicalName} onChange={event => setCanonicalName(event.target.value)} maxLength={100} required /></label>
              <label><span className="mb-2 block text-sm text-neutral-300">Anzeigename</span><input className={inputStyle} value={displayName} onChange={event => setDisplayName(event.target.value)} maxLength={100} required /></label>
            </div>
            <label className="flex min-h-11 items-center gap-3 text-sm"><input type="checkbox" checked={active} onChange={event => setActive(event.target.checked)} className="size-4 accent-white" /> Für Genreerkennung und Discovery aktiv</label>
            <p className="text-sm text-neutral-400">Aktive Genres werden mit ihren Aliasen für Flyer-Vorschläge und den Genreabgleich von Veranstaltungen berücksichtigt.</p>
            <div className="flex flex-wrap gap-3"><button type="submit" className="rounded-lg bg-white px-5 py-3 text-sm font-semibold text-black disabled:opacity-60">{saving ? "Speichern ..." : "Speichern und schließen"}</button><button type="button" onClick={closeEditor} className="rounded-lg border border-white/15 px-5 py-3 text-sm">Abbrechen</button></div>
          </fieldset>
        </form>
      </DashboardModal>
      <section className="mt-8 rounded-xl border border-white/10 bg-neutral-950 p-4 sm:p-5">
        {loading ? <p role="status" className="py-8 text-center text-sm text-neutral-400">Genres werden geladen ...</p> : <DataCollection items={genres} columns={columns} label="Genres" storageKey="noizy-view-genres" getName={genre => genre.displayName} searchLabel="Genres und Aliase durchsuchen" searchText={genre => [genre.canonicalName, genre.displayName, ...genre.aliases.map(alias => alias.value)].join(" ")} empty={genres.length ? "Keine passenden Genres oder Aliase gefunden." : "Noch keine Genres erfasst."} actions={genre => <div className="flex flex-wrap gap-2"><button type="button" disabled={saving} onClick={() => editGenre(genre)} className="cc-button">Bearbeiten</button><button type="button" disabled={saving} onClick={() => void toggleActive(genre)} className="cc-button">{genre.active ? "Deaktivieren" : "Aktivieren"}</button></div>} />}
      </section>
    </div>
  </main>;
}
