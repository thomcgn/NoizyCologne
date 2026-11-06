"use client";

import { useEffect, useState, useCallback, useRef } from "react";
import { discoveryStatusLabel } from "../discoveryLabels";
import { useCatalog } from "../CatalogProvider";
import SourceOverview from "../SourceOverview";
import DiscoveryResetDialog, { type ResetMode } from "./DiscoveryResetDialog";
import CandidateReviewDialog from "./CandidateReviewDialog";
import SourceEditorDialog from "./SourceEditorDialog";
import DiscoveryCandidates from "./DiscoveryCandidates";
import DiscoveryHistory from "./DiscoveryHistory";
import DiscoveryGeography,{type GeographyData} from "./DiscoveryGeography";
import { discoveryApi as api } from "./discoveryApi";
import { useWorkspaceAction } from "../workspaceActions";
import { isNewSeries, isOpen } from "./discoveryPresentation";
import type { Candidate, Source, Overview } from "./discoveryTypes";

export default function DiscoveryPage() {
  const { reloadEntries, reloadParties, reloadDrafts } = useCatalog();
  const [data, setData] = useState<(Overview & {geography?:GeographyData}) | null>(null);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [candidate, setCandidate] = useState<Candidate | null>(null);
  const [source, setSource] = useState<Source | null>(null);
  const [notice, setNotice] = useState("");
  const [resetMode, setResetMode] = useState<ResetMode | null>(null);
  const [historyOpen, setHistoryOpen] = useState(false);
  const [toolsOpen, setToolsOpen] = useState(false);
  const actionLock = useRef(false);
  const generation = useRef(0);
  const imported = useRef<number | null>(null);
  const reload = useCallback(async () => {
    const request = ++generation.current;
    try {
      const [overviews, geography] = await Promise.all([Promise.all(["NEEDS_REVIEW", "NEEDS_INQUIRY", "READY"].map(filter => api<Overview>(`?filter=${filter}`))),api<GeographyData>("/geography")]);
      const [review,inquiry,ready]=overviews;
      if (request !== generation.current) return;
      const candidates = [...new Map([...review.candidates, ...inquiry.candidates, ...ready.candidates].map(item => [item.id, item])).values()];
      setData({ ...review, candidates, geography });
      setError("");
    } catch (error) { if (request === generation.current) setError(error instanceof Error ? error.message : "Discovery konnte nicht geladen werden."); }
  }, []);
  useEffect(() => {
    const requests = generation;
    const timer = window.setTimeout(() => void reload(), 0);
    const poll = window.setInterval(() => void reload(), 10000);
    return () => { clearTimeout(timer); clearInterval(poll); requests.current++; };
  }, [reload]);
  useEffect(() => {
    if (!data) return;
    if (imported.current !== null && imported.current !== data.automaticallyImported) {
      void Promise.all([reloadEntries(), reloadParties(), reloadDrafts()]).catch(() => setError("Kalender und Portfolio konnten nicht aktualisiert werden. Bitte erneut laden."));
    }
    imported.current = data.automaticallyImported;
  }, [data, reloadEntries, reloadParties, reloadDrafts]);
  async function action(work: () => Promise<void>) {
    if (actionLock.current) return;
    actionLock.current = true; setBusy(true); setError(""); setNotice("");
    try { await work(); await reload(); }
    catch (error) { setError(error instanceof Error ? error.message : "Aktion fehlgeschlagen"); }
    finally { actionLock.current = false; setBusy(false); }
  }
  function reject(item: Candidate) {void action(async()=>{await api(`/candidates/${item.id}/reject`,"POST");setNotice(`Abgelehnt: „${item.title}“. Die manuelle Entscheidung bleibt bei späteren Suchläufen erhalten.`);});}
  function deleteSpecial(item: Source) {void action(async()=>{await api(`/special-sources/${item.id}`,"DELETE");setNotice(`Sonderquelle „${item.name || item.url}“ gelöscht. Vorhandene Termine und Entscheidungen bleiben erhalten.`);});}
  function edit(item: Candidate) { setCandidate(item); setError(""); }
  const currentCandidate = candidate ? data?.candidates.find(item => item.id === candidate.id) ?? candidate : null;
  const running = !!data?.operations?.some(item => item.status === "RUNNING") || data?.runs[0]?.status === "RUNNING";
  const newSeries = data?.candidates.filter(isNewSeries) ?? [];
  const ready = data?.candidates.filter(item => isOpen(item) && !isNewSeries(item) && item.assessment?.automaticImportEligible && !["PREPARED", "SENT"].includes(item.inquiry_status) && item.decision_origin !== "MANUAL") ?? [];
  const readyIds = new Set(ready.map(item => item.id));
  const review = data?.candidates.filter(item => isOpen(item) && !isNewSeries(item) && !readyIds.has(item.id)) ?? [];
  const specialSources = data?.sources.filter(item => item.special_source) ?? [];
  const sourceProblems = specialSources.filter(item => item.enabled !== false && (["PARTIAL", "BLOCKED", "FAILED"].includes(item.quality ?? "") || !["EVENTS_FOUND", "NO_EVENTS", "NOT_CHECKED", "SOURCE_LINKS_FOUND", "EXCLUDED_CONCERTS"].includes(item.status)));
  function requestScan() {void action(async () => {await api("/scan", "POST"); setNotice("Suchlauf angefordert. Ergebnisse werden automatisch aktualisiert.");});}
  useWorkspaceAction(running ? "Suche läuft …" : "Jetzt suchen", requestScan, busy || running || !data);
  useEffect(() => {
    function openSection() {
      const hash = window.location.hash;
      if (hash === "#discovery-tools") setToolsOpen(true);
      if (hash === "#discovery-history") setHistoryOpen(true);
    }
    const timer = window.setTimeout(openSection, 0);
    window.addEventListener("hashchange", openSection);
    return () => {clearTimeout(timer); window.removeEventListener("hashchange", openSection);};
  }, []);
  const lastRun = data?.runs[0];
  const operation = data?.operations?.[0];
  return <main className="discovery-page">
    <header className="discovery-header">
      <div><p className="discovery-eyebrow">Arbeitsbereich / Entdecken</p><h1>Discovery</h1><p className="discovery-muted">Passende Partys entdecken. Neue Reihen ins Portfolio aufnehmen.</p>
        <div className="discovery-run-status" role="status"><span className={`discovery-status-dot ${running ? "is-running" : ""}`} /><span>{running ? "Suchlauf läuft – Ergebnisse werden aktualisiert" : lastRun ? discoveryStatusLabel(lastRun.status) : "Noch kein Suchlauf"}</span><span className="discovery-muted">Letzter Suchlauf: {lastRun ? new Date(lastRun.started_at).toLocaleString("de-DE", { timeZone: "Europe/Berlin", dateStyle: "medium", timeStyle: "short" }) : "–"}</span></div>
      </div>
      <div className="discovery-header-actions"><button className="discovery-button discovery-button-primary cc-page-primary" disabled={busy || running || !data} onClick={requestScan}>{running ? "Suche läuft …" : "Jetzt suchen"}</button>
        <details className="discovery-actions"><summary className="discovery-button">Weitere Aktionen</summary><div className="discovery-action-menu"><button disabled={busy || running} onClick={() => setResetMode("ALL")}>Neu bewerten</button><button disabled={busy || running} onClick={() => setResetMode("IGNORED")}>Suchlauf zurücksetzen</button><button onClick={() => {setToolsOpen(true); window.setTimeout(() => document.getElementById("discovery-tools")?.scrollIntoView({ behavior: "smooth" }), 0);}}>Quellen &amp; Konfiguration</button></div></details>
      </div>
    </header>
    {error && <div className="discovery-message" role="alert">{error}<button className="discovery-button" disabled={busy} onClick={() => void reload()}>Erneut laden</button></div>}
    {notice && <p className="discovery-message" role="status">{notice}</p>}
    <div className="discovery-metrics" aria-label="Discovery-Kennzahlen">{[["Automatisch übernommen", data?.automaticallyImported], ["Neue Partyreihen", data ? newSeries.length : undefined], ["Prüfbedarf", data ? review.length : undefined]].map(([label, value]) => <div key={label} className="discovery-metric"><span>{label}</span><strong>{value ?? "–"}</strong></div>)}</div>
    {data?.counts.some(item => ["NEEDS_REVIEW", "NEEDS_INQUIRY", "READY"].includes(item.status) && item.count >= 500) && <p className="discovery-source-notice">Die API liefert bis zu 500 offene Kandidaten je Status. Die Kennzahlen für neue Reihen und Prüfbedarf beziehen sich auf die geladenen Ergebnisse.</p>}
    {sourceProblems.length > 0 && <p className="discovery-source-notice">{sourceProblems.length} {sourceProblems.length === 1 ? "Quelle konnte" : "Quellen konnten"} nicht vollständig ausgewertet werden. Weitere Quellen werden unabhängig verarbeitet. <button onClick={() => {setToolsOpen(true); window.setTimeout(() => document.getElementById("discovery-tools")?.scrollIntoView({ behavior: "smooth" }), 0);}}>Quellen ansehen</button></p>}
    <DiscoveryCandidates title="Neue Partyreihen" description="Neue passende Reihen mit belegtem Termin und mindestens 50 % Genre-Match." candidates={newSeries} kind="new" loading={!data && !error} unavailable={!data && !!error} busy={busy} onReview={edit} onReject={reject} />
    <DiscoveryCandidates title="Prüfbedarf" description="Nur offene Fragen zu Terminen, Quellen oder der Zuordnung. Deine manuellen Entscheidungen bleiben geschützt." candidates={review} kind="review" loading={!data && !error} unavailable={!data && !!error} busy={busy} onReview={edit} onReject={reject} />
    <DiscoveryGeography data={data?.geography} busy={busy} error={error} action={action} onReevaluate={()=>setResetMode("ALL")} />
    <details id="discovery-history" className="discovery-section discovery-history" open={historyOpen} onToggle={event => setHistoryOpen(event.currentTarget.open)}><summary><span>Verlauf</span><span className="discovery-muted">Übernahmen, ignorierte Konzerte, Dubletten und Suchläufe</span></summary>
      {historyOpen && data && <>{ready.length > 0 && <DiscoveryCandidates title="Bereit zur Übernahme" description="Eindeutig erkannte bekannte Reihen. Keine erneute Portfoliofreigabe erforderlich." candidates={ready} kind="ready" loading={false} busy={busy} onReview={edit} onReject={reject} />}<DiscoveryHistory data={data} onReview={edit} onReviewId={id => void action(async () => edit(await api<Candidate>(`/candidates/${id}`)))} /></>}
    </details>
    <details id="discovery-tools" className="discovery-section discovery-tools" open={toolsOpen} onToggle={event => setToolsOpen(event.currentTarget.open)}><summary>Quellen &amp; Konfiguration <span className="discovery-muted">{specialSources.length} Sonderquellen</span></summary>
      <section className="mt-5 space-y-4" aria-label="Sonderquellen"><h2 className="font-semibold">Sonderquellen</h2><p>Veranstaltungsplattformen wie qrushapp.de werden unabhängig von Locations geprüft. Gefundene Termine erhalten keinen Standardort.</p><button type="button" className="discovery-button" disabled={busy} onClick={()=>setSource({id:"",venue_id:null,venue_name:"",name:"",special_source:true,url:"",source_type:"EXTERNAL",source_kind:"PLATFORM",approved:false,status:"NOT_CHECKED",item_selector:"",title_selector:"",date_selector:""})}>Sonderquelle hinzufügen</button>{specialSources.length > 0 ? <SourceOverview sources={specialSources} onEdit={item => {setError("");setSource({...item});}} onDelete={deleteSpecial} busy={busy} /> : <p>Noch keine Sonderquellen hinterlegt.</p>}</section>
      <details className="discovery-subsection"><summary>Technische Diagnosen</summary>{operation && <p>Letzter Vorgang: {discoveryStatusLabel(operation.status)} · {operation.report}</p>}{operation?.summary && <pre className="whitespace-pre-wrap break-words">{operation.summary}</pre>}<div className="overflow-x-auto"><table className="mt-3 w-full text-left text-sm"><thead><tr><th>Party</th><th>Location</th><th>Vorher</th><th>Nachher</th><th>Grund</th></tr></thead><tbody>{data?.diagnoses?.map(item => <tr key={item.id}><td className="p-2">{item.title}</td><td>{item.venue_name}</td><td>{item.old_status ?? "Neu"}</td><td>{item.new_status}</td><td>{item.origin} · {item.reason}</td></tr>)}</tbody></table></div></details>
    </details>
    {resetMode && <DiscoveryResetDialog initialMode={resetMode} onClose={() => setResetMode(null)} onStarted={() => {setResetMode(null); setNotice("Neubewertung angefordert. Der Vorgang wird protokolliert."); void reload();}} />}
    {candidate && <CandidateReviewDialog key={candidate.id} candidate={candidate} currentCandidate={currentCandidate} busy={busy} error={error} action={action} onClose={() => setCandidate(null)} setNotice={setNotice} />}
    {source && <SourceEditorDialog key={source.id} initialSource={source} busy={busy} error={error} action={action} onClose={() => setSource(null)} />}
  </main>;
}
