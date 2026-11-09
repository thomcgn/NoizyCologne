"use client";

import { useEffect, useRef, useState, type ReactNode } from "react";
import DashboardModal from "../../DashboardModal";

type Issue = { line: number; genre: string; field: string; message: string };
type ImportRow = { line: number; canonicalName: string; displayName: string; active: boolean; aliases: string[]; metadata: Record<string, string>; action: string };
type Report = { total: number; created: number; unchanged: number; supplemented: number; inactive: number; aliases: number; aliasesAdded: number; valid: boolean; applied: boolean; rows: ImportRow[]; conflicts: Issue[] };
const maxBytes = 1_048_576;
const base = JSON.stringify({ schemaVersion: 1, preset: "base-v1" });

function object(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}
function count(value: unknown): value is number {
  return typeof value === "number" && Number.isSafeInteger(value) && value >= 0;
}
function isReport(value: unknown): value is Report {
  return object(value) && ["total", "created", "unchanged", "supplemented", "inactive", "aliases", "aliasesAdded"].every(key => count(value[key]))
    && typeof value.valid === "boolean" && typeof value.applied === "boolean"
    && Array.isArray(value.rows) && value.rows.every((row: unknown) => object(row) && count(row.line)
      && typeof row.canonicalName === "string" && typeof row.displayName === "string" && typeof row.active === "boolean"
      && typeof row.action === "string" && Array.isArray(row.aliases) && row.aliases.every((alias: unknown) => typeof alias === "string")
      && object(row.metadata) && Object.values(row.metadata).every(item => typeof item === "string"))
    && Array.isArray(value.conflicts) && value.conflicts.every((issue: unknown) => object(issue) && count(issue.line)
      && typeof issue.genre === "string" && typeof issue.field === "string" && typeof issue.message === "string")
    && (!value.valid || value.conflicts.length === 0) && (!value.applied || value.valid);
}

type ImportActions = { importAction: ReactNode; restoreAction: ReactNode };

