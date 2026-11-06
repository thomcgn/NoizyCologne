"use client";

import {useState} from "react";
import DashboardModal from "../DashboardModal";
import {useCatalog} from "../CatalogProvider";
import {discoveryApi as api} from "./discoveryApi";
import {sourceLink} from "./discoveryPresentation";
export type GeographyItem={id:string;title:string;source_url:string;status:string;reason:string;location:string;place?:{city:string;street:string}};
export type GeographyData={items?:GeographyItem[];limit?:number;counts?:Record<string,number>};
const field="mt-1 w-full rounded-lg border border-white/20 bg-neutral-900 p-3";
export default function DiscoveryGeography({data,busy,error,action,onReevaluate}:{data?:GeographyData;busy:boolean;error:string;action:(work:()=>Promise<void>)=>Promise<void>;onReevaluate:()=>void}) {
 const items=Array.isArray(data?.items)?data.items:[];
 const pending=items.filter(item=>item.status!=="OUTSIDE"),outside=items.filter(item=>item.status==="OUTSIDE");
 const pendingCount=data?.counts?(data.counts.UNRESOLVED??0)+(data.counts.NOT_CHECKED??0):pending.length;
 const outsideCount=data?.counts?.OUTSIDE??outside.length;
 const [selected,setSelected]=useState<GeographyItem|null>(null);
 const [visible,setVisible]=useState(20);
 return <section className="discovery-section" aria-label="Standortklärung"><h2>Standortklärung <span className="discovery-count">{data?pendingCount:"–"}</span></h2><p>Nur nachweislich in der Stadt Köln stattfindende Veranstaltungen gelangen in den normalen Prüfbereich. Ungeklärte Standorte werden hier separat geführt.</p>
   <button type="button" className="discovery-button mt-3" disabled={busy} onClick={onReevaluate}>Gespeicherte Standorte neu bewerten</button>
   {!pending.length?<p className="cc-empty">{data?"Keine ungeklärten Standortfunde.":error?"Standorte derzeit nicht verfügbar.":"Standorte werden geladen …"}</p>:<div className="mt-3 space-y-3">{pending.slice(0,visible).map(item=><article key={item.id} className="rounded-lg border p-3"><h3 className="break-words font-semibold">{item.title}</h3><p>{item.location||"Location unbekannt"}{item.place?.city?` · ${item.place.city}`:""}</p><p>{item.reason}</p><div className="mt-2 flex flex-wrap gap-2"><a className="cc-button" href={sourceLink(item.source_url)} target="_blank" rel="noreferrer">Ortsbeleg öffnen</a><button type="button" disabled={busy} className="cc-button" onClick={()=>setSelected(item)}>Standort klären</button></div></article>)}</div>}
   {pendingCount>pending.length&&<p>Zeigt {pending.length} von {pendingCount} Standortfunden. Nach dem Klären werden weitere Funde nachgeladen.</p>}
   {pending.length>visible&&<button className="discovery-button" onClick={()=>setVisible(value=>value+20)}>Weitere Standorte anzeigen</button>}
   <details className="mt-4"><summary>Außerhalb Kölns ({outsideCount})</summary><p>Diese Funde werden vor Party- und Genreprüfung ausgeschlossen. Vorhandene Teamentscheidungen und Termine bleiben erhalten.</p>{outsideCount>outside.length&&<p>Anzeige der letzten {outside.length} von {outsideCount} auswärtigen Funden.</p>}{outside.map(item=><article className="mt-3 break-words border-t pt-2" key={item.id}><strong>{item.title}</strong><p>{item.location} · {item.place?.city||item.reason}</p><a className="underline" href={sourceLink(item.source_url)} target="_blank" rel="noreferrer">Ortsbeleg öffnen</a></article>)}</details>
   {selected&&<LocationConfirmation key={selected.id} item={selected} onClose={()=>setSelected(null)} busy={busy} error={error} action={action}/>}
 </section>;
}
function LocationConfirmation({item,onClose,busy,error,action}:{item:GeographyItem;onClose:()=>void;busy:boolean;error:string;action:(work:()=>Promise<void>)=>Promise<void>}) {
 const {venues,reloadVenues}=useCatalog();
 const [venue,setVenue]=useState("");const [name,setName]=useState(item.location);const [city,setCity]=useState("Köln");
 const [street,setStreet]=useState(item.place?.street??"");const [proof,setProof]=useState(item.source_url);const [confirmed,setConfirmed]=useState(false);
 return <DashboardModal open protectChanges title="Veranstaltungsort bestätigen" onClose={onClose}><form className="space-y-4" onSubmit={event=>{event.preventDefault();void action(async()=>{await api(`/geography/${encodeURIComponent(item.id)}/confirm`,"POST",{venueId:venue==="new"?null:venue,venueName:venue==="new"?name:null,city,street,proofUrl:proof,confirmed});await reloadVenues();onClose();});}}>
  {error&&<p role="alert">{error}</p>}<p>{item.title}. Prüfe die tatsächliche Veranstaltungsadresse anhand des Belegs. Ein Titel oder Portalpfad mit „Köln“ reicht nicht.</p>
  <a className="underline" href={sourceLink(item.source_url)} target="_blank" rel="noreferrer">Ortsbeleg öffnen</a>
  <label className="block">Location<select className={field} required value={venue} onChange={event=>setVenue(event.target.value)}><option value="">Bitte ausdrücklich wählen</option>{venues.map(value=><option key={value.id} value={value.id}>{value.name}</option>)}<option value="new">Neue Location anlegen</option></select></label>
  {venue==="new"&&<label className="block">Name der neuen Location<input required maxLength={200} className={field} value={name} onChange={event=>setName(event.target.value)}/></label>}
  <label className="block">Bestätigte Stadt<input required maxLength={200} className={field} value={city} onChange={event=>setCity(event.target.value)}/></label>
  <label className="block">Straße und Hausnummer<input required maxLength={300} className={field} value={street} onChange={event=>setStreet(event.target.value)}/></label>
  <label className="block">Beleg-URL<input type="url" required maxLength={2048} className={field} value={proof} onChange={event=>setProof(event.target.value)}/></label>
  <label className="flex gap-2"><input type="checkbox" className="mt-1 h-4 w-4 shrink-0" checked={confirmed} onChange={event=>setConfirmed(event.target.checked)}/><span>Ich habe die tatsächliche Adresse und die Zugehörigkeit zur Stadt Köln geprüft.</span></label>
  <p>Die bestätigte Location und belegte Namensvarianten werden mit ihrer Ortsidentität für künftige Suchläufe gespeichert. Bloße Namensgleichheit wird nicht wiederverwendet. Es erfolgt kein Kalenderimport.</p>
  <button className="discovery-button" disabled={busy||!confirmed||!venue||!street.trim()}>Kölner Standort bestätigen</button>
 </form></DashboardModal>;
}
