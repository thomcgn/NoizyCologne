"use client";

import { useEffect, useState } from "react";
import DashboardModal from "../DashboardModal";
import { discoveryApi } from "./discoveryApi";

export type ResetMode = "IGNORED" | "ALL" | "RESCAN";
type PreviewItem = { id: string; title: string; venue: string; oldStatus: string; newStatus: string; classification: string; reason: string };
type Preview = { keywordExcluded?:number;keywordReview?:number;protectedRejections?:number;keywordRestored?:number;token: string; affectedCount: number; preservedManual: number; missingRaw: number; enabledLocations: number; items: PreviewItem[]; geography?:{id:string;status:string;reason:string}[] };
const modes: { value: ResetMode; label: string; description: string }[] = [
  { value: "IGNORED", label: "Automatisch ignorierte Kandidaten neu bewerten", description: "Automatische IGNORE-/REJECT-Entscheidungen anhand aktueller Regeln prüfen." },
  { value: "ALL", label: "Alle nicht übernommenen Kandidaten neu bewerten", description: "Gespeicherte Belege verwenden. Manuelle Prüfentscheidungen erhalten." },
  { value: "RESCAN", label: "Vollständigen Suchlauf neu starten", description: "Zusätzlich alle aktivierten Locations erneut abrufen und neue Funde abgleichen." },
];
const button = "min-h-11 rounded-lg border border-white/20 px-4 py-2 disabled:opacity-50";

export default function DiscoveryResetDialog({ initialMode, onClose, onStarted }: {
  initialMode: ResetMode; onClose: () => void; onStarted: (id: string) => void;
}) {
  const [mode, setMode] = useState(initialMode);
  const [includeManualRejected, setIncludeManualRejected] = useState(false);
  const [confirmed, setConfirmed] = useState(false);
  const [preview, setPreview] = useState<Preview | null>(null);
  const [error, setError] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [operationId, setOperationId] = useState(() => crypto.randomUUID());
  useEffect(() => {
    const controller = new AbortController();
    discoveryApi<Preview>("/reset/preview", "POST", { mode, includeManualRejected }, controller.signal)
      .then(result => { if (!controller.signal.aborted) setPreview(result); })
      .catch(e => { if (!controller.signal.aborted) setError(e instanceof Error ? e.message : "Vorschau fehlgeschlagen"); });
    return () => controller.abort();
  }, [mode, includeManualRejected]);
  function invalidate() { setPreview(null); setError(""); setOperationId(crypto.randomUUID()); }
  async function submit() {
    if (!preview || (includeManualRejected && !confirmed)) return;
    setSubmitting(true); setError("");
    try {
      const result = await discoveryApi<{ operationId: string }>("/reset", "POST", {
        mode, includeManualRejected, previewToken: preview.token, operationId,
        confirmation: confirmed ? "MANUELL ABGELEHNTE ERNEUT PRÜFEN" : "",
      });
      onStarted(result.operationId);
    } catch (e) { setError(e instanceof Error ? e.message : "Neubewertung fehlgeschlagen"); }
    finally { setSubmitting(false); }
  }
  return <DashboardModal open title="Suchlauf zurücksetzen" description="Kandidaten neu prüfen. Kalendertermine, Quellen und Suchhistorie bleiben erhalten." onClose={onClose}>
    <div className="space-y-4">
      <fieldset disabled={submitting} className="space-y-3">
        <legend className="mb-2 font-medium">Umfang auswählen</legend>
        {modes.map(option => <label key={option.value} className="flex gap-3 rounded-lg border border-white/15 p-3">
          <input type="radio" name="reset-mode" value={option.value} checked={mode === option.value} onChange={() => { invalidate(); setMode(option.value); }} />
          <span>{option.label}<span className="block text-sm text-neutral-400">{option.description}</span></span>
        </label>)}
        <label className="flex gap-3"><input type="checkbox" checked={includeManualRejected} onChange={e => { invalidate(); setIncludeManualRejected(e.target.checked); setConfirmed(false); }} />Auch manuell abgelehnte Kandidaten erneut zur Prüfung vorlegen</label>
        {includeManualRejected && <label className="flex gap-3 rounded-lg border border-amber-400/40 p-3"><input type="checkbox" checked={confirmed} onChange={e => setConfirmed(e.target.checked)} />Ich bestätige ausdrücklich: Manuelle Ablehnungen erneut vorlegen. Keine automatische Übernahme dieser Kandidaten.</label>}
      </fieldset>
      {error && <p role="alert" className="text-red-400">{error}</p>}
      {!preview && !error && <p role="status">Vorschau wird geladen …</p>}
      {preview && <section aria-label="Reset-Vorschau" className="space-y-2">
        {!!preview.geography?.length && <div><p>{preview.geography.length} Bestandsfunde aus Sonderquellen werden geografisch eingestuft. Teamentscheidungen und Kalendertermine bleiben dabei erhalten.</p><p>{preview.geography.filter(item=>item.status==="OUTSIDE").length} außerhalb Kölns · {preview.geography.filter(item=>item.status==="UNRESOLVED").length} ungeklärt. Diese Funde gelangen nicht in den fachlichen Prüfbedarf.</p></div>}
        <p className="text-sm">Eindeutig erkannte bekannte Partyreihen können weiterhin nach den bestehenden Freigabe- und Konfliktprüfungen automatisch übernommen werden. Genre-Matches allein bleiben Vorschläge.</p>
        <p>{preview.keywordExcluded ?? 0} durch Schlagwortregeln ausgefiltert · {preview.keywordReview ?? 0} bei bekannten Locations zur Prüfung · {preview.protectedRejections ?? 0} manuelle Ablehnungen geschützt · {preview.keywordRestored ?? 0} vormals automatisch ausgefilterte Kandidaten könnten wieder erscheinen.</p>
        <p>{preview.affectedCount} Kandidaten werden neu bewertet; {preview.preservedManual} manuelle oder nicht eindeutig zuordenbare Altentscheidungen bleiben unverändert.</p>
        {preview.missingRaw > 0 && <p className="text-sm text-amber-300">Bei {preview.missingRaw} Kandidaten fehlen vollständige Rohdaten. Ein vollständiger Suchlauf kann diese ergänzen; fehlende Angaben bleiben ungeklärt.</p>}
        {mode === "RESCAN" && <p>{preview.enabledLocations} aktivierte Locations werden erneut durchsucht. Zusätzliche neue Funde stehen erst nach dem Abruf fest.</p>}
        <details><summary>Betroffene Kandidaten und erwartete Entscheidung</summary>
          <div className="max-h-72 overflow-auto"><table className="w-full text-left text-sm"><thead><tr><th>Party / Location</th><th>Alter Status</th><th>Neuer Status</th><th>Grund</th></tr></thead><tbody>
            {preview.items.map(item => <tr key={item.id}><td className="p-2">{item.title}<br />{item.venue}</td><td>{item.oldStatus}</td><td>{item.newStatus}<br />{item.classification}</td><td>{item.reason}</td></tr>)}
          </tbody></table></div>
          {preview.items.length < preview.affectedCount && <p>Vorschau zeigt die ersten {preview.items.length} von {preview.affectedCount} Kandidaten.</p>}
        </details>
      </section>}
      <div className="flex flex-wrap gap-3">
        <button type="button" className={button} disabled={submitting || !preview || (includeManualRejected && !confirmed)} onClick={() => void submit()}>{submitting ? "Wird angefordert …" : "Neubewertung bestätigen"}</button>
        <button type="button" className={button} disabled={submitting} onClick={onClose}>Abbrechen</button>
      </div>
    </div>
  </DashboardModal>;
}
