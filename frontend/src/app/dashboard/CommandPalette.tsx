"use client";
import Link from "next/link";
import { useEffect, useRef, useState } from "react";
import DashboardModal from "./DashboardModal";
import WorkspaceIcon from "./WorkspaceIcon";
import { useCatalog } from "./CatalogProvider";
import { navigationGroups } from "./workspaceRoutes";
import { recordLink } from "./useRecordLink";
import type { WorkspaceAction } from "./workspaceActions";

export default function CommandPalette({action}: {action: WorkspaceAction | null}) {
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState("");
  const resultsRef = useRef<HTMLDivElement>(null);
  const catalog = useCatalog();
  function show() {
    // Keep keyboard shortcuts from opening a second dialog over an active editor.
    if (document.querySelector('[role="dialog"]')) return;
    setQuery(""); setOpen(true);
  }
  useEffect(() => {
    function key(event: KeyboardEvent) {
      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === "k") {
        event.preventDefault();
        show();
      }
    }
    document.addEventListener("keydown", key);
    return () => document.removeEventListener("keydown", key);
  }, []);
  const needle = query.trim().toLocaleLowerCase("de-DE");
  const matches = (text: string) => text.toLocaleLowerCase("de-DE").includes(needle);
  const groups = [
    {label: "Navigation", loading: false, error: null, items: navigationGroups.flatMap(group => group.items).map(item => ({id: item.href, name: item.label, detail: "Bereich öffnen", href: item.href}))},
    {label: "Partyreihen", loading: catalog.partiesLoading, error: catalog.partiesError, items: catalog.parties.map(party => ({id: party.id, name: party.name, detail: party.standardGenres.join(", "), href: recordLink("/dashboard/partys", party.id)}))},
    {label: "Locations", loading: catalog.venuesLoading, error: catalog.venuesError, items: catalog.venues.map(venue => ({id: venue.id, name: venue.name, detail: venue.instagram, href: recordLink("/dashboard/locations", venue.id)}))},
    {label: "DJs / Akteure", loading: catalog.actorsLoading, error: catalog.actorsError, items: catalog.actors.map(actor => ({id: actor.id, name: actor.name, detail: actor.instagram, href: recordLink("/dashboard/djs", actor.id)}))},
    {label: "Kalender", loading: catalog.entriesLoading, error: catalog.entriesError, items: catalog.entries.map(entry => ({id: entry.id, name: entry.type === "event" ? catalog.parties.find(party => party.id === entry.partyId)?.name ?? "Unbekannte Partyreihe" : entry.reason, detail: `${entry.date} · ${catalog.venues.find(venue => venue.id === entry.locationId)?.name ?? "Location unbekannt"}${entry.eventStatus === "CANCELLED" ? " · ABGESAGT" : ""}`, href: recordLink("/dashboard/kalender", entry.id)}))},
  ].map(group => ({...group, items: group.error ? [] : group.items.filter(item => matches(`${item.name} ${item.detail}`)).slice(0, 5)}));
  const hasAction = action && matches(action.label);
  function move(direction: number) {
    const controls = Array.from(resultsRef.current?.querySelectorAll<HTMLElement>("a[href], button:not(:disabled)") ?? []);
    if (!controls.length) return;
    const current = controls.indexOf(document.activeElement as HTMLElement);
    controls[(current + direction + controls.length) % controls.length].focus();
  }
  return <>
    <button type="button" className="cc-button cc-search-trigger" onClick={show} aria-label="Globale Suche öffnen"><WorkspaceIcon name="search" /><span>Suche</span><kbd>⌘ / Strg K</kbd></button>
    <DashboardModal open={open} onClose={() => setOpen(false)} title="Suche & Aktionen" description="Bereiche und geladene Katalogdaten durchsuchen. Bis zu fünf Treffer je Bereich.">
      <input className="cc-input cc-command-input" type="search" aria-label="Global suchen" placeholder="Party, Location, DJ oder Datum …" value={query} onChange={event => setQuery(event.target.value)} onKeyDown={event => {if (event.key === "ArrowDown") {event.preventDefault(); move(1);}}} />
      <div className="cc-command-results" ref={resultsRef} onKeyDown={event => {if (event.key === "ArrowDown" || event.key === "ArrowUp") {event.preventDefault(); move(event.key === "ArrowDown" ? 1 : -1);}}}>
        {hasAction && <section aria-label="Aktuelle Aktion"><h3>Aktuelle Aktion</h3><button type="button" className="cc-command-result" disabled={action.disabled} onClick={() => {setOpen(false); action.run();}}>{action.label}</button></section>}
        {groups.map(group => <section key={group.label} aria-label={group.label}>
          <h3>{group.label}</h3>
          {group.loading ? <p role="status">Wird geladen …</p> : group.error ? <p role="alert">{group.label} derzeit nicht verfügbar.</p> : group.items.map(item => <Link key={item.id} href={item.href} className="cc-command-result" onClick={() => setOpen(false)}><strong>{item.name}</strong><small>{item.detail}</small></Link>)}
        </section>)}
        {!hasAction && groups.every(group => !group.items.length && !group.loading && !group.error) && <p role="status">Keine Treffer. Suche mit einem anderen Begriff.</p>}
      </div>
    </DashboardModal>
  </>;
}
