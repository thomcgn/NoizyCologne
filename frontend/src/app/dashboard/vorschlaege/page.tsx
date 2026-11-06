"use client";

import { useEffect, useState, useCallback } from "react";
import { discoveryStatusLabel } from "../discoveryLabels";
import DashboardModal from "../DashboardModal";
import { useCatalog } from "../CatalogProvider";
import EventChangesPanel from "./EventChangesPanel";
import DiscoveryResetDialog, { type ResetMode } from "./DiscoveryResetDialog";

type Candidate = {id: string; title: string; venue_name: string; instagram: string; source_url: string; event_date: string | null; party_id: string | null; status: string; reason: string; evidence: string; inquiry_status: string; dm_text: string; calendar_entry_id: string | null; decision_origin: string; matching_class: string; automatic_reason: string; matcher_version: string; evaluated_at: string | null; previous_status: string | null; last_evaluation_id: string | null};
type Source = {id: string; venue_id: string; venue_name: string; url: string; source_type: string; approved: boolean; status: string; item_selector: string; title_selector: string; date_selector: string};
type Operation = { id: string; kind: string; status: string; report: string; summary: string };
type Summary = { evaluated: number; newPossibleMatches: number; stillIgnored: number; preservedManual: number; reclassified: number; failed: number };
type Diagnosis = { id: number; title: string; venue_name: string; old_status: string | null; new_status: string; reason: string; origin: string };
type Overview = {metrics: { automaticallyIgnored: number; manuallyRejected: number; needsReview: number; reevaluated: number; unverified: number; possible: number; open: number; archive: number }; operations: Operation[]; diagnoses: Diagnosis[]; automaticallyImported: number; candidates: Candidate[]; sources: Source[]; counts: {status: string; count: number}[]; runs: {id: string; started_at: string; status: string; report: string}[]};
const labels: Record<string,string> = {NEEDS_REVIEW: "Neue passende Partys / Prüfung", NEEDS_INQUIRY: "Rückfragen nötig", IMPORTED: "Übernommen", REJECTED: "Abgelehnt", DUPLICATE: "Dublette", IGNORED: "Ignoriert", READY: "Bereit zur Übernahme", NONE: "Keine Rückfrage", PREPARED: "Rückfrage vorbereitet", SENT: "Rückfrage gesendet (manuell)", ANSWERED: "Antwort erhalten"};
const extraFilters: Record<string,string> = {AUTOMATIC_IGNORED: "Automatisch ignoriert", MANUAL_REJECTED: "Manuell abgelehnt", UNVERIFIED: "Unklar", POSSIBLE_MATCH: "Möglicher Treffer", REEVALUATED: "Neu bewertet"};
function summaryOf(operation?: Operation): Summary | null {
  if (!operation || !["COMPLETED", "PARTIAL"].includes(operation.status)) return null;
  try { return JSON.parse(operation.summary) as Summary; } catch { return null; }
}
const field = "mt-1 w-full rounded-lg border border-white/20 bg-neutral-900 p-3";
const button = "rounded-lg border border-white/20 px-4 py-2 disabled:opacity-50";
async function api(path: string, method = "GET", body?: unknown) {
  const response = await fetch(`/api/v1/discovery${path}`, {method, cache: "no-store", headers: {"Content-Type":"application/json"}, ...(body === undefined ? {} : {body: JSON.stringify(body)})});
  if (!response.ok) { const error = await response.json().catch(() => ({})); throw new Error(error.message || `HTTP ${response.status}`); }
  return response.status === 204 || response.headers.get("content-length") === "0" ? null : response.text().then(text => text ? JSON.parse(text) : null);
}
function sourceLink(url: string) { try { const u = new URL(url); return u.protocol === "https:" && !u.username && !u.password ? u.href : undefined; } catch { return undefined; } }

