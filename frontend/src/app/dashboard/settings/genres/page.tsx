"use client";

import { useEffect, useId, useRef, useState, type FormEvent } from "react";
import { DEFAULT_CAPTION_PRETEXT } from "../../captionSettings";
import { useCaptionPretext } from "../../useCaptionPretext";
import DashboardModal from "../../DashboardModal";
import GenreImport from "./GenreImport";
import DataCollection, { type CollectionColumn } from "../../DataCollection";

type GenreAlias = { id: string; value: string };
type Genre = {
  id: string;
  canonicalName: string;
  displayName: string;
  active: boolean;
  aliases: GenreAlias[];
};

function isAlias(value: unknown): value is GenreAlias {
  if (!value || typeof value !== "object") return false;
  const item = value as Record<string, unknown>;
  return typeof item.id === "string" && !!item.id.trim() && typeof item.value === "string";
}
function isGenre(value: unknown): value is Genre {
  if (!value || typeof value !== "object") return false;
  const item = value as Record<string, unknown>;
  return typeof item.id === "string" && !!item.id.trim() && typeof item.canonicalName === "string"
    && typeof item.displayName === "string" && typeof item.active === "boolean"
    && Array.isArray(item.aliases) && item.aliases.every(isAlias);
}

function AliasChips({ aliases, name }: { aliases: GenreAlias[]; name: string }) {
  const [expanded, setExpanded] = useState(false);
  const id = useId();
  const shown = expanded ? aliases : aliases.slice(0, 4);
  return <div className="flex flex-wrap items-center gap-1.5">
    <ul id={id} aria-label={`Aliase für ${name}`} className="flex min-w-0 flex-wrap gap-1.5">
      {shown.map(alias => <li key={alias.id} className="genre-alias-chip">{alias.value}</li>)}
    </ul>
    {!aliases.length && <span className="text-xs text-neutral-500">Keine Aliase</span>}
    {aliases.length > 4 && <button type="button" className="cc-button" aria-expanded={expanded} aria-controls={id} aria-label={expanded ? `Weniger Aliase für ${name}` : `${aliases.length - 4} weitere Aliase für ${name}`} onClick={() => setExpanded(value => !value)}>{expanded ? "Weniger" : `+${aliases.length - 4} weitere`}</button>}
  </div>;
}

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
  const mutationInFlight = useRef(false);
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
  const [addingAlias, setAddingAlias] = useState(false);
  const [aliasToDelete, setAliasToDelete] = useState<{ genreId: string; aliasId: string } | null>(null);
  const selectedGenre = genres.find(genre => genre.id === editingId);
  const aliasDirty = !!editingId && (!!aliasDrafts[editingId]?.trim() || (!!aliasEditor && aliasEditor.value !== selectedGenre?.aliases.find(alias => alias.id === aliasEditor.aliasId)?.value));
  const dirty = aliasDirty || canonicalName !== baseline.canonicalName || displayName !== baseline.displayName || active !== baseline.active;

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

  useEffect(() => {
    if (editingId && aliasErrors[editingId]) document.getElementById("genre-alias-error")?.focus();
  }, [editingId, aliasErrors]);

  useEffect(() => {
    if (editorOpen && (addingAlias || aliasEditor?.aliasId)) document.querySelector<HTMLInputElement>("#genre-aliases input")?.focus();
  }, [editorOpen, addingAlias, aliasEditor?.aliasId]);

  function closeEditor() {
    if (mutationInFlight.current || (dirty && !window.confirm("Ungespeicherte Änderungen verwerfen?"))) return;
    if (editingId) setAliasDrafts(current => ({ ...current, [editingId]: "" }));
    setAliasEditor(null); setAliasToDelete(null); setAddingAlias(false);
    setEditorOpen(false);
  }
  const { pretext: serverPretext, revision: serverRevision, save: persistCaptionPretext } = useCaptionPretext();
  const [captionDraft, setCaptionDraft] = useState<string | null>(null);
  const [captionBase, setCaptionBase] = useState(DEFAULT_CAPTION_PRETEXT);
  const [captionSaving, setCaptionSaving] = useState(false);
  const [captionError, setCaptionError] = useState("");
  const [captionSuccess, setCaptionSuccess] = useState<{ pretext: string; revision: number } | null>(null);
  const captionPreText = captionDraft ?? captionBase;
  const captionDirty = captionDraft !== null && captionDraft.trim() !== captionBase;
  const captionPreTextSaved = captionSuccess !== null
    && captionSuccess.pretext === captionPreText.trim()
    && captionSuccess.revision === serverRevision;

  async function loadGenres() {
    setLoading(true);
    setError("");
    try {
      const response = await fetch("/api/v1/genres", { credentials: "same-origin" });
      if (!response.ok) throw new Error(await apiError(response));
      const items: unknown = await response.json();
      if (!Array.isArray(items) || !items.every(isGenre)) throw new Error("Die Katalogantwort ist unvollständig. Bitte erneut laden.");
      setGenres(items);
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

  useEffect(() => {
    if (captionDraft !== null) return;
    setCaptionBase(current => current === serverPretext ? current : serverPretext);
  }, [serverPretext, captionDraft]);

  async function saveCaptionPreText() {
    if (captionSaving) return;
    const value = (captionDraft ?? captionBase).trim() || DEFAULT_CAPTION_PRETEXT;
    setCaptionSaving(true);
    setCaptionError("");
    setCaptionSuccess(null);
    try {
      const saved = await persistCaptionPretext(value);
      setCaptionDraft(null);
      setCaptionBase(saved.pretext);
      setCaptionSuccess({ pretext: saved.pretext, revision: saved.revision });
    } catch (cause) {
      setCaptionError(cause instanceof Error ? cause.message : "Der Pre-Text konnte nicht gespeichert werden.");
    } finally {
      setCaptionSaving(false);
    }
  }

  function newGenre() {
    setBaseline({ canonicalName: "", displayName: "", active: true });
    setEditingId(null); setCanonicalName(""); setDisplayName(""); setActive(true);
    setAliasEditor(null); setAliasToDelete(null); setAddingAlias(false); setAliasErrors({});
    setEditorError(""); setNotice(""); setEditorOpen(true);
  }

  function editGenre(genre: Genre) {
    setBaseline({ canonicalName: genre.canonicalName, displayName: genre.displayName, active: genre.active });
    setEditingId(genre.id); setCanonicalName(genre.canonicalName); setDisplayName(genre.displayName); setActive(genre.active);
    setAliasEditor(null); setAliasToDelete(null); setAddingAlias(false); setAliasErrors({});
    setEditorError(""); setNotice(""); setEditorOpen(true);
  }

  async function saveGenre(event: FormEvent) {
    event.preventDefault();
    if (mutationInFlight.current) return;
    if (aliasDirty) { setEditorError("Bitte den offenen Alias zuerst speichern oder seine Eingabe abbrechen. Bereits gespeicherte Aliase bleiben erhalten."); return; }
    if (!canonicalName.trim() || !displayName.trim()) { setEditorError("Bitte kanonischen Namen und Anzeigenamen eingeben."); return; }
    mutationInFlight.current = true;
    setSaving(true); setEditorError("");
    try {
      const response = await fetch(editingId ? `/api/v1/genres/${encodeURIComponent(editingId)}` : "/api/v1/genres", {
        method: editingId ? "PUT" : "POST", credentials: "same-origin",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ canonicalName: canonicalName.trim(), displayName: displayName.trim(), active }),
      });
      if (!response.ok) throw new Error(await apiError(response));
      const saved: unknown = await response.json();
      if (!isGenre(saved) || (editingId !== null && saved.id !== editingId)) throw new Error("Die Speicherung wurde nicht vollständig bestätigt. Bitte den Katalog neu laden und den Stand prüfen.");
      setGenres((current) => editingId ? current.map((item) => item.id === saved.id ? saved : item) : [...current, saved]);
      setBaseline({ canonicalName: saved.canonicalName, displayName: saved.displayName, active: saved.active });
      setEditorOpen(false);
      setNotice(`Genre „${saved.displayName}“ gespeichert.`);
    } catch (cause) {
      setEditorError(cause instanceof Error ? cause.message : "Genre konnte nicht gespeichert werden.");
    } finally { mutationInFlight.current = false; setSaving(false); }
  }

  async function addAlias(event: FormEvent, genre: Genre) {
    event.preventDefault();
    if (mutationInFlight.current) return;
    const value = (aliasDrafts[genre.id] ?? "").trim();
    if (!value) { setAliasErrors(current => ({ ...current, [genre.id]: "Bitte einen Alias eingeben." })); return; }
    mutationInFlight.current = true;
    setSaving(true); setAliasErrors(current => ({ ...current, [genre.id]: "" }));
    try {
      const response = await fetch(`/api/v1/genres/${encodeURIComponent(genre.id)}/aliases`, {
        method: "POST", credentials: "same-origin", headers: { "content-type": "application/json" }, body: JSON.stringify({ value }),
      });
      if (!response.ok) throw new Error(await apiError(response));
      const alias: unknown = await response.json();
      if (!isAlias(alias)) throw new Error("Der Alias wurde nicht vollständig bestätigt. Bitte den Katalog neu laden.");
      setGenres((current) => current.map((item) => item.id === genre.id ? { ...item, aliases: [...item.aliases, alias] } : item));
      setAliasDrafts(current => ({ ...current, [genre.id]: "" }));
      setAddingAlias(false);
      setNotice(`Alias „${alias.value}“ für „${genre.displayName}“ gespeichert.`);
    } catch (cause) { setAliasErrors(current => ({ ...current, [genre.id]: cause instanceof Error ? cause.message : "Alias konnte nicht gespeichert werden." })); }
    finally { mutationInFlight.current = false; setSaving(false); }
  }

  async function deleteAlias(genre: Genre, alias: GenreAlias) {
    if (mutationInFlight.current) return;
    mutationInFlight.current = true;
    setSaving(true); setAliasErrors(current => ({ ...current, [genre.id]: "" }));
    try {
      const response = await fetch(`/api/v1/genres/${encodeURIComponent(genre.id)}/aliases/${encodeURIComponent(alias.id)}`, { method: "DELETE", credentials: "same-origin" });
      if (!response.ok) throw new Error(await apiError(response));
      setGenres((current) => current.map((item) => item.id === genre.id ? { ...item, aliases: item.aliases.filter((value) => value.id !== alias.id) } : item));
      setAliasToDelete(null);
      setNotice(`Alias „${alias.value}“ entfernt. Das Genre bleibt erhalten.`);
    } catch (cause) { setAliasErrors(current => ({ ...current, [genre.id]: cause instanceof Error ? cause.message : "Alias konnte nicht entfernt werden." })); }
    finally { mutationInFlight.current = false; setSaving(false); }
  }

  async function saveAlias(event: FormEvent, genre: Genre, alias: GenreAlias) {
    event.preventDefault();
    if (mutationInFlight.current || aliasEditor?.genreId !== genre.id || aliasEditor.aliasId !== alias.id) return;
    const value = aliasEditor.value.trim();
    if (!value) { setAliasErrors(current => ({ ...current, [genre.id]: "Bitte einen Alias eingeben." })); return; }
    mutationInFlight.current = true;
    setSaving(true); setAliasErrors(current => ({ ...current, [genre.id]: "" }));
    try {
      const response = await fetch(`/api/v1/genres/${encodeURIComponent(genre.id)}/aliases/${encodeURIComponent(alias.id)}`, {
        method: "PUT", credentials: "same-origin", headers: { "content-type": "application/json" }, body: JSON.stringify({ value }),
      });
      if (!response.ok) throw new Error(await apiError(response));
      const saved: unknown = await response.json();
      if (!isAlias(saved) || saved.id !== alias.id) throw new Error("Der Alias wurde nicht vollständig bestätigt. Bitte den Katalog neu laden.");
      setGenres((current) => current.map((item) => item.id === genre.id ? { ...item, aliases: item.aliases.map((itemAlias) => itemAlias.id === saved.id ? saved : itemAlias) } : item));
      setAliasEditor(null); setNotice(`Alias „${saved.value}“ gespeichert.`);
    } catch (cause) { setAliasErrors(current => ({ ...current, [genre.id]: cause instanceof Error ? cause.message : "Alias konnte nicht bearbeitet werden." })); }
    finally { mutationInFlight.current = false; setSaving(false); }
  }

  function renderAliases(genre: Genre) {
    return <div className="space-y-3">
      <ul className="space-y-2">{genre.aliases.map(alias => <li key={alias.id}>
        {aliasEditor?.genreId === genre.id && aliasEditor.aliasId === alias.id ?
          <form onSubmit={event => void saveAlias(event, genre, alias)} className="flex flex-wrap items-center gap-2">
            <label className="min-w-0 basis-full sm:basis-auto sm:flex-1"><span className="sr-only">Alias {alias.value} für {genre.displayName} bearbeiten</span><input className={inputStyle} value={aliasEditor.value} disabled={saving} maxLength={100} onChange={event => setAliasEditor({ genreId: genre.id, aliasId: alias.id, value: event.target.value })} /></label>
            <button type="submit" disabled={saving} className="cc-button">Alias speichern</button>
            <button type="button" disabled={saving} className="cc-button" onClick={() => setAliasEditor(null)}>Abbrechen</button>
          </form> : <div className="flex flex-wrap items-center gap-2"><span className="genre-alias-chip">{alias.value}</span>
            <button type="button" disabled={saving || addingAlias || !!aliasEditor} className="cc-button" aria-label={`Alias ${alias.value} für ${genre.displayName} bearbeiten`} onClick={() => { setAliasEditor({ genreId: genre.id, aliasId: alias.id, value: alias.value }); setAliasToDelete(null); }}>Bearbeiten</button>
            <button type="button" disabled={saving || addingAlias || !!aliasEditor} className="cc-button" aria-label={`Alias ${alias.value} für ${genre.displayName} entfernen`} onClick={() => { setAliasToDelete({ genreId: genre.id, aliasId: alias.id }); setAliasEditor(null); }}>Entfernen</button>
          </div>}
        {aliasToDelete?.genreId === genre.id && aliasToDelete.aliasId === alias.id && <div role="group" aria-label={`Alias ${alias.value} entfernen`} className="mt-2 space-y-2">
          <p>Alias „{alias.value}“ entfernen? Das Genre bleibt erhalten. Dieser Begriff wird nicht mehr als Alias erkannt.</p>
          <div className="flex flex-wrap gap-2"><button type="button" disabled={saving} className="cc-button cc-button-danger" onClick={() => void deleteAlias(genre, alias)}>Alias endgültig entfernen</button><button type="button" disabled={saving} className="cc-button" onClick={() => setAliasToDelete(null)}>Abbrechen</button></div>
        </div>}
      </li>)}</ul>
      {addingAlias ? <form onSubmit={event => void addAlias(event, genre)} className="flex flex-wrap gap-2">
        <label className="min-w-0 basis-full sm:basis-auto sm:flex-1"><span className="sr-only">Neuer Alias für {genre.displayName}</span><input className={inputStyle} value={aliasDrafts[genre.id] ?? ""} disabled={saving} maxLength={100} onChange={event => setAliasDrafts(current => ({ ...current, [genre.id]: event.target.value }))} placeholder="Neuer Alias" /></label>
        <button type="submit" disabled={saving} className="cc-button" aria-label={`Alias für ${genre.displayName} hinzufügen`}>Alias speichern</button>
        <button type="button" disabled={saving} className="cc-button" onClick={() => { setAliasDrafts(current => ({ ...current, [genre.id]: "" })); setAddingAlias(false); }}>Aliaseingabe abbrechen</button>
      </form> : <button type="button" disabled={saving || !!aliasEditor || !!aliasToDelete} className="cc-button" onClick={() => setAddingAlias(true)}>+ Alias hinzufügen</button>}
      {aliasErrors[genre.id] && <p id="genre-alias-error" tabIndex={-1} role="alert" className="cc-status-danger rounded-lg border p-3 text-sm">{aliasErrors[genre.id]}</p>}
    </div>;
  }

  const columns: CollectionColumn<Genre>[] = [
    { key: "name", label: "Genre", sortValue: genre => genre.displayName, render: genre => <div><strong>{genre.displayName}</strong><p className="mt-1 break-words text-xs text-neutral-500">{genre.canonicalName}</p>{!genre.active && <span className="text-xs text-neutral-500">Inaktiv (Bestandsdaten)</span>}</div> },
    { key: "aliases", label: "Aliase", className: "col-span-full", render: genre => <AliasChips aliases={genre.aliases} name={genre.displayName} /> },
  ];

  return <main className="app-page min-h-dvh bg-black px-4 py-6 text-white sm:py-8 md:px-10">
    <div className="mx-auto max-w-5xl">
      <header className="flex flex-col gap-4 sm:flex-row sm:items-end sm:justify-between">
        <div><p className="text-xs uppercase tracking-[0.2em] text-neutral-500">NoizyCologne / Einstellungen</p><h1 className="mt-2 text-3xl font-bold">Genres</h1><p className="mt-3 text-neutral-400">Genres und Aliase für lokale Flyer-Erkennung und Discovery.</p></div>
      </header>
      <section className="mt-8 rounded-xl border border-white/10 bg-neutral-950 p-4 sm:p-5">
        <h2 className="text-lg font-semibold">Instagram-Caption</h2>
        <p className="mt-2 text-sm leading-6 text-neutral-400">
          Dieser Pre-Text steht am Anfang neuer Captions. Die restliche Vorlage wird aus Veranstaltung, Genres, DJ-Mentions und Location-Handle aufgebaut.
        </p>
        <div className="mt-4 flex flex-col gap-3 sm:flex-row">
          <label className="flex-1">
            <span className="mb-2 block text-sm text-neutral-300">Pre-Text / Accountname</span>
            <input className={inputStyle} value={captionPreText} onChange={(event) => { setCaptionDraft(event.target.value); setCaptionSuccess(null); setCaptionError(""); }} maxLength={100} placeholder={DEFAULT_CAPTION_PRETEXT} />
          </label>
          <button type="button" disabled={captionSaving} onClick={() => void saveCaptionPreText()} className="min-h-11 self-end rounded-lg bg-white px-4 py-2 text-sm font-semibold text-black">
            {captionSaving ? "Speichern …" : captionPreTextSaved ? "Gespeichert" : "Pre-Text speichern"}
          </button>
        </div>
        {captionDirty && <p className="mt-2 text-xs text-neutral-400">Nicht gespeicherte Änderung. „Gespeichert“ erscheint erst nach bestätigter Speicherung.</p>}
        {captionError && <p role="alert" className="cc-status-danger mt-3 rounded-lg border p-3 text-sm">{captionError}</p>}
        <p className="mt-3 text-xs text-neutral-500">Beispiel: {captionPreText.trim() || DEFAULT_CAPTION_PRETEXT} Samstag 31.10.26 Garage, Punk, Doom mit @dj im @location</p>
      </section>
      <DashboardModal variant="drawer" open={editorOpen} busy={saving} onClose={closeEditor} title={editingId ? "Genre bearbeiten" : "Genre anlegen"} description="Namen bearbeiten. Aliasänderungen werden einzeln sofort gespeichert.">
        <div className="genre-editor" aria-busy={saving}>
          {editorError && <p id="genre-editor-error" tabIndex={-1} role="alert" className="cc-status-danger rounded-lg border p-3 text-sm">{editorError}</p>}
          {notice && <p role="status" className="rounded-lg border border-white/15 p-3 text-sm">{notice}</p>}
          <form id="genre-names" onSubmit={saveGenre} aria-busy={saving}>
            <fieldset disabled={saving} className="space-y-4">
              <label className="block"><span className="mb-2 block text-sm text-neutral-300">Anzeigename</span><input className={inputStyle} value={displayName} onChange={event => setDisplayName(event.target.value)} maxLength={100} required /></label>
              <label className="block"><span className="mb-2 block text-sm text-neutral-300">Kanonischer Name</span><input className={inputStyle} value={canonicalName} onChange={event => setCanonicalName(event.target.value)} maxLength={100} required /></label>
            </fieldset>
          </form>
          <section id="genre-aliases" aria-label="Aliase bearbeiten" className="min-w-0 space-y-3 border-t border-white/15 pt-4">
            <h3 className="font-semibold">Aliase{selectedGenre ? ` (${selectedGenre.aliases.length})` : ""}</h3>
            {selectedGenre ? renderAliases(selectedGenre) : <p className="text-sm text-neutral-400">Zuerst das Genre speichern. Danach können Aliase ergänzt werden.</p>}
          </section>
          <footer className="genre-editor-footer">
            <button type="submit" form="genre-names" disabled={saving} className="cc-button cc-button-primary bg-white text-black">{saving ? "Speichern …" : "Speichern"}</button>
            <button type="button" disabled={saving} onClick={closeEditor} className="cc-button">Abbrechen</button>
          </footer>
        </div>
      </DashboardModal>
      <GenreImport importPrimary={false} disabled={saving || loading} onImported={async message => { setNotice(message); await loadGenres(); }} renderActions={({ importAction, restoreAction }) => {
        const actions = <div className="genre-toolbar-actions" role="group" aria-label="Genre-Aktionen">
          <button type="button" disabled={saving} onClick={newGenre} className="cc-button cc-button-primary">+ Genre anlegen</button>
          {importAction}
          <details className="genre-management">
            <summary className="cc-button">Verwaltung</summary>
            <div className="genre-management-actions">
              {restoreAction}
              <button type="button" disabled={saving || loading} onClick={() => void loadGenres()} className="cc-button">Neu laden</button>
            </div>
          </details>
        </div>;
        return <>
          {error && <p role="alert" className="cc-status-danger mt-4 rounded-lg border p-3 text-sm">{error}</p>}
          {notice && !editorOpen && <p role="status" className="mt-4 rounded-lg border border-white/15 p-3 text-sm">{notice}</p>}
          <section className="genre-catalog mt-8" aria-label="Genre-Katalog" aria-busy={loading}>
            <DataCollection items={genres} columns={columns} label="Genres" storageKey="noizy-view-genres" getName={genre => genre.displayName} searchLabel="Genres und Aliase durchsuchen" searchText={genre => [genre.canonicalName, genre.displayName, ...genre.aliases.map(alias => alias.value)].join(" ")} toolbar={actions} empty={loading ? "Genres werden geladen …" : error && !genres.length ? "Der Katalog konnte nicht geladen werden. Bitte „Neu laden“ unter „Verwaltung“ versuchen." : genres.length ? "Keine passenden Genres oder Aliase gefunden. Bitte den Suchbegriff ändern." : "Noch keine Genres erfasst. Über „Genres importieren“ einen Katalog übernehmen oder mit „+ Genre anlegen“ beginnen. Die Basisgenres können unter „Verwaltung“ separat wiederhergestellt werden."} actions={genre => <button type="button" disabled={saving} onClick={() => editGenre(genre)} className="cc-button">Bearbeiten</button>} />
          </section>
        </>;
      }} />
    </div>
  </main>;
}
