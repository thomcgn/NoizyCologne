import type { Operation } from "./discoveryTypes";

export default function DiscoveryProgress({ operation }: { operation?: Operation }) {
  if (!operation || !["SCAN", "MONITOR"].includes(operation.kind) || operation.progressPercent == null || operation.totalUnits == null || operation.completedUnits == null) return null;
  const percent = operation.progressPercent;
  const count = `${operation.completedUnits} von ${operation.totalUnits} Quellen/Locations verarbeitet`;
  const failed = operation.failedUnits ?? 0;
  return <section className="mt-4 space-y-2 text-sm" aria-label="Discovery-Fortschritt">
    <div className="flex flex-wrap justify-between gap-2"><strong>{percent} %</strong><span>{count}</span></div>
    <progress className="block h-3 w-full accent-amber-600" role="progressbar" aria-label="Discovery-Suchlauf" aria-valuemin={0} aria-valuemax={100} aria-valuenow={percent} aria-valuetext={`${percent} %, ${count}`} max={100} value={percent} />
    {operation.status === "RUNNING" && <p>{operation.phase === "SPECIAL_SOURCES" ? "Aktuelle Quelle" : operation.phase === "LOCATIONS" ? "Aktuelle Location" : "Vorbereitung"}{operation.currentLabel ? `: ${operation.currentLabel}` : ""}</p>}
    {operation.totalUnits === 0 && operation.status === "COMPLETED" && <p>Keine Quellen oder Locations für diesen Lauf vorgesehen.</p>}
    {failed > 0 && <p>{failed} {failed === 1 ? "Arbeitseinheit mit Fehler" : "Arbeitseinheiten mit Fehlern"}{operation.status === "PARTIAL" ? ". Alle vorgesehenen Einheiten wurden verarbeitet." : "."}</p>}
    {["FAILED", "INTERRUPTED"].includes(operation.status) && <p>Suchlauf {operation.status === "INTERRUPTED" ? "unterbrochen" : "fehlgeschlagen"}. Angezeigt wird der zuletzt gespeicherte Fortschritt.</p>}
    {operation.updatedAt && <p className="discovery-muted">Zuletzt aktualisiert: <time dateTime={operation.updatedAt}>{new Date(operation.updatedAt).toLocaleString("de-DE", { timeZone: "Europe/Berlin", dateStyle: "medium", timeStyle: "medium" })}</time></p>}
  </section>;
}
