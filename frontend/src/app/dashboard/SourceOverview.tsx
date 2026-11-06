"use client";

import { discoveryStatusLabel } from "./discoveryLabels";

export type DiscoverySource = {
  id: string; venue_id: string | null; name?: string; special_source?: boolean; venue_name: string; url: string; source_type: string; approved: boolean; status: string;
  item_selector: string; title_selector: string; date_selector: string; source_kind?: string; enabled?: boolean; priority?: number;
  quality?: string; event_count?: number | null; fallback_used?: boolean; fetch_method?: string; scan_complete?: boolean;
  fetch_diagnostics?: string; last_fetched_at?: string | null; last_success_at?: string | null;
};
const qualityLabels: Record<string,string> = {SUCCESS:"Veranstaltungen erfolgreich ausgewertet",PARTIAL:"Nur teilweise ausgewertet",BLOCKED:"Abruf gesperrt",FAILED:"Auswertung fehlgeschlagen",NO_EVENTS_CONFIRMED:"Ausdrücklich keine Veranstaltungen gemeldet",NOT_CHECKED:"Noch nicht geprüft"};
export const sourceKinds: Record<string,string> = {WEBSITE:"Location-Website",OFFICIAL_ORGANIZER:"Offizieller Veranstalter",OFFICIAL_TICKETS:"Offizielle Ticketseite",PLATFORM:"Veranstaltungsplattform",CUSTOM:"Weitere konfigurierte Quelle"};
function timestamp(value?: string | null) { return value ? new Date(value).toLocaleString("de-DE") : "Noch nicht nachgewiesen"; }
function link(value: string) { try { const url=new URL(value); return url.protocol==="https:" && !url.username && !url.password ? url.href : undefined; } catch { return undefined; } }
export default function SourceOverview({sources,onEdit,onDelete,busy=false}:{sources:DiscoverySource[];onEdit?:(source:DiscoverySource)=>void;onDelete?:(source:DiscoverySource)=>void;busy?:boolean}) {
  return <div className="space-y-3">{sources.map(source => <article id={`location-source-${source.id}`} key={source.id} className="rounded-lg border border-white/15 p-3 text-sm">
    <p className="font-semibold">{source.special_source ? `${source.name || source.url} · Sonderquelle` : source.venue_name} · {sourceKinds[source.source_kind ?? ""] ?? (source.source_type==="OFFICIAL" ? "Location-Website" : "Externe Quelle")}</p>
    <p>{source.enabled===false ? "Quelle deaktiviert" : qualityLabels[source.quality ?? ""] ?? discoveryStatusLabel(source.status)} · {source.event_count==null ? "Veranstaltungszahl noch nicht ermittelt" : `${source.event_count} Veranstaltungen erkannt`}</p>
    <p>Letzter Abruf: {timestamp(source.last_fetched_at)}<br />Letzter erfolgreicher Abruf: {timestamp(source.last_success_at)}</p>
    <p>{source.approved ? "Automatische Übernahme freigegeben" : "Manuelle Prüfung erforderlich"}{source.fallback_used ? " · Als Fallback verwendet" : ""}</p>
    <a href={link(source.url)} target="_blank" rel="noreferrer" className="underline">Quelle öffnen</a>
    <details className="mt-2"><summary>Technische Details</summary><p className="break-all">{source.url}</p><p>Abruf: {source.fetch_method==="BROWSER" ? "Browser" : "HTTP"} · {discoveryStatusLabel(source.status)}</p><p>{source.scan_complete ? "Vollständige Liste bestätigt" : "Vollständigkeit nicht bestätigt"}</p>{source.fetch_diagnostics && <p>{source.fetch_diagnostics.split("; ").map(discoveryStatusLabel).join("; ")}</p>}</details>
    {(onDelete || onEdit) && <div className="mt-2 flex flex-wrap gap-2">
      {onEdit && <button type="button" disabled={busy} className="cc-button" onClick={()=>onEdit(source)}>{source.special_source ? "Sonderquelle editieren" : "Quelle / Profil bearbeiten"}</button>}
      {onDelete && <button type="button" disabled={busy} className="cc-button cc-button-danger" onClick={()=>onDelete(source)}>Sonderquelle löschen</button>}
    </div>}
  </article>)}</div>;
}

export function SourceEvidence({origins}:{origins:{url:string;source_kind:string;event_data:string}[]}) {
  return <div className="space-y-3">{origins.map((origin,index)=>{
    let facts:{title?:string;starts?:string[];location?:string;evidence?:string}={};
    try {facts=JSON.parse(origin.event_data);} catch { /* Historical invalid evidence remains explicitly unavailable. */ }
    return <article key={`${origin.url}-${index}`} className="mt-2 border-t pt-2"><a className="underline" href={link(origin.url)} target="_blank" rel="noreferrer">{sourceKinds[origin.source_kind] ?? "Quelle"}</a><p>{facts?.title ?? "Titel nicht auswertbar"}</p><p>Start: {Array.isArray(facts?.starts)?facts.starts.join(", "):"Datum fehlt"} · {facts?.location || "Location nicht angegeben"}</p><details><summary>Quellenbeleg anzeigen</summary><p className="max-h-40 overflow-auto whitespace-pre-wrap text-sm">{facts?.evidence ?? "Beleg nicht auswertbar"}</p></details></article>;
  })}</div>;
}
