"use client";

import { useRef, useState } from "react";
import DashboardModal from "../../DashboardModal";

type Issue = { line: number; genre: string; field: string; message: string };
type ImportRow = { line: number; canonicalName: string; displayName: string; active: boolean; aliases: string[]; metadata: Record<string, string>; action: string };
type Report = { total: number; created: number; unchanged: number; supplemented: number; inactive: number; aliases: number; aliasesAdded: number; valid: boolean; applied: boolean; rows: ImportRow[]; conflicts: Issue[] };
const maxBytes = 1_048_576;
const base = JSON.stringify({ schemaVersion: 1, preset: "base-v1" });

export default function GenreImport({ disabled, onImported }: { disabled: boolean; onImported: (message: string) => Promise<void> }) {
  const [open, setOpen] = useState(false);
  const [body, setBody] = useState("");
  const [filename, setFilename] = useState("");
  const [report, setReport] = useState<Report | null>(null);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const inFlight = useRef(false);

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
      const data = await response.json() as Report & { message?: string; detail?: string; error?: string };
      if (!response.ok) {
        if (Array.isArray(data.conflicts)) setReport(data);
        throw new Error(data.message || data.detail || data.error || (response.status === 409 ? "Der Katalog hat sich geändert oder enthält Konflikte. Bitte die Vorschau erneut prüfen." : `Importprüfung fehlgeschlagen (HTTP ${response.status}).`));
      }
      setReport(data);
      if (apply) {
        if (!data.applied) throw new Error("Der Import wurde nicht bestätigt. Bitte die Vorschau erneut prüfen.");
        const message = data.created === 0 && data.aliasesAdded === 0
          ? `Keine Änderungen – ${data.unchanged} Genres bereits vorhanden.`
          : `${data.total} Genres geprüft · ${data.created} neu angelegt · ${data.aliasesAdded} Aliase übernommen${data.supplemented ? ` · ${data.supplemented} vorhandene Genres ergänzt` : ""}.`;
        setOpen(false);
        await onImported(message);
      }
    } catch (cause) {
      setError(cause instanceof TypeError || cause instanceof SyntaxError
        ? apply ? "Import nicht bestätigt. Der Server kann bereits gespeichert haben. Bitte die Vorschau erneut prüfen; vorhandene Genres bleiben geschützt." : "Die Importvorschau ist momentan nicht erreichbar. Bitte erneut prüfen."
        : cause instanceof Error ? cause.message : "Import nicht bestätigt. Eine neue Vorschau ist erforderlich.");
      if (apply) setReport(current => current ? { ...current, valid: false } : null);
    } finally { inFlight.current = false; setBusy(false); }
  }

  return <>
    <button type="button" className="cc-button" disabled={disabled} onClick={() => begin(false)}>Genres importieren</button>
    <button type="button" className="cc-button" disabled={disabled} onClick={() => begin(true)}>Basisgenres wiederherstellen</button>
    <DashboardModal open={open} busy={busy} onClose={() => { if (!inFlight.current) setOpen(false); }} title="Genres importieren" description="Datei analysieren oder die 38 Basisgenres ausdrücklich wiederherstellen.">
      <div className="space-y-4" aria-busy={busy}>
        <p>Keine vorhandenen Genres werden überschrieben</p>
        <p className="text-sm text-neutral-400">Ein Werkseinstellungsreset leert die Fachdaten. Basisgenres lassen sich hier separat wiederherstellen.</p>
        <label className="block"><span className="mb-2 block">Katalogdatei (JSON oder CSV, höchstens 1 MiB)</span><input type="file" accept=".json,.csv" disabled={busy} className="block w-full min-w-0 text-sm" onChange={event => void choose(event.target.files?.[0])} /></label>
        {filename && <p className="break-words text-sm">{filename}</p>}
        {error && <p role="alert" className="cc-status-danger break-words rounded-lg border p-3 text-sm">{error}</p>}
        {report && <section aria-label="Importvorschau" className="space-y-3">
          <p role="status">{report.total} Genres geprüft · {report.created} neu · {report.unchanged} unverändert · {report.supplemented} mit neuen Aliasen · {report.total - report.inactive} aktiv · {report.inactive} inaktiv · {report.aliases} Aliase insgesamt ({report.aliasesAdded} neu)</p>
          {report.conflicts.length > 0 && <div role="alert"><h3 className="font-semibold">{report.conflicts.length} Konflikte – kein Import möglich</h3><ul className="mt-2 space-y-2">{report.conflicts.map((issue, index) => <li key={index} className="break-words">Zeile/Eintrag {issue.line} · {issue.genre} · {issue.field}: {issue.message}</li>)}</ul></div>}
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
