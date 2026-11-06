"use client";
import { useState } from "react";
import DashboardModal from "../DashboardModal";
import { useCatalog } from "../CatalogProvider";
import { SourceEvidence } from "../SourceOverview";
import CandidateAssessment from "./CandidateAssessment";
import { discoveryApi as api } from "./discoveryApi";
import { canApprovePortfolio, sourceLink } from "./discoveryPresentation";
import type { Candidate } from "./discoveryTypes";
const field = "mt-1 w-full rounded-lg border border-white/20 bg-neutral-900 p-3";
const button = "discovery-button";
const labels: Record<string,string> = {NEEDS_REVIEW: "Angaben prüfen", NEEDS_INQUIRY: "Rückfragen nötig", IMPORTED: "Übernommen", REJECTED: "Abgelehnt", DUPLICATE: "Dublette", IGNORED: "Ignoriert", READY: "Bereit zur Übernahme", NONE: "Keine Rückfrage", PREPARED: "Rückfrage vorbereitet", SENT: "Rückfrage gesendet (manuell)", ANSWERED: "Antwort erhalten"};
export default function CandidateReviewDialog({ candidate, currentCandidate, busy, error, action, onClose, setNotice }: {
 candidate: Candidate; currentCandidate: Candidate | null; busy: boolean; error: string;
 action: (work: () => Promise<void>) => Promise<void>; onClose: () => void; setNotice: (value: string) => void;
}) {
 const { parties, reloadEntries, reloadParties, reloadDrafts } = useCatalog();
 const [origins,setOrigins] = useState<{url:string;source_kind:string;event_data:string}[]>([]);
 const [portfolioName,setPortfolioName] = useState(candidate.title.slice(0,200));
 const [party,setParty] = useState(candidate.party_id ?? "");
 const [date,setDate] = useState(candidate.event_date ?? "");
 const [status,setStatus] = useState(["NEEDS_REVIEW","NEEDS_INQUIRY","REJECTED","DUPLICATE"].includes(candidate.status) ? candidate.status : "NEEDS_REVIEW");
 const [inquiry,setInquiry] = useState(candidate.inquiry_status);
 const [dm,setDm] = useState(candidate.dm_text);
 function setCandidate(value: Candidate | null) { if (value === null) onClose(); }
 async function save() { await api(`/candidates/${candidate.id}`,"PUT",{partyId: party || null,date: date || null,status,inquiryStatus: inquiry,dmText: dm}); }
 return (
    <DashboardModal variant="drawer" protectChanges open={!!candidate} onClose={() => setCandidate(null)} title={candidate?.title ?? "Discovery prüfen"}>
      {candidate && <div className="space-y-4">
        {error && <p role="alert" className="text-red-400">{error}</p>}
        <p>{candidate.venue_name} · {candidate.reason}</p>
        <details><summary>Technische Bewertung</summary><p className="text-sm">Entscheidungsursprung: {candidate.decision_origin} · {candidate.matching_class}<br />Regelversion: {candidate.matcher_version} · Bewertet: {candidate.evaluated_at ?? "Noch nicht versioniert"}</p>
        {candidate.automatic_reason && <p className="text-sm">Technische Entscheidung: {candidate.automatic_reason}</p>}</details>
        {currentCandidate?.assessment && <CandidateAssessment assessment={currentCandidate.assessment} detailed />}
        {candidate.genreCoverage && <p>Genreübereinstimmung: {candidate.genreCoverage.matched} von {candidate.genreCoverage.total} aktiven Projektgenres ({candidate.genreCoverage.percent} %).</p>}
        {canApprovePortfolio(currentCandidate ?? candidate) && <fieldset className="space-y-2 rounded-lg border p-3"><legend>Neue Partyreihe einmalig freigeben</legend><label className="block">Name im Portfolio<input className={field} value={portfolioName} maxLength={200} onChange={e=>setPortfolioName(e.target.value)} /></label><p>Ab 50 % aller aktiven Projektgenres. Bei belegtem Datum aus freigegebener Quelle wird der Termin mit übernommen. Geprüfte Angaben bleiben erhalten.</p><button type="button" className={`${button} discovery-button-primary`} disabled={busy || !portfolioName.trim()} onClick={()=>void action(async()=>{await api(`/candidates/${candidate.id}/portfolio`,"POST",{name:portfolioName});await reloadEntries();await reloadParties();await reloadDrafts();setCandidate(null);setNotice("Partyreihe im Portfolio freigegeben.");})}>Ins Portfolio übernehmen</button></fieldset>}
        <details><summary>Quellen und Angaben vergleichen</summary><button type="button" className={button} disabled={busy} onClick={()=>void action(async()=>{const rows=await api(`/candidates/${candidate.id}/sources`);setOrigins(Array.isArray(rows)?rows:[]);})}>Quellenbelege laden</button><SourceEvidence origins={origins} /></details>
        <a href={sourceLink(candidate.source_url)} target="_blank" rel="noreferrer" className="underline">Quellseite öffnen</a>
        <label className="block">Partyreihe zuordnen<select className={field} value={party} onChange={e => setParty(e.target.value)}><option value="">Bitte wählen</option>{parties.map(p => <option key={p.id} value={p.id}>{p.name}</option>)}</select></label>
        <label className="block">Korrigiertes / bestätigtes Datum<input className={field} type="date" value={date} onChange={e => setDate(e.target.value)} /></label>
        <label className="block">Prüfstatus<select className={field} value={status} onChange={e => setStatus(e.target.value)}>{["NEEDS_REVIEW","NEEDS_INQUIRY","REJECTED","DUPLICATE"].map(s => <option key={s} value={s}>{labels[s]}</option>)}</select></label>
        <details><summary>Quellenangaben</summary><pre className="max-h-48 overflow-auto whitespace-pre-wrap break-words text-sm">{candidate.evidence}</pre></details>
        <details open={inquiry !== "NONE"}><summary>Instagram-Rückfrage</summary>
          <button disabled={busy} className={`${button} mt-3`} onClick={() => void action(async () => { const result = await api<{text: string}>(`/candidates/${candidate.id}/dm`); setDm(result.text); setInquiry("PREPARED"); await api(`/candidates/${candidate.id}`,"PUT",{partyId: party || null,date:date || null,status:"NEEDS_INQUIRY",inquiryStatus:"PREPARED",dmText:result.text}); setStatus("NEEDS_INQUIRY"); })}>DM vorbereiten</button>
          <label className="block mt-3">Rückfragetext<textarea className={field} rows={9} maxLength={12000} value={dm} onChange={e => setDm(e.target.value)} /></label>
          <div className="flex flex-wrap gap-2"><button className={button} disabled={!dm} onClick={() => void action(async () => { await navigator.clipboard.writeText(dm); setNotice("Text kopiert"); })}>Text kopieren</button>{candidate.instagram && /^[A-Za-z0-9._]+$/.test(candidate.instagram) && <a className={button} target="_blank" rel="noreferrer" href={`https://www.instagram.com/${candidate.instagram}/`}>Instagram-Profil öffnen</a>}</div>
          {!candidate.instagram && <p className="text-sm mt-2">Kein Instagram-Handle vorhanden. Den Text über einen passenden Kontaktweg verwenden.</p>}
          <label className="block mt-3">Rückfragestatus<select className={field} value={inquiry} onChange={e => setInquiry(e.target.value)}>{["NONE","PREPARED","SENT","ANSWERED"].map(s => <option key={s} value={s}>{labels[s]}</option>)}</select></label>
          <p className="text-sm mt-2">Nachrichten werden von dir in Instagram verschickt. Den Status anschließend manuell setzen.</p>
        </details>
        <div className="flex flex-wrap gap-2"><button disabled={busy} className={button} onClick={() => void action(async () => { await save(); setCandidate(null); })}>Speichern</button><button disabled={busy || !party || !date || !!currentCandidate?.calendar_entry_id || !!currentCandidate?.assessment?.importBlockers.length} className={button} onClick={() => void action(async () => { await save(); await api(`/candidates/${candidate.id}/import`,"POST",{partyId:party,date}); await reloadEntries(); await reloadDrafts(); setCandidate(null); })}>Manuell übernehmen</button></div>
        {currentCandidate?.calendar_entry_id && <p>Bereits übernommene Änderungen bitte direkt im Kalender prüfen.</p>}
      </div>}
    </DashboardModal>
 );
}
