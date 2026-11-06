import type { Candidate } from "./discoveryTypes";

export function isOpen(candidate: Candidate) {
  return !candidate.calendar_entry_id && !["IMPORTED", "REJECTED", "DUPLICATE", "IGNORED"].includes(candidate.status);
}

export function canApprovePortfolio(candidate: Candidate) {
  const assessment = candidate.assessment;
  return isOpen(candidate) && !candidate.party_id && candidate.title.length <= 200 &&
    assessment?.recognition.status === "NONE" && assessment.lifecycle === "ACTIVE" &&
    !assessment.importBlockers.length && !!candidate.genreCoverage?.eligible;
}

export function isNewSeries(candidate: Candidate) {
  return canApprovePortfolio(candidate) && candidate.decision_origin === "AUTOMATIC" &&
    !["PREPARED", "SENT"].includes(candidate.inquiry_status) &&
    candidate.assessment?.verification.status === "VERIFIED" && !!candidate.event_date;
}

export function reviewReason(candidate: Candidate) {
  const assessment = candidate.assessment;
  if (["PREPARED", "SENT"].includes(candidate.inquiry_status)) return "Eine Rückfrage ist noch offen. Die Antwort bitte hier ergänzen.";
  if (assessment?.lifecycle && assessment.lifecycle !== "ACTIVE") return "Ein Hinweis auf eine Änderung oder Absage muss geklärt werden.";
  if (assessment?.importBlockers.length) return "Die Übernahme ist gesperrt. Bitte die Belege und Übernahmehindernisse prüfen.";
  if (assessment?.recognition.status === "COOPERATION") return "Kooperation erkannt: Für den Kalender bitte eine der beteiligten Reihen zuordnen.";
  if (assessment?.recognition.status === "AMBIGUOUS") return "Mehrere Partyreihen passen. Die richtige Zuordnung ist noch offen.";
  if (assessment?.verification.status === "CONFLICTING_DATA") return "Die Quellen nennen widersprüchliche Angaben zum Termin.";
  if (assessment?.verification.status === "SPECULATIVE") return "Der Termin ist noch nicht ausreichend durch die Quelle belegt.";
  if (!candidate.event_date || assessment?.verification.status === "MISSING_DATA") return "Für die Übernahme fehlen verlässliche Terminangaben.";
  if (candidate.title.length > 200) return "Der Titel ist zu lang für einen eindeutigen Portfolio-Alias. Bitte die Partyreihe im Portfolio prüfen.";
  if (candidate.decision_origin === "MANUAL") return "Eine manuelle Entscheidung bleibt geschützt. Bitte die Zuordnung oder Rückfrage abschließen.";
  if (!candidate.genreCoverage?.eligible && assessment?.recognition.status === "NONE") return "Die Genre-Übereinstimmung reicht für eine neue Portfolioaufnahme noch nicht aus.";
  return "Die Quelle oder die Zuordnung muss vor der Übernahme bestätigt werden.";
}

export function sourceLink(value: string) {
  try { const url = new URL(value); return url.protocol === "https:" && !url.username && !url.password ? url.href : undefined; }
  catch { return undefined; }
}
export function sourceName(value: string) {
  const href = sourceLink(value);
  return href ? new URL(href).hostname.replace(/^www\./, "") : "Quelle nicht verfügbar";
}
export function displayDate(value: string | null) {
  return value ? new Date(`${value}T12:00:00Z`).toLocaleDateString("de-DE", { day: "2-digit", month: "long", year: "numeric", timeZone: "Europe/Berlin" }) : "Datum noch offen";
}

export function genreMatchLabel(candidate: Candidate) {
  const coverage=candidate.genreCoverage;
  if (!coverage || coverage.total === 0 || coverage.basis === "MISSING") return "Keine Genreangaben";
  return `${coverage.basis === "STANDARD" ? "Standardgenres · " : ""}${coverage.percent} %`;
}
