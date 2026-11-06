"use client";
import { useState } from "react";
import Link from "next/link";
import { calendarEntryHref } from "../kalender/calendarNavigation";
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
 const { parties, venues, reloadEntries, reloadParties, reloadDrafts } = useCatalog();
 const [importedId,setImportedId] = useState(candidate.calendar_entry_id);
 const [origins,setOrigins] = useState<{url:string;source_kind:string;event_data:string}[]>([]);
 const [portfolioName,setPortfolioName] = useState(candidate.title.slice(0,200));
 const [venueId,setVenueId] = useState(candidate.venue_id ?? "");
 const needsVenue = candidate.special_source === true && !candidate.venue_id;
 const [party,setParty] = useState(candidate.party_id ?? "");
 const [date,setDate] = useState(candidate.event_date ?? "");
 const [status,setStatus] = useState(["NEEDS_REVIEW","NEEDS_INQUIRY","REJECTED","DUPLICATE"].includes(candidate.status) ? candidate.status : "NEEDS_REVIEW");
 const [inquiry,setInquiry] = useState(candidate.inquiry_status);
 const [dm,setDm] = useState(candidate.dm_text);
 const [rememberName,setRememberName] = useState(false);
 const [seriesAlias,setSeriesAlias] = useState(candidate.title.slice(0,200));
 const selectedStandards=parties.find(item => item.id === party)?.standardGenres ?? currentCandidate?.genreCoverage?.standardNames ?? [];
 function setCandidate(value: Candidate | null) { if (value === null) onClose(); }
 async function save() { await api(`/candidates/${candidate.id}`,"PUT",{partyId: party || null,date: date || null,status,inquiryStatus: inquiry,dmText: dm,...(needsVenue ? {venueId:venueId || null} : {})}); }
 return (
    <DashboardModal variant="drawer" protectChanges open={!!candidate} onClose={() => setCandidate(null)} title={candidate?.title ?? "Discovery prüfen"}>
      {candidate && <div className="space-y-4">
        {error && <p role="alert" className="text-red-400">{error}</p>}
        <p>{candidate.venue_name} · {candidate.reason}</p>
        <details><summary>Technische Bewertung</summary><p className="text-sm">Entscheidungsursprung: {candidate.decision_origin} · {candidate.matching_class}<br />Regelversion: {candidate.matcher_version} · Bewertet: {candidate.evaluated_at ?? "Noch nicht versioniert"}</p>
        {candidate.automatic_reason && <p className="text-sm">Technische Entscheidung: {candidate.automatic_reason}</p>}</details>
        {currentCandidate?.assessment && <CandidateAssessment assessment={currentCandidate.assessment} detailed />}
        {currentCandidate?.genreCoverage && <section aria-label="Genregrundlage" className="space-y-2 text-sm"><p><strong>Genres aus Veranstaltungsquelle:</strong> {(currentCandidate.genreCoverage.sourceNames ?? currentCandidate.genreCoverage.names).join(", ") || "Keine Eventgenres belegt."}</p>{((currentCandidate.assessment && currentCandidate.assessment.recognition.status !== "NONE") || party) && <p><strong>Standardgenres der bekannten Partyreihe:</strong> {selectedStandards.join(", ") || "Keine hinterlegt."}</p>}{currentCandidate.genreCoverage.basis === "STANDARD" ? <p>Genregrundlage: hinterlegte Standardgenres. Fehlende Eventgenres ändern die bekannte Zuordnung nicht.</p> : currentCandidate.genreCoverage.total > 0 ? <p>Genreübereinstimmung: {currentCandidate.genreCoverage.matched} von {currentCandidate.genreCoverage.total} erkannten Eventgenres passen zum aktiven Portfolio ({currentCandidate.genreCoverage.percent} %).</p> : <p>Keine Eventgenres für eine Prozentberechnung belegt.</p>}</section>}
        {canApprovePortfolio(currentCandidate ?? candidate) && <fieldset className="space-y-2 rounded-lg border p-3"><legend>Neue Partyreihe einmalig freigeben</legend><label className="block">Name im Portfolio<input className={field} value={portfolioName} maxLength={200} onChange={e=>setPortfolioName(e.target.value)} /></label><p>Ab 50 % der erkannten Eventgenres passend zum aktiven Portfolio. Bei belegtem Datum aus freigegebener Quelle wird der Termin mit übernommen. Geprüfte Angaben bleiben erhalten.</p><button type="button" className={`${button} discovery-button-primary`} disabled={busy || !portfolioName.trim()} onClick={()=>void action(async()=>{await api(`/candidates/${candidate.id}/portfolio`,"POST",{name:portfolioName,seriesAlias:rememberName ? seriesAlias.trim() : null});await reloadEntries();await reloadParties();await reloadDrafts();setCandidate(null);setNotice("Partyreihe im Portfolio freigegeben.");})}>Ins Portfolio übernehmen</button></fieldset>}
        <details><summary>Quellen und Angaben vergleichen</summary><button type="button" className={button} disabled={busy} onClick={()=>void action(async()=>{const rows=await api(`/candidates/${candidate.id}/sources`);setOrigins(Array.isArray(rows)?rows:[]);})}>Quellenbelege laden</button><SourceEvidence origins={origins} /></details>
        <a href={sourceLink(candidate.source_url)} target="_blank" rel="noreferrer" className="underline">Quellseite öffnen</a>
        {needsVenue && <label className="block">Veranstaltungsort zuordnen<p className="text-sm">Angabe aus der Quelle: {candidate.reported_location || "Kein eindeutiger Veranstaltungsort angegeben"}. Die Plattform ist keine Location.</p><select className={field} value={venueId} onChange={e=>setVenueId(e.target.value)}><option value="">Bitte ausdrücklich wählen</option>{venues.map(venue=><option key={venue.id} value={venue.id}>{venue.name}</option>)}</select></label>}
        <label className="block">Partyreihe zuordnen<select className={field} value={party} onChange={e => setParty(e.target.value)}><option value="">Bitte wählen</option>{parties.map(p => <option key={p.id} value={p.id}>{p.name}</option>)}</select></label>
        <label className="block">Korrigiertes / bestätigtes Datum<input className={field} type="date" value={date} onChange={e => setDate(e.target.value)} /></label>
        <fieldset className="space-y-2 rounded-lg border p-3"><legend>Erkennung künftiger Ausgaben</legend><p>Die Zuordnung gilt für diese Partyreihe. Bekannte Namen und vorhandene Aliase werden wiederverwendet; andere Veranstaltungen der Location bleiben prüfpflichtig.</p><label className="flex items-start gap-2"><input type="checkbox" checked={rememberName} onChange={e => setRememberName(e.target.checked)} />Zusätzlich eine wiederverwendbare Namensvariante bestätigen</label>{rememberName && <label className="block">Wiederverwendbarer Reihenname<input className={field} value={seriesAlias} maxLength={200} onChange={e => setSeriesAlias(e.target.value)} /><span className="text-sm">Nur den belegten Reihennamen eingeben. Einmalige Specials, Daten und Line-ups weglassen. Speicherung erfolgt zusammen mit der erfolgreichen Übernahme.</span></label>}</fieldset>
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
        <div className="flex flex-wrap gap-2"><button disabled={busy || !!importedId} className={button} onClick={() => void action(async () => { await save(); setCandidate(null); setNotice("Bearbeitungsstand gespeichert. Kein Kalenderimport."); })}>Speichern</button><button disabled={busy || !!importedId || !party || !date || (needsVenue && !venueId) || ["REJECTED","DUPLICATE"].includes(status) || (rememberName && !seriesAlias.trim()) || !!currentCandidate?.calendar_entry_id || !!currentCandidate?.assessment?.importBlockers.length} className={`${button} discovery-button-primary`} onClick={() => void action(async () => { const result=await api<{calendarEntryId:string}>(`/candidates/${candidate.id}/confirm`,"POST",{partyId:party,date,inquiryStatus:inquiry,dmText:dm,seriesAlias:rememberName ? seriesAlias.trim() : null,...(needsVenue ? {venueId:venueId || null} : {})}); if (!result?.calendarEntryId) throw new Error("Kalenderimport wurde nicht bestätigt. Bitte den Vorschlag erneut prüfen."); await Promise.all([reloadEntries(),reloadDrafts(),reloadParties()]); setImportedId(result.calendarEntryId); setNotice("Termin bestätigt und in den Kalender übernommen. Er steht jetzt in der Redaktion bereit."); })}>Bestätigen &amp; übernehmen</button></div>
        {importedId && <p><Link className={button} href={calendarEntryHref(importedId)}>Im Kalender anzeigen</Link></p>}
      </div>}
    </DashboardModal>
 );
}
