"use client";
import { useState } from "react";
import DashboardModal from "../DashboardModal";
import { useCatalog } from "../CatalogProvider";
import { sourceKinds } from "../SourceOverview";
import type { Source } from "./discoveryTypes";
import { discoveryApi as api } from "./discoveryApi";
const field = "mt-1 w-full rounded-lg border border-white/20 bg-neutral-900 p-3";
const button = "discovery-button";
export default function SourceEditorDialog({ initialSource, busy, error, action, onClose }: {
 initialSource: Source; busy: boolean; error: string; action: (work: () => Promise<void>) => Promise<void>; onClose: () => void;
}) {
 const { venues } = useCatalog();
 const [source, updateSource] = useState<Source | null>(initialSource);
 function setSource(value: Source | null) { if (value === null) onClose(); else updateSource(value); }
 return (
    <DashboardModal open={!!source} onClose={() => setSource(null)} title="Quelle & Extraktionsprofil">
      {source && <form className="space-y-4" onSubmit={e => {e.preventDefault(); void action(async () => { await api("/sources","POST",{venueId:source.venue_id,url:source.url,approved:source.approved,itemSelector:source.item_selector,titleSelector:source.title_selector,dateSelector:source.date_selector,sourceKind:source.source_kind ?? (source.source_type==="OFFICIAL"?"WEBSITE":"CUSTOM"),enabled:source.enabled ?? true,priority:source.priority ?? 100}); setSource(null); });}}>
        {error && <p role="alert" className="text-red-400">{error}</p>}
        <label className="block">Location<select className={field} value={source.venue_id} onChange={e => setSource({...source,venue_id:e.target.value})} required>{venues.map(v => <option key={v.id} value={v.id}>{v.name}</option>)}</select></label>
        <label className="block">Quell-URL<input className={field} type="url" required maxLength={2048} value={source.url} onChange={e => setSource({...source,url:e.target.value})} /></label>
        <label className="block">Quellenart<select className={field} value={source.source_kind ?? (source.source_type==="OFFICIAL"?"WEBSITE":"CUSTOM")} onChange={e=>setSource({...source,source_kind:e.target.value})}>{Object.entries(sourceKinds).map(([value,label])=><option key={value} value={value}>{label}</option>)}</select></label>
        <label className="flex gap-2"><input type="checkbox" checked={source.enabled ?? true} onChange={e=>setSource({...source,enabled:e.target.checked})} />Quelle für Discovery verwenden</label>
        <label className="block">Reihenfolge der Zusatzquellen (kleine Zahl zuerst)<input type="number" min={0} max={999} className={field} value={source.priority ?? 100} onChange={e=>setSource({...source,priority:Number(e.target.value)})} /></label>
        <label className="flex gap-2"><input type="checkbox" checked={source.approved} onChange={e => setSource({...source,approved:e.target.checked})} />Quelle ausdrücklich zur automatischen Übernahme freigeben</label>
        <p className="text-sm">Externe Portale bleiben als externe Quellen gekennzeichnet. Freigabe erst nach Prüfung der Verlässlichkeit.</p>
        <details><summary>Optionales HTML-Profil (CSS-Selektoren)</summary>{(["item_selector","title_selector","date_selector"] as const).map((key,i) => <label key={key} className="mt-3 block">{["Event-Container","Titel innerhalb des Containers","Startdatum (datetime/content oder ISO-Text)"][i]}<input className={field} maxLength={300} value={source[key]} onChange={e => setSource({...source,[key]:e.target.value})} /></label>)}</details>
        <button disabled={busy} className={button}>Speichern</button>
      </form>}
    </DashboardModal>
 );
}
