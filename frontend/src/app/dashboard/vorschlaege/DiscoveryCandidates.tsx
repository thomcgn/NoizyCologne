import { useState } from "react";
import type { Candidate } from "./discoveryTypes";
import { displayDate, reviewReason, sourceLink, sourceName } from "./discoveryPresentation";

export default function DiscoveryCandidates({ title, description, candidates, kind, loading, unavailable = false, busy, onReview }: {
  title: string; description: string; candidates: Candidate[]; kind: "new" | "review" | "ready";
  loading: boolean; unavailable?: boolean; busy: boolean; onReview: (candidate: Candidate) => void;
}) {
  const [limit, setLimit] = useState(12);
  return <section className="discovery-section" aria-label={title}>
    <header className="discovery-section-heading"><div><h2>{title} <span className="discovery-count">{candidates.length}</span></h2><p>{description}</p></div></header>
    {unavailable ? <p className="discovery-empty">Ergebnisse derzeit nicht verfügbar. Bitte erneut laden.</p> : loading ? <p role="status" className="discovery-empty">Discovery wird geladen …</p> : candidates.length === 0 ? <div className="discovery-empty"><strong>{kind === "new" ? "Alles auf dem aktuellen Stand" : "Keine offenen Ausnahmefälle"}</strong><p>{kind === "new" ? "Sobald eine neue passende Partyreihe gefunden wird, erscheint sie hier." : "Aktuell gibt es hier nichts zu bearbeiten."}</p></div> : <div className="discovery-card-grid">{candidates.slice(0, limit).map(candidate => <article key={candidate.id} className="discovery-card">
      <div className="discovery-card-top"><span className={`discovery-badge ${kind === "new" ? "discovery-badge-accent" : ""}`}>{kind === "new" ? "Neue Partyreihe" : kind === "ready" ? "Bekannte Partyreihe" : "Klärung nötig"}</span>{candidate.genreCoverage && <span className="discovery-match">{candidate.genreCoverage.percent} % Genre-Match</span>}</div>
      <h3>{candidate.title}</h3>
      <p className="discovery-location">{candidate.venue_name}</p><p className="discovery-date">{displayDate(candidate.event_date)}</p>
      {kind === "new" && <div className="discovery-genres" aria-label="Erkannte Genres">{candidate.genreCoverage?.names.map(name => <span key={name}>{name}</span>)}</div>}
      {kind === "review" && <><p className="discovery-review-reason">{reviewReason(candidate)}</p>{candidate.assessment?.recognition.parties.length ? <ul className="discovery-recognized">{candidate.assessment.recognition.parties.map(party => <li key={party.id}>{party.name}</li>)}</ul> : null}</>}
      <div className="discovery-card-footer"><a href={sourceLink(candidate.source_url)} target="_blank" rel="noreferrer">{sourceName(candidate.source_url)} ↗</a><button disabled={busy} className={`discovery-button ${kind === "new" ? "discovery-button-primary" : ""}`} onClick={() => onReview(candidate)}>{kind === "new" ? "Ins Portfolio übernehmen" : kind === "ready" ? "Übernahme öffnen" : "Veranstaltung prüfen"}</button></div>
    </article>)}</div>}
    {candidates.length > limit && <button className="discovery-button mt-4" onClick={() => setLimit(limit + 12)}>Weitere {Math.min(12, candidates.length - limit)} anzeigen</button>}
  </section>;
}
