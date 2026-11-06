"use client";
import { useEffect, useState } from "react";
import Link from "next/link";
import { calendarEntryHref } from "../kalender/calendarNavigation";
import { discoveryApi } from "./discoveryApi";
import type { Candidate, Overview } from "./discoveryTypes";
import { displayDate } from "./discoveryPresentation";
import { discoveryStatusLabel } from "../discoveryLabels";
import EventChangesPanel from "./EventChangesPanel";

const filters: Record<string, string> = { IMPORTED: "Übernommene Termine", AUTOMATIC_IGNORED: "Ignorierte Konzerte & weitere Ausschlüsse", DUPLICATE: "Dubletten", ARCHIVE: "Archiv", MANUAL_REJECTED: "Manuell abgelehnt", REEVALUATED: "Neu bewertet" };
export default function DiscoveryHistory({ data, onReview, onReviewId }: {
  data: Overview; onReview: (candidate: Candidate) => void; onReviewId: (id: string) => void;
}) {
  const [changesOpen, setChangesOpen] = useState(false);
  const [filter, setFilter] = useState("IMPORTED");
  const [history, setHistory] = useState<Candidate[] | null>(null);
  const [capped, setCapped] = useState(false);
  const [error, setError] = useState("");
  const [limit, setLimit] = useState(20);
  useEffect(() => {
    const controller = new AbortController();
    discoveryApi<Overview>(`?filter=${filter === "DUPLICATE" ? "ARCHIVE" : filter}`, "GET", undefined, controller.signal)
      .then(result => { if (controller.signal.aborted) return; setError(""); setCapped(result.candidates.length >= 500); setHistory(filter === "DUPLICATE" ? result.candidates.filter(candidate => candidate.status === "DUPLICATE") : result.candidates); }).catch(error => { if (!controller.signal.aborted) setError(error instanceof Error ? error.message : "Verlauf konnte nicht geladen werden."); });
    return () => controller.abort();
  }, [filter, data]);
  return <div className="discovery-history-content">
    <div className="discovery-history-filters" aria-label="Verlauf filtern">{Object.entries(filters).map(([value, label]) => <button key={value} className="discovery-button" aria-pressed={filter === value} onClick={() => {if (filter === value) return; setHistory(null); setError(""); setLimit(20); setFilter(value);}}>{label}</button>)}</div>
    {error && <p role="alert">{error}</p>}
    {!history && !error && <p role="status">Verlauf wird geladen …</p>}
    {history?.length === 0 && <p className="discovery-empty">Keine Einträge in diesem Verlauf.</p>}
    <ul className="discovery-history-list">{history?.slice(0, limit).map(candidate => <li key={candidate.id}><div><strong>{candidate.title}</strong><p>{candidate.venue_name} · {displayDate(candidate.event_date)}</p><span>{candidate.status === "IMPORTED" ? candidate.decision_origin === "AUTOMATIC" ? "Automatisch übernommen" : "Manuell übernommen" : candidate.status === "DUPLICATE" ? "Dublette" : candidate.status === "IGNORED" ? "Ignoriert" : candidate.status === "REJECTED" ? "Abgelehnt" : candidate.status === "READY" ? "Bereit zur Übernahme" : candidate.status === "NEEDS_INQUIRY" ? "Rückfrage offen" : "Angaben prüfen"}</span></div><div className="cc-inline-actions">{candidate.calendar_entry_id && <Link className="discovery-button" href={calendarEntryHref(candidate.calendar_entry_id)}>Im Kalender anzeigen</Link>}<button className="discovery-button" onClick={() => onReview(candidate)}>Details öffnen</button></div></li>)}</ul>
    {history && history.length > limit && <button className="discovery-button" onClick={() => setLimit(limit + 20)}>Weitere Einträge anzeigen</button>}
    {capped && <p className="discovery-muted">Die API liefert höchstens die letzten 500 Treffer dieses Filters; Dubletten werden innerhalb des Archivs ausgewählt.</p>}
    <details className="discovery-subsection" onToggle={event => setChangesOpen(event.currentTarget.open)}><summary>Änderungen &amp; Absagen</summary>{changesOpen && <EventChangesPanel onReviewCandidate={onReviewId} />}</details>
    <details className="discovery-subsection"><summary>Abgeschlossene Suchläufe</summary><ul className="discovery-history-list">{data.runs.filter(run => run.status !== "RUNNING").map(run => <li key={run.id}><div><strong>{new Date(run.started_at).toLocaleString("de-DE", {timeZone: "Europe/Berlin"})}</strong><p>{discoveryStatusLabel(run.status)}</p><details><summary>Technische Details</summary><p>{run.report}</p></details></div></li>)}</ul>{!data.runs.some(run => run.status !== "RUNNING") && <p>Noch kein abgeschlossener Suchlauf.</p>}</details>
  </div>;
}