export default function GenreImport({ disabled, onImported, renderActions, importPrimary = true }: { disabled: boolean; onImported: (message: string) => Promise<void>; renderActions?: (actions: ImportActions) => ReactNode; importPrimary?: boolean }) {
  const [open, setOpen] = useState(false);
  const [body, setBody] = useState("");
  const [filename, setFilename] = useState("");
  const [report, setReport] = useState<Report | null>(null);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const inFlight = useRef(false);
  const errorFocus = useRef<HTMLParagraphElement>(null);
  const conflictFocus = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (error) errorFocus.current?.focus();
    else if (report && !report.valid && report.conflicts.length) conflictFocus.current?.focus();
  }, [error, report]);

  function begin(preset: boolean) {
    setBody(preset ? base : ""); setFilename(preset ? "38 Basisgenres · Version 1" : "");
    setReport(null); setError(""); setOpen(true);
  }
  async function choose(file?: File) {
    if (inFlight.current) return;
    setReport(null); setBody(""); setError(""); setFilename(file?.name ?? "");
    if (!file) return;
    if (!/\.(json|csv)$/i.test(file.name)) { setError("Bitte eine JSON- oder CSV-Datei wählen."); return; }
    if (file.size > maxBytes) { setError("Die Datei darf höchstens 1 MiB groß sein."); return; }
    inFlight.current = true; setBusy(true);
    try {
      const text = new TextDecoder("utf-8", { fatal: true }).decode(await file.arrayBuffer()).replace(/^\uFEFF/, "");
      const payload = /\.csv$/i.test(file.name) ? JSON.stringify({ schemaVersion: 1, csv: text }) : text;
      if (new TextEncoder().encode(payload).length > maxBytes) throw new Error("Die Importanfrage einschließlich CSV-Verpackung darf höchstens 1 MiB groß sein.");
      setBody(payload);
    } catch (cause) { setError(cause instanceof TypeError ? "Die Datei ist kein gültiges UTF-8 oder konnte nicht gelesen werden." : cause instanceof Error ? cause.message : "Datei konnte nicht gelesen werden."); }
    finally { inFlight.current = false; setBusy(false); }
  }
  async function run(apply: boolean) {
    if (inFlight.current || !body || (apply && !report?.valid)) return;
    inFlight.current = true; setBusy(true); setError("");
    if (!apply) setReport(null);
    try {
      const response = await fetch(`/api/v1/genres/import${apply ? "" : "/preview"}`, {
        method: "POST", credentials: "same-origin", headers: { "content-type": "application/json" }, body,
      });
      const data: unknown = await response.json();
      if (!response.ok) {
        if (isReport(data) && !data.applied) setReport({ ...data, valid: false });
        if (apply && response.status >= 500) throw new Error("Import nicht bestätigt. Der Server kann bereits gespeichert haben. Bitte den Katalog und die Vorschau erneut prüfen.");
        const message = object(data) ? [data.message, data.detail, data.error].find(item => typeof item === "string" && item.trim()) : null;
        throw new Error(typeof message === "string" ? message : response.status === 409 ? "Der Katalog hat sich geändert oder enthält Konflikte. Bitte die Vorschau erneut prüfen." : `Importprüfung fehlgeschlagen (HTTP ${response.status}).`);
      }
      if (!isReport(data)) throw new Error("Die Importantwort ist unvollständig. Bitte den Katalog prüfen und die Vorschau erneut laden.");
      if (!apply && data.applied) throw new Error("Die Vorschau meldet einen unerwarteten Schreibstatus. Bitte den Katalog prüfen.");
      setReport(data);
      if (apply) {
        if (!data.applied) throw new Error("Der Import wurde nicht bestätigt. Bitte die Vorschau erneut prüfen.");
        const message = data.created === 0 && data.aliasesAdded === 0
          ? `Keine Änderungen – ${data.unchanged} Genres bereits vorhanden.`
          : `${data.total} Genres geprüft · ${data.created} neu angelegt · ${data.aliasesAdded} Aliase übernommen${data.supplemented ? ` · ${data.supplemented} vorhandene Genres ergänzt` : ""}.`;
        await onImported(message);
        setOpen(false);
      }
    } catch (cause) {
      setError(cause instanceof TypeError || cause instanceof SyntaxError
        ? apply ? "Import nicht bestätigt. Der Server kann bereits gespeichert haben. Bitte die Vorschau erneut prüfen; vorhandene Genres bleiben geschützt." : "Die Importvorschau ist momentan nicht erreichbar. Bitte erneut prüfen."
        : cause instanceof Error ? cause.message : "Import nicht bestätigt. Eine neue Vorschau ist erforderlich.");
      if (apply) setReport(current => current ? { ...current, valid: false } : null);
    } finally { inFlight.current = false; setBusy(false); }
  }

  const actions: ImportActions = {
    importAction: <button type="button" className={`cc-button${importPrimary ? " cc-button-primary" : ""}`} disabled={disabled || busy} onClick={() => begin(false)}>Genres importieren</button>,
    restoreAction: <button type="button" className="cc-button" disabled={disabled || busy} onClick={() => begin(true)}>Basisgenres wiederherstellen</button>,
  };
  return <>
    {renderActions ? renderActions(actions) : <>{actions.importAction}{actions.restoreAction}</>}
    <DashboardModal open={open} busy={busy} onClose={() => { if (!inFlight.current) setOpen(false); }} title="Genres importieren" description="Datei analysieren oder die 38 Basisgenres ausdrücklich wiederherstellen.">
      <div className="space-y-4" aria-busy={busy}>
        <p>Keine vorhandenen Genres werden überschrieben</p>
        <p className="text-sm text-neutral-400">Eine Datei auswählen oder die Basisgenres separat prüfen. Erst „Import bestätigen“ übernimmt die geprüften Daten.</p>
        <label className="block"><span className="mb-2 block">Katalogdatei (JSON oder CSV, höchstens 1 MiB)</span><input type="file" accept=".json,.csv" disabled={busy} className="block w-full min-w-0 text-sm" onChange={event => void choose(event.target.files?.[0])} /></label>
        {report?.applied && <p role="status">Import bestätigt. Die Katalogliste wird aktualisiert. Ein erneuter Import ist nicht erforderlich.</p>}
        {filename && <p className="break-words text-sm">{filename}</p>}
        {error && <p ref={errorFocus} tabIndex={-1} role="alert" className="cc-status-danger break-words rounded-lg border p-3 text-sm">{error}</p>}
        {report && <section aria-label="Importvorschau" className="space-y-3">
          <p role="status">{report.total} Genres geprüft · {report.created} neu · {report.unchanged} unverändert · {report.supplemented} mit neuen Aliasen · {report.total - report.inactive} aktiv · {report.inactive} inaktiv · {report.aliases} Aliase insgesamt ({report.aliasesAdded} neu)</p>
          {report.conflicts.length > 0 && <div ref={conflictFocus} tabIndex={-1} role="alert"><h3 className="font-semibold">{report.conflicts.length} Konflikte – kein Import möglich</h3><ul className="mt-2 space-y-2">{report.conflicts.map((issue, index) => <li key={index} className="break-words">Zeile/Eintrag {issue.line} · {issue.genre} · {issue.field}: {issue.message}</li>)}</ul></div>}
          {report.conflicts.length > 0 && <a className="cc-button" download="genre-import-konflikte.json" href={`data:application/json;charset=utf-8,${encodeURIComponent(JSON.stringify(report, null, 2))}`}>Konfliktbericht herunterladen</a>}
          <details><summary className="cursor-pointer py-3">Alle {report.rows.length} Genres und Quellen anzeigen</summary><ul className="space-y-3">{report.rows.map((row, index) => <li key={index} className="break-words rounded-lg border border-white/15 p-3 text-sm"><strong>{row.displayName}</strong> · {row.active ? "Aktiv" : "Inaktiv"} · {row.action}<p>Kanonisch: {row.canonicalName} · Zeile/Eintrag {row.line}</p><p>Aliase: {row.aliases.join(" · ") || "Keine"}</p>{Object.entries(row.metadata).map(([key, value]) => value && <p key={key}>{key}: {value}</p>)}</li>)}</ul></details>
        </section>}
        <div className="sticky bottom-0 z-10 flex flex-wrap gap-3 border-t border-white/15 py-3" style={{ background: "var(--surface)" }}>
          <button type="button" className="cc-button" disabled={busy || !body} onClick={() => void run(false)}>{busy ? "Bitte warten …" : "Vorschau prüfen"}</button>
          <button type="button" className="cc-button cc-button-primary" disabled={busy || !report?.valid} onClick={() => void run(true)}>Import bestätigen</button>
          <button type="button" className="cc-button" disabled={busy} onClick={() => setOpen(false)}>Abbrechen</button>
        </div>
      </div>
    </DashboardModal>
  </>;
}
