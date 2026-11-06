"use client";
import Link from "next/link";
import { useEffect, useState } from "react";
import { discoveryApi } from "../vorschlaege/discoveryApi";
import type { Overview } from "../vorschlaege/discoveryTypes";
import { discoveryStatusLabel } from "../discoveryLabels";
export default function AutomationPage() {
  const [data, setData] = useState<Overview | null>(null);
  const [error, setError] = useState("");
  useEffect(() => {const controller = new AbortController(); discoveryApi<Overview>("?filter=READY", "GET", undefined, controller.signal).then(setData).catch(error => {if (!controller.signal.aborted) setError(error instanceof Error ? error.message : "Status nicht verfügbar");}); return () => controller.abort();}, []);
  return <main className="cc-page"><header className="cc-page-heading"><p className="cc-eyebrow">Verwaltung</p><h1>Automatisierung</h1><p className="cc-description">Suchläufe und Quellen im Blick behalten.</p></header>{error && <p className="cc-message" role="alert">{error}</p>}{!data && !error && <p role="status">Status wird geladen …</p>}<div className="cc-settings-grid"><section className="cc-panel"><h2>Event-Discovery</h2><p>{data?.runs[0] ? `${discoveryStatusLabel(data.runs[0].status)} · ${new Date(data.runs[0].started_at).toLocaleString("de-DE", {timeZone: "Europe/Berlin"})}` : data ? "Noch kein Suchlauf" : "Status wird ermittelt"}</p><p>{data ? `${data.sources.filter(source => source.enabled !== false).length} aktive Quellen · ${data.automaticallyImported} automatisch übernommene Termine` : ""}</p><Link className="cc-button cc-button-primary" href="/dashboard/vorschlaege">Discovery öffnen</Link><Link className="cc-button" href="/dashboard/vorschlaege#discovery-tools">Quellen verwalten</Link></section><section className="cc-panel"><h2>Redaktionelle Freigaben</h2><p>Eine Kalenderübernahme erteilt keine Veröffentlichungsfreigabe. Beiträge, Änderungen und Absagen werden in ihren bestehenden Arbeitsabläufen geprüft.</p><Link className="cc-button" href="/dashboard/redaktion">Redaktion öffnen</Link><Link className="cc-button" href="/dashboard/vorschlaege#discovery-history">Änderungen &amp; Absagen prüfen</Link></section></div></main>;
}