export default function SuggestionsPage() {
  const {parties, venues, reloadEntries} = useCatalog();
  const [data,setData] = useState<Overview | null>(null);
  const [error,setError] = useState("");
  const [busy,setBusy] = useState(false);
  const [filter,setFilter] = useState("NEEDS_REVIEW");
  const [candidate,setCandidate] = useState<Candidate | null>(null);
  const [source,setSource] = useState<Source | null>(null);
  const [party,setParty] = useState(""); const [date,setDate] = useState("");
  const [status,setStatus] = useState("NEEDS_REVIEW"); const [inquiry,setInquiry] = useState("NONE"); const [dm,setDm] = useState("");
  const [showChanges,setShowChanges] = useState(false);
  const [notice,setNotice] = useState("");
  const [resetMode, setResetMode] = useState<ResetMode | null>(null);
  const [operationId, setOperationId] = useState<string | null>(null);
  const reload = useCallback(async () => { try { setData(await api(`?filter=${encodeURIComponent(filter)}`)); } catch(e) { setError(e instanceof Error ? e.message : "Laden fehlgeschlagen"); } }, [filter]);
  useEffect(() => { const timer = window.setTimeout(() => void reload(),0); const poll = window.setInterval(() => void reload(),10000); return () => { clearTimeout(timer); clearInterval(poll); }; },[reload]);
  async function action(work: () => Promise<void>) { setBusy(true); setError(""); setNotice(""); try { await work(); await reload(); } catch(e) { setError(e instanceof Error ? e.message : "Aktion fehlgeschlagen"); } finally { setBusy(false); } }
  function edit(c: Candidate) { setCandidate(c); setParty(c.party_id ?? ""); setDate(c.event_date ?? ""); setStatus(["NEEDS_REVIEW","NEEDS_INQUIRY","REJECTED","DUPLICATE"].includes(c.status) ? c.status : "NEEDS_REVIEW"); setInquiry(c.inquiry_status); setDm(c.dm_text); setError(""); }
  const operation = operationId ? data?.operations?.find(item => item.id === operationId) : data?.operations?.[0];
  const summary = summaryOf(operation);
  const running = busy || data?.operations?.some(item => item.status === "RUNNING");
  function countFor(value: string) {
    const m = data?.metrics;
    const extra: Record<string, number | undefined> = {AUTOMATIC_IGNORED: m?.automaticallyIgnored, MANUAL_REJECTED: m?.manuallyRejected, UNVERIFIED: m?.unverified, POSSIBLE_MATCH: m?.possible, REEVALUATED: m?.reevaluated, OPEN: m?.open, ARCHIVE: m?.archive};
    return extra[value] ?? data?.counts.find(item => item.status === value)?.count ?? 0;
  }
  function visible(c: Candidate) {
    if (filter === "OPEN") return ["PREPARED", "SENT"].includes(c.inquiry_status);
    if (filter === "ARCHIVE") return ["REJECTED", "DUPLICATE", "IGNORED"].includes(c.status);
    if (filter === "AUTOMATIC_IGNORED") return c.status === "IGNORED" && c.decision_origin === "AUTOMATIC";
    if (filter === "MANUAL_REJECTED") return c.status === "REJECTED" && c.decision_origin === "MANUAL";
    if (filter === "REEVALUATED") return true; // Server filters against the reset audit history.
    if (["UNVERIFIED", "POSSIBLE_MATCH"].includes(filter)) return c.matching_class === filter && !["IMPORTED", "REJECTED", "DUPLICATE", "IGNORED"].includes(c.status);
    return c.status === filter;
  }
  async function save() { if (!candidate) return; await api(`/candidates/${candidate.id}`,"PUT",{partyId: party || null,date: date || null,status,inquiryStatus: inquiry,dmText: dm}); }
  return <main className="min-w-0 space-y-5 p-4 sm:p-7">
    <div className="flex flex-wrap items-center justify-between gap-3"><h1 className="text-2xl font-bold">Vorgeschlagene</h1><div className="flex flex-wrap gap-2"><button className={button} disabled={running} onClick={() => void action(async () => { await api("/scan","POST"); setNotice("Suchlauf angefordert. Ergebnisse werden automatisch aktualisiert."); })}>Jetzt suchen</button><button className={button} disabled={running} onClick={() => setResetMode("ALL")}>Neu bewerten</button><button className={button} disabled={running} onClick={() => setResetMode("IGNORED")}>Suchlauf zurücksetzen</button></div></div>
    <button className={button} aria-expanded={showChanges} onClick={() => setShowChanges(!showChanges)}>Änderungen &amp; Absagen</button>
    {showChanges && <EventChangesPanel onReviewCandidate={id => void action(async () => { edit(await api(`/candidates/${id}`)); })} />}
    <p className="text-sm text-neutral-400">Gefundene Partys prüfen und in den Kalender übernehmen.</p>
    {error && <p role="alert" className="text-red-400">{error}</p>}{notice && <p role="status">{notice}</p>}
    <div className="flex flex-wrap gap-2">{["NEEDS_REVIEW","NEEDS_INQUIRY","OPEN","IMPORTED","ARCHIVE", ...Object.keys(extraFilters)].map(value => <button key={value} aria-pressed={filter === value} className={`${button} ${filter === value ? "bg-white/15" : ""}`} onClick={() => setFilter(value)}>{extraFilters[value] ?? (value === "OPEN" ? "Rückfragen offen" : value === "ARCHIVE" ? "Abgelehnt / Archiv" : labels[value])} ({countFor(value)})</button>)}</div>
    <p className="text-sm">Automatisch ignoriert: {data?.metrics?.automaticallyIgnored ?? 0} · Manuell abgelehnt: {data?.metrics?.manuallyRejected ?? 0} · Prüfbedürftig: {data?.metrics?.needsReview ?? 0} · Neu bewertet: {data?.metrics?.reevaluated ?? 0}</p>
    {operation && <section className="rounded-xl border border-white/15 p-3" aria-live="polite"><p>Letzter Vorgang: {operation.status} · {operation.report}</p>
      {summary && <p>{summary.evaluated} Kandidaten erneut bewertet · {summary.newPossibleMatches} neue mögliche Treffer · {summary.stillIgnored} weiterhin ignoriert · {summary.preservedManual} manuelle/geschützte Entscheidungen unverändert · {summary.reclassified} neu klassifiziert · {summary.failed} Location-Fehler</p>}
    </section>}
    <details className="rounded-xl border border-white/15 p-3"><summary>Matching-Diagnose · letzte {data?.diagnoses?.length ?? 0} Entscheidungen</summary><div className="overflow-x-auto"><table className="mt-3 w-full text-left text-sm"><thead><tr><th>Party</th><th>Location</th><th>Alter Status</th><th>Neuer Status</th><th>Grund</th></tr></thead><tbody>{data?.diagnoses?.map(item => <tr key={item.id}><td className="p-2">{item.title}</td><td>{item.venue_name}</td><td>{item.old_status ?? "Neu"}</td><td>{item.new_status}</td><td>{item.origin} · {item.reason}</td></tr>)}</tbody></table></div></details>
    <p className="text-sm">Automatisch übernommen: {data?.automaticallyImported ?? 0}</p>
    <p className="text-sm">Letzter Suchlauf: {data?.runs[0] ? `${new Date(data.runs[0].started_at).toLocaleString("de-DE",{timeZone:"Europe/Berlin"})} · ${discoveryStatusLabel(data.runs[0].status)} · ${data.runs[0].report}` : "Noch kein Suchlauf"}</p>
    <div className="space-y-3">{data?.candidates.filter(visible).map(c => <article key={c.id} className="rounded-xl border border-white/10 p-4"><div className="flex flex-wrap justify-between gap-3"><div><h2 className="font-semibold">{c.title}</h2><p>{c.venue_name} · {c.event_date ?? "Datum unklar"}</p><p className="text-sm text-neutral-400">{c.reason}</p><p className="text-xs text-neutral-500">{c.decision_origin} · {c.matching_class} · {c.matcher_version}</p></div><button className={button} onClick={() => edit(c)}>Veranstaltung prüfen</button></div></article>)}</div>
    <details className="rounded-xl border border-white/10 p-4"><summary>Location-Quellen · {data?.sources.filter(s => !["EVENTS_FOUND","NO_EVENTS","NOT_CHECKED","SOURCE_LINKS_FOUND","EXCLUDED_CONCERTS"].includes(s.status)).length ?? 0} mit Diagnosebedarf</summary>
      <button className={`${button} mt-3`} onClick={() => setSource({id:"",venue_id:venues[0]?.id ?? "",venue_name:"",url:"",source_type:"EXTERNAL",approved:false,status:"NOT_CHECKED",item_selector:"",title_selector:"",date_selector:""})}>Quelle hinzufügen</button>
      {data?.sources.map(s => <div key={s.id} className="mt-3 break-words border-t border-white/10 pt-3"><p>{s.venue_name} · {s.source_type === "OFFICIAL" ? "Offizielle Website" : "Externe Quelle"} · {discoveryStatusLabel(s.status)} · {s.approved ? "Automatik freigegeben" : "Prüfung erforderlich"}</p><a href={sourceLink(s.url)} target="_blank" rel="noreferrer" className="underline">{s.url}</a> <button className={button} onClick={() => setSource({...s})}>Quelle / Profil bearbeiten</button></div>)}
    </details>
    {resetMode && <DiscoveryResetDialog initialMode={resetMode} onClose={() => setResetMode(null)} onStarted={id => { setOperationId(id); setResetMode(null); setNotice("Neubewertung angefordert. Der Vorgang wird mit Ergebnis und Fehlern protokolliert."); void reload(); }} />}
    <DashboardModal open={!!candidate} onClose={() => setCandidate(null)} title={candidate?.title ?? "Vorschlag prüfen"}>
      {candidate && <div className="space-y-4">
        {error && <p role="alert" className="text-red-400">{error}</p>}
        <p>{candidate.venue_name} · {candidate.reason}</p>
        <p className="text-sm">Entscheidungsursprung: {candidate.decision_origin} · {candidate.matching_class}<br />Regelversion: {candidate.matcher_version} · Bewertet: {candidate.evaluated_at ?? "Noch nicht versioniert"}</p>
        {candidate.automatic_reason && <p className="text-sm">Technische Entscheidung: {candidate.automatic_reason}</p>}
        <a href={sourceLink(candidate.source_url)} target="_blank" rel="noreferrer" className="underline">Quellseite öffnen</a>
        <label className="block">Partyreihe zuordnen<select className={field} value={party} onChange={e => setParty(e.target.value)}><option value="">Bitte wählen</option>{parties.map(p => <option key={p.id} value={p.id}>{p.name}</option>)}</select></label>
        <label className="block">Korrigiertes / bestätigtes Datum<input className={field} type="date" value={date} onChange={e => setDate(e.target.value)} /></label>
        <label className="block">Prüfstatus<select className={field} value={status} onChange={e => setStatus(e.target.value)}>{["NEEDS_REVIEW","NEEDS_INQUIRY","REJECTED","DUPLICATE"].map(s => <option key={s} value={s}>{labels[s]}</option>)}</select></label>
        <details><summary>Quellenangaben</summary><pre className="max-h-48 overflow-auto whitespace-pre-wrap break-words text-sm">{candidate.evidence}</pre></details>
        <details open={inquiry !== "NONE"}><summary>Instagram-Rückfrage</summary>
          <button disabled={busy} className={`${button} mt-3`} onClick={() => void action(async () => { const result = await api(`/candidates/${candidate.id}/dm`); setDm(result.text); setInquiry("PREPARED"); await api(`/candidates/${candidate.id}`,"PUT",{partyId: party || null,date:date || null,status:"NEEDS_INQUIRY",inquiryStatus:"PREPARED",dmText:result.text}); setStatus("NEEDS_INQUIRY"); })}>DM vorbereiten</button>
          <label className="block mt-3">Rückfragetext<textarea className={field} rows={9} maxLength={12000} value={dm} onChange={e => setDm(e.target.value)} /></label>
          <div className="flex flex-wrap gap-2"><button className={button} disabled={!dm} onClick={() => void action(async () => { await navigator.clipboard.writeText(dm); setNotice("Text kopiert"); })}>Text kopieren</button>{candidate.instagram && /^[A-Za-z0-9._]+$/.test(candidate.instagram) && <a className={button} target="_blank" rel="noreferrer" href={`https://www.instagram.com/${candidate.instagram}/`}>Instagram-Profil öffnen</a>}</div>
          {!candidate.instagram && <p className="text-sm mt-2">Kein Instagram-Handle vorhanden. Den Text über einen passenden Kontaktweg verwenden.</p>}
          <label className="block mt-3">Rückfragestatus<select className={field} value={inquiry} onChange={e => setInquiry(e.target.value)}>{["NONE","PREPARED","SENT","ANSWERED"].map(s => <option key={s} value={s}>{labels[s]}</option>)}</select></label>
          <p className="text-sm mt-2">Nachrichten werden von dir in Instagram verschickt. Den Status anschließend manuell setzen.</p>
        </details>
        <div className="flex flex-wrap gap-2"><button disabled={busy} className={button} onClick={() => void action(async () => { await save(); setCandidate(null); })}>Speichern</button><button disabled={busy || !party || !date || !!candidate.calendar_entry_id} className={button} onClick={() => void action(async () => { await save(); await api(`/candidates/${candidate.id}/import`,"POST",{partyId:party,date}); await reloadEntries(); setCandidate(null); })}>Manuell übernehmen</button></div>
        {candidate.calendar_entry_id && <p>Bereits übernommene Änderungen bitte direkt im Kalender prüfen.</p>}
      </div>}
    </DashboardModal>
    <DashboardModal open={!!source} onClose={() => setSource(null)} title="Quelle & Extraktionsprofil">
      {source && <form className="space-y-4" onSubmit={e => {e.preventDefault(); void action(async () => { await api("/sources","POST",{venueId:source.venue_id,url:source.url,approved:source.approved,itemSelector:source.item_selector,titleSelector:source.title_selector,dateSelector:source.date_selector}); setSource(null); });}}>
        {error && <p role="alert" className="text-red-400">{error}</p>}
        <label className="block">Location<select className={field} value={source.venue_id} onChange={e => setSource({...source,venue_id:e.target.value})} required>{venues.map(v => <option key={v.id} value={v.id}>{v.name}</option>)}</select></label>
        <label className="block">Quell-URL<input className={field} type="url" required maxLength={2048} value={source.url} onChange={e => setSource({...source,url:e.target.value})} /></label>
        <label className="flex gap-2"><input type="checkbox" checked={source.approved} onChange={e => setSource({...source,approved:e.target.checked})} />Quelle ausdrücklich zur automatischen Übernahme freigeben</label>
        <p className="text-sm">Externe Portale bleiben als externe Quellen gekennzeichnet. Freigabe erst nach Prüfung der Verlässlichkeit.</p>
        <details><summary>Optionales HTML-Profil (CSS-Selektoren)</summary>{(["item_selector","title_selector","date_selector"] as const).map((key,i) => <label key={key} className="mt-3 block">{["Event-Container","Titel innerhalb des Containers","Startdatum (datetime/content oder ISO-Text)"][i]}<input className={field} maxLength={300} value={source[key]} onChange={e => setSource({...source,[key]:e.target.value})} /></label>)}</details>
        <button disabled={busy} className={button}>Speichern</button>
      </form>}
    </DashboardModal>
  </main>;
}
