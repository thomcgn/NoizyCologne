import type { DiscoveryAssessment } from "./discoveryTypes";

const recognitionLabels: Record<DiscoveryAssessment["recognition"]["status"], string> = {SINGLE: "Bekannte Partyreihe erkannt", COOPERATION: "Kooperation erkannt", AMBIGUOUS: "Zuordnung mehrdeutig", NONE: "Keine bekannte Partyreihe erkannt"};
const verificationLabels: Record<DiscoveryAssessment["verification"]["status"], string> = {VERIFIED: "Termin belegt", MISSING_DATA: "Angaben fehlen", CONFLICTING_DATA: "Widersprüchliche Angaben", SPECULATIVE: "Termin nicht ausreichend belegt"};
const lifecycleLabels: Record<DiscoveryAssessment["lifecycle"], string> = {ACTIVE: "Kein Absage- oder Verschiebungshinweis", CANCELLED: "Absage erkannt", CANCELLATION_UNCONFIRMED: "Absagehinweis – prüfen", POSTPONED: "Veranstaltung verschoben", RESCHEDULED: "Veranstaltung auf einen anderen Termin verlegt", LINEUP_CHANGE: "Line-up-Änderung – Veranstaltung separat prüfen"};
export default function CandidateAssessment({ assessment, detailed = false }: { assessment: DiscoveryAssessment; detailed?: boolean }) {
  return <section className="space-y-2 text-sm" aria-label="Erkennung, Terminprüfung und Veranstaltungsstatus">
    <p><strong>Partyreihen-Erkennung:</strong> {recognitionLabels[assessment.recognition.status]}</p>
    {assessment.recognition.parties.length > 0 && <ul className="list-inside list-disc">{assessment.recognition.parties.map(p => <li key={p.id}>{p.name}{detailed && p.matchedName !== p.name ? ` · erkannt über „${p.matchedName}“` : ""}</li>)}</ul>}
    {detailed && assessment.recognition.status === "COOPERATION" && <p>Alle erkannten Reihen bleiben sichtbar. Der Kalender führt pro Termin eine Partyreihe; die Zuordnung muss manuell geprüft werden.</p>}
    {detailed && assessment.recognition.status === "AMBIGUOUS" && <p>Mehrere Zuordnungen passen. Bitte die richtige Partyreihe manuell prüfen.</p>}
    <p><strong>Terminprüfung:</strong> {verificationLabels[assessment.verification.status]}{assessment.verification.date ? ` · ${assessment.verification.date}` : ""}</p>
    {detailed && assessment.verification.reasons.length > 0 && <ul className="list-inside list-disc">{assessment.verification.reasons.map((reason, index) => <li key={`${index}-${reason}`}>{reason}</li>)}</ul>}
    <p><strong>Veranstaltungsstatus:</strong> {lifecycleLabels[assessment.lifecycle]}</p>
    {detailed && <p>Automatische Übernahme: {assessment.automaticImportEligible ? "Voraussetzungen erfüllt." : "derzeit nicht möglich."}</p>}
    {detailed && assessment.importBlockers.length > 0 && <div role="status"><p className="font-semibold">Übernahme gesperrt:</p><ul className="list-inside list-disc">{assessment.importBlockers.map((reason, index) => <li key={`${index}-${reason}`}>{reason}</li>)}</ul><p>Absagen und Verschiebungen unter „Änderungen &amp; Absagen“ prüfen.</p></div>}
  </section>;
}
