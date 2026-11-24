"use client";

import { useEffect, useState, useCallback, useRef, useMemo } from "react";
import { discoveryStatusLabel } from "../discoveryLabels";
import { useCatalog } from "../CatalogProvider";
import SourceOverview from "../SourceOverview";
import DiscoveryResetDialog, { type ResetMode } from "./DiscoveryResetDialog";
import CandidateReviewDialog from "./CandidateReviewDialog";
import SourceEditorDialog from "./SourceEditorDialog";
import DashboardModal from "../DashboardModal";
import ActionFeedback from "../ActionFeedback";
import KeywordRules from "./KeywordRules";
import DiscoveryCandidates from "./DiscoveryCandidates";
import DiscoveryHistory from "./DiscoveryHistory";
import DiscoveryProgress from "./DiscoveryProgress";
import DiscoveryGeography,{type GeographyData} from "./DiscoveryGeography";
import { discoveryApi as api } from "./discoveryApi";
import { useWorkspaceAction } from "../workspaceActions";
import { isNewSeries, isOpen, isUnclear } from "./discoveryPresentation";
import { useDiscoveryPolling } from "./useDiscoveryPolling";
import type { Candidate, Source, Overview } from "./discoveryTypes";

export default function DiscoveryPage() {
  const { reloadEntries, reloadParties, reloadDrafts } = useCatalog();
  const [data, setData] = useState<(Overview & {geography?:GeographyData}) | null>(null);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [candidate, setCandidate] = useState<Candidate | null>(null);
  const [source, setSource] = useState<Source | null>(null);
  const [notice, setNotice] = useState("");
  const [noticePartial, setNoticePartial] = useState(false);
  const [resetMode, setResetMode] = useState<ResetMode | null>(null);
  const [historyOpen, setHistoryOpen] = useState(false);
  const [toolsOpen, setToolsOpen] = useState(false);
  const [pending,setPending]=useState<{ids:Set<string>;items:Map<string,Candidate>}>(()=>({ids:new Set(),items:new Map()}));
  const [confirmReject,setConfirmReject]=useState(false);
  const visibleCandidates=useMemo(()=>data?.candidates.filter(item=>!pending.ids.has(item.id)) ?? [],[data,pending.ids]);
  const actionLock = useRef(false);
  const generation = useRef(0);
  const imported = useRef<number | null>(null);
  const reload = useCallback(async () => {
    const request = ++generation.current;
    try {
      const [overviews, geography] = await Promise.all([Promise.all(["RELEVANT", "UNCLEAR", "READY"].map(filter => api<Overview>(`?filter=${filter}`))),api<GeographyData>("/geography")]);
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
    return () => { clearTimeout(timer); requests.current++; };
  }, [reload]);
  useDiscoveryPolling(() => { void reload(); });
  useEffect(() => {
    if (!data) return;
    if (imported.current !== null && imported.current !== data.automaticallyImported) {
      void Promise.all([reloadEntries(), reloadParties(), reloadDrafts()]).catch(() => setError("Kalender und Portfolio konnten nicht aktualisiert werden. Bitte erneut laden."));
    }
    imported.current = data.automaticallyImported;
  }, [data, reloadEntries, reloadParties, reloadDrafts]);
  async function action(work: () => Promise<void>) {
    if (actionLock.current) return;
    actionLock.current = true; setBusy(true); setError(""); setNotice(""); setNoticePartial(false);
    try { await work(); await reload(); }
    catch (error) { setError(error instanceof Error ? error.message : "Aktion fehlgeschlagen"); }
    finally { actionLock.current = false; setBusy(false); }
  }
  function reject(item: Candidate) {
    setPending(current=>{
      if(current.ids.has(item.id) || current.ids.size>=500) return current;
      const ids=new Set(current.ids);ids.add(item.id);const items=new Map(current.items);items.set(item.id,item);
      return {ids,items};
    });
  }
  function unmark(id:string){setPending(current=>{const ids=new Set(current.ids);ids.delete(id);const items=new Map(current.items);items.delete(id);return {ids,items};});}
  async function rejectBatch(){
    const ids=[...pending.ids];
    await action(async()=>{
      const expectedRevisions=Object.fromEntries([...pending.items].filter(([,item])=>item.updated_at).map(([id,item])=>[id,item.updated_at]));
      const result=await api<{requested:number;rejected:number;alreadyRejected:number;rejectedIds:string[];alreadyRejectedIds:string[];conflicts:{id:string;reason:string}[]}>("/candidates/reject-batch","POST",{candidateIds:ids,expectedRevisions});
      const successful=new Set([...result.rejectedIds,...result.alreadyRejectedIds]);
      setPending(current=>({ids:new Set([...current.ids].filter(id=>!successful.has(id))),items:new Map([...current.items].filter(([id])=>!successful.has(id)))}));
      setConfirmReject(false);
      setNoticePartial(result.conflicts.length > 0);
      setNotice(`${result.rejected} Veranstaltungen abgelehnt; ${result.alreadyRejected} bereits abgelehnt.${result.conflicts.length ? ` ${result.conflicts.length} Konflikte: ${result.conflicts.map(item=>`${pending.items.get(item.id)?.title ?? item.id}: ${item.reason}`).join("; ")}. Vormerkung zurücknehmen und erneut prüfen.` : ""}`);
    });
  }
  function deleteSpecial(item: Source) {void action(async()=>{await api(`/special-sources/${item.id}`,"DELETE");setNotice(`Sonderquelle „${item.name || item.url}“ gelöscht. Vorhandene Termine und Entscheidungen bleiben erhalten.`);});}
  function edit(item: Candidate) { setCandidate(item); setError(""); }
  const currentCandidate = candidate ? data?.candidates.find(item => item.id === candidate.id) ?? candidate : null;
  const activeOperation = data?.operations?.find(item => item.status === "RUNNING");
  const running = !!activeOperation || data?.runs[0]?.status === "RUNNING";
  const resetting = !!activeOperation && !["SCAN", "MONITOR"].includes(activeOperation.kind);
  const runningLabel = resetting ? "Neubewertung läuft …" : "Suche läuft …";
  const newSeries = visibleCandidates.filter(isNewSeries);
  const ready = visibleCandidates.filter(item => isOpen(item) && !isNewSeries(item) && item.assessment?.automaticImportEligible && !["PREPARED", "SENT"].includes(item.inquiry_status) && item.decision_origin !== "MANUAL");
  const readyIds = new Set(ready.map(item => item.id));
  const unclear = visibleCandidates.filter(isUnclear);
  const review = visibleCandidates.filter(item => isOpen(item) && !isUnclear(item) && !isNewSeries(item) && !readyIds.has(item.id));
  const specialSources = data?.sources.filter(item => item.special_source) ?? [];
  const sourceProblems = specialSources.filter(item => item.enabled !== false && (["PARTIAL", "BLOCKED", "FAILED"].includes(item.quality ?? "") || !["EVENTS_FOUND", "NO_EVENTS", "NOT_CHECKED", "SOURCE_LINKS_FOUND", "EXCLUDED_CONCERTS"].includes(item.status)));
  function requestScan() {void action(async () => {await api("/scan", "POST"); setNotice("Suchlauf angefordert. Ergebnisse werden automatisch aktualisiert.");});}
  useWorkspaceAction(running ? runningLabel : "Jetzt suchen", requestScan, busy || running || !data);
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
        <div className="discovery-run-status" role="status"><span className={`discovery-status-dot ${running ? "is-running" : ""}`} /><span>{running ? resetting ? "Neubewertung läuft – Ergebnisse werden aktualisiert" : "Suchlauf läuft – Ergebnisse werden aktualisiert" : lastRun ? discoveryStatusLabel(lastRun.status) : "Noch kein Suchlauf"}</span><span className="discovery-muted">Letzter Suchlauf: {lastRun ? new Date(lastRun.started_at).toLocaleString("de-DE", { timeZone: "Europe/Berlin", dateStyle: "medium", timeStyle: "short" }) : "–"}</span></div>
      </div>
      <div className="discovery-header-actions"><span role="status">{pending.ids.size} vorgemerkt</span><button type="button" className="discovery-button" disabled={busy || pending.ids.size===0} onClick={()=>setConfirmReject(true)}>Ablehnen</button><button className="discovery-button discovery-button-primary cc-page-primary" disabled={busy || running || !data} onClick={requestScan}>{running ? runningLabel : "Jetzt suchen"}</button>
        <details className="discovery-actions"><summary className="discovery-button">Weitere Aktionen</summary><div className="discovery-action-menu"><button disabled={busy || running} onClick={() => setResetMode("ALL")}>Neu bewerten</button><button disabled={busy || running} onClick={() => setResetMode("IGNORED")}>Suchlauf zurücksetzen</button><button onClick={() => {setToolsOpen(true); window.setTimeout(() => document.getElementById("discovery-tools")?.scrollIntoView({ behavior: "smooth" }), 0);}}>Quellen &amp; Konfiguration</button></div></details>
      </div>
    </header>
    {pending.ids.size>0 && <details className="discovery-section"><summary>{pending.ids.size} vorgemerkt · Vormerkung zurücknehmen</summary><p>Nur für diese geöffnete Discovery-Sitzung vorgemerkt; noch nicht dauerhaft abgelehnt. Maximal 500 pro Anfrage.</p><ul className="max-h-64 overflow-y-auto">{[...pending.items.values()].map(item=><li key={item.id} className="flex flex-wrap items-center justify-between gap-2 py-2"><span>{item.title}</span><button type="button" className="discovery-button" disabled={busy} onClick={()=>unmark(item.id)}>Vormerkung zurücknehmen: {item.title}</button></li>)}</ul></details>}
    <DiscoveryProgress operation={activeOperation ?? operation} />
    {error && <div className="discovery-message" role="alert">{error}<button className="discovery-button" disabled={busy} onClick={() => void reload()}>Erneut laden</button></div>}
    {notice && <ActionFeedback kind={noticePartial ? "partial" : "success"} showLabel={false}>{notice}</ActionFeedback>}
    <div className="discovery-metrics" aria-label="Discovery-Kennzahlen">{[["Automatisch übernommen", data?.automaticallyImported], ["Neue Partyreihen", data ? newSeries.length : undefined], ["Prüfbedarf", data ? review.length : undefined]].map(([label, value]) => <div key={label} className="discovery-metric"><span>{label}</span><strong>{value ?? "–"}</strong></div>)}</div>
    {visibleCandidates.length >= 500 && <p className="discovery-source-notice">Die API liefert bis zu 500 Treffer je Bereich. Die Kennzahlen für neue Reihen und Prüfbedarf beziehen sich auf die geladenen Ergebnisse.</p>}
    {sourceProblems.length > 0 && <p className="discovery-source-notice">{sourceProblems.length} {sourceProblems.length === 1 ? "Quelle konnte" : "Quellen konnten"} nicht vollständig ausgewertet werden. Weitere Quellen werden unabhängig verarbeitet. <button onClick={() => {setToolsOpen(true); window.setTimeout(() => document.getElementById("discovery-tools")?.scrollIntoView({ behavior: "smooth" }), 0);}}>Quellen ansehen</button></p>}
    <DiscoveryCandidates title="Relevant · Neue Partyreihen" description="Neue passende Reihen mit belegtem Termin und mindestens 50 % Genre-Match." candidates={newSeries} kind="new" loading={!data && !error} unavailable={!data && !!error} busy={busy} onReview={edit} onReject={reject} />
    <DiscoveryCandidates title="Prüfbedarf" description="Nur offene Fragen zu Terminen, Quellen oder der Zuordnung. Deine manuellen Entscheidungen bleiben geschützt." candidates={review} kind="review" loading={!data && !error} unavailable={!data && !!error} busy={busy} onReview={edit} onReject={reject} />
    <DiscoveryCandidates title="Unklare Angaben" description="Nicht ausreichend klassifizierbar: Genrebelege fehlen oder der Genre-Match liegt unter 50 %. Diese Funde sind nicht abgelehnt." candidates={unclear} kind="review" loading={!data && !error} unavailable={!data && !!error} busy={busy} onReview={edit} onReject={reject} />
    <DiscoveryGeography data={data?.geography} busy={busy} error={error} action={action} onReevaluate={()=>setResetMode("ALL")} />
    <details id="discovery-history" className="discovery-section discovery-history" open={historyOpen} onToggle={event => setHistoryOpen(event.currentTarget.open)}><summary><span>Verlauf</span><span className="discovery-muted">Übernahmen, ignorierte Konzerte, Dubletten und Suchläufe</span></summary>
      {historyOpen && data && <>{ready.length > 0 && <DiscoveryCandidates title="Bereit zur Übernahme" description="Eindeutig erkannte bekannte Reihen. Keine erneute Portfoliofreigabe erforderlich." candidates={ready} kind="ready" loading={false} busy={busy} onReview={edit} onReject={reject} />}<DiscoveryHistory data={data} onChanged={()=>void reload()} onReview={edit} onReviewId={id => void action(async () => edit(await api<Candidate>(`/candidates/${id}`)))} /></>}
    </details>
    <details id="discovery-tools" className="discovery-section discovery-tools" open={toolsOpen} onToggle={event => setToolsOpen(event.currentTarget.open)}><summary>Quellen &amp; Konfiguration <span className="discovery-muted">{specialSources.length} Sonderquellen</span></summary>
      {toolsOpen && <KeywordRules busy={busy} action={action} />}
      <section className="mt-5 space-y-4" aria-label="Sonderquellen"><h2 className="font-semibold">Sonderquellen</h2><p>Veranstaltungsplattformen wie qrushapp.de werden unabhängig von Locations geprüft. Gefundene Termine erhalten keinen Standardort.</p><button type="button" className="discovery-button" disabled={busy} onClick={()=>setSource({id:"",venue_id:null,venue_name:"",name:"",special_source:true,url:"",source_type:"EXTERNAL",source_kind:"PLATFORM",approved:false,status:"NOT_CHECKED",item_selector:"",title_selector:"",date_selector:""})}>Sonderquelle hinzufügen</button>{specialSources.length > 0 ? <SourceOverview sources={specialSources} onEdit={item => {setError("");setSource({...item});}} onDelete={deleteSpecial} busy={busy} /> : <p>Noch keine Sonderquellen hinterlegt.</p>}</section>
      <details className="discovery-subsection"><summary>Technische Diagnosen</summary>{operation && <p>Letzter Vorgang: {discoveryStatusLabel(operation.status)} · {operation.report}</p>}{operation?.summary && <pre className="whitespace-pre-wrap break-words">{operation.summary}</pre>}<div className="overflow-x-auto"><table className="mt-3 w-full text-left text-sm"><thead><tr><th>Party</th><th>Location</th><th>Vorher</th><th>Nachher</th><th>Grund</th></tr></thead><tbody>{data?.diagnoses?.map(item => <tr key={item.id}><td className="p-2">{item.title}</td><td>{item.venue_name}</td><td>{item.old_status ?? "Neu"}</td><td>{item.new_status}</td><td>{item.origin} · {item.reason}</td></tr>)}</tbody></table></div></details>
    </details>
    {confirmReject && <DashboardModal open onClose={()=>{if(!busy)setConfirmReject(false);}} title="Vorgemerkte Veranstaltungen ablehnen" description={`${pending.ids.size} Veranstaltungen werden dauerhaft manuell abgelehnt.`}>
      <ul className="max-h-64 overflow-y-auto break-words">{[...pending.items.values()].map(item=><li key={item.id}>{item.title}</li>)}</ul>
      {error && <p role="alert">{error}</p>}
      <div className="mt-4 flex flex-wrap gap-3"><button type="button" className="discovery-button" disabled={busy} onClick={()=>setConfirmReject(false)}>Abbrechen</button><button type="button" className="discovery-button" disabled={busy || pending.ids.size===0} onClick={()=>void rejectBatch()}>{pending.ids.size} Veranstaltungen ablehnen</button></div>
    </DashboardModal>}
    {resetMode && <DiscoveryResetDialog initialMode={resetMode} onClose={() => setResetMode(null)} onStarted={() => {setResetMode(null); setNotice("Neubewertung angefordert. Der Vorgang wird protokolliert."); void reload();}} />}
    {candidate && <CandidateReviewDialog key={candidate.id} candidate={candidate} currentCandidate={currentCandidate} busy={busy} error={error} action={action} onClose={() => setCandidate(null)} setNotice={setNotice} />}
    {source && <SourceEditorDialog key={source.id} initialSource={source} busy={busy} error={error} action={action} onClose={() => setSource(null)} />}
  </main>;
}
