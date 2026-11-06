import { useState } from "react";
import DataCollection, { type CollectionColumn } from "../DataCollection";
import type { Candidate } from "./discoveryTypes";
import { displayDate, genreMatchLabel, reviewReason, sourceLink, sourceName } from "./discoveryPresentation";

export default function DiscoveryCandidates({ title, description, candidates, kind, loading, unavailable = false, busy, onReview, onReject }: {
  title: string; description: string; candidates: Candidate[]; kind: "new" | "review" | "ready";
  loading: boolean; unavailable?: boolean; busy: boolean; onReview: (candidate: Candidate) => void; onReject?: (candidate: Candidate) => void;
}) {
  const [matchFilter, setMatchFilter] = useState("all");
  const filtered = candidates.filter(candidate => matchFilter === "all" || (candidate.genreCoverage?.percent ?? 0) >= Number(matchFilter));
  const displayedGenres = (candidate: Candidate) => candidate.genreCoverage?.basis === "STANDARD" ? candidate.genreCoverage.standardNames ?? [] : candidate.genreCoverage?.sourceNames ?? candidate.genreCoverage?.names ?? [];
  const actionLabel = kind === "new" ? "Ins Portfolio übernehmen" : kind === "ready" ? "Übernahme öffnen" : "Veranstaltung prüfen";
  const columns: CollectionColumn<Candidate>[] = [
    {key: "title", label: "Partyreihe", sortValue: candidate => candidate.title, render: candidate => <strong>{candidate.title}</strong>},
    {key: "venue", label: "Location", sortValue: candidate => candidate.venue_name, render: candidate => candidate.venue_name},
    {key: "date", label: "Termin", sortValue: candidate => candidate.event_date ?? "9999", render: candidate => displayDate(candidate.event_date)},
    {key: "genres", label: "Genres", render: candidate => <div className="cc-tags" aria-label={candidate.genreCoverage?.basis === "STANDARD" ? "Standardgenres der bekannten Partyreihe" : "Genres aus Veranstaltungsquelle"}>{displayedGenres(candidate).map(name => <span key={name}>{name}</span>)}{!displayedGenres(candidate).length && "Keine Genreangaben"}</div>},
    {key: "match", label: "Genre-Match", sortValue: candidate => candidate.genreCoverage?.percent ?? -1, render: candidate => genreMatchLabel(candidate)},
    {key: "source", label: "Quelle", render: candidate => <a className="cc-source-link" href={sourceLink(candidate.source_url)} target="_blank" rel="noreferrer">{sourceName(candidate.source_url)} ↗</a>},
    {key: "status", label: kind === "review" ? "Klärung" : "Freigabe", render: candidate => kind === "review" ? <div className="cc-review-summary"><p>{reviewReason(candidate)}</p>{candidate.assessment?.recognition.parties.length ? <ul>{candidate.assessment.recognition.parties.map(party => <li key={party.id}>{party.name}</li>)}</ul> : null}</div> : <span className="cc-status">{kind === "new" ? "Neue Reihe · Freigabe offen" : "Bekannte Reihe"}</span>},
  ];
  function card(candidate: Candidate) {return <article className="discovery-card">
      <div className="discovery-card-top"><span className={`discovery-badge ${kind === "new" ? "discovery-badge-accent" : ""}`}>{kind === "new" ? "Neue Partyreihe" : kind === "ready" ? "Bekannte Partyreihe" : "Klärung nötig"}</span>{candidate.genreCoverage && <span className="discovery-match">{genreMatchLabel(candidate)} Genre-Match</span>}</div>
      <h3>{candidate.title}</h3>
      <p className="discovery-location">{candidate.venue_name}</p><p className="discovery-date">{displayDate(candidate.event_date)}</p>
      {kind === "new" && <div className="discovery-genres" aria-label="Erkannte Genres">{displayedGenres(candidate).map(name => <span key={name}>{name}</span>)}</div>}
      {kind === "review" && <><p className="discovery-review-reason">{reviewReason(candidate)}</p>{candidate.assessment?.recognition.parties.length ? <ul className="discovery-recognized">{candidate.assessment.recognition.parties.map(party => <li key={party.id}>{party.name}</li>)}</ul> : null}</>}
      <div className="discovery-card-footer"><a href={sourceLink(candidate.source_url)} target="_blank" rel="noreferrer">{sourceName(candidate.source_url)} ↗</a><div className="cc-inline-actions"><button disabled={busy} className={`discovery-button ${kind === "new" ? "discovery-button-primary" : ""}`} onClick={() => onReview(candidate)}>{actionLabel}</button>{onReject && <button type="button" disabled={busy} className="cc-button cc-button-danger" onClick={()=>onReject(candidate)}>Sofort ablehnen</button>}</div></div>
    </article>;}
  return <section className="discovery-section" aria-label={title}>
    <header className="discovery-section-heading"><div><h2>{title} <span className="discovery-count">{candidates.length}</span></h2><p className="cc-description">{description}</p></div></header>
    {unavailable ? <p className="cc-empty">Ergebnisse derzeit nicht verfügbar. Bitte erneut laden.</p> : loading ? <p role="status" className="cc-empty">Discovery wird geladen …</p> : <DataCollection items={filtered} columns={columns} label={title} getName={candidate => candidate.title} onOpen={onReview} openDisabled={busy} storageKey={`noizy-view-discovery-${kind}`} searchLabel={`${title} durchsuchen`} searchText={candidate => [candidate.title, candidate.venue_name, ...displayedGenres(candidate)].join(" ")} renderCard={card} actions={candidate => <div className="cc-inline-actions"><button disabled={busy} className={`cc-button ${kind === "new" ? "cc-button-primary" : ""}`} onClick={() => onReview(candidate)}>{actionLabel}</button>{onReject && <button type="button" disabled={busy} className="cc-button cc-button-danger" onClick={()=>onReject(candidate)}>Sofort ablehnen</button>}</div>} toolbar={<label className="cc-inline-filter">Genre-Match<select className="cc-input" value={matchFilter} onChange={event => setMatchFilter(event.target.value)}><option value="all">Alle</option><option value="50">Ab 50 %</option><option value="75">Ab 75 %</option><option value="100">100 %</option></select></label>} empty={<><strong>{candidates.length > 0 ? "Keine Treffer für diese Auswahl" : kind === "new" ? "Keine neuen passenden Partyreihen" : kind === "ready" ? "Keine ausstehenden Übernahmen" : "Keine offenen Ausnahmefälle"}</strong><p>{candidates.length > 0 ? "Suche oder Filter anpassen." : "Aktuell gibt es hier nichts zu bearbeiten."}</p></>} />}
  </section>;
}
