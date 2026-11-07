"use client";
import { useEffect, useState } from "react";
import { discoveryApi as api } from "./discoveryApi";

type Rule = { id: string; keyword: string; reason: string; enabled: boolean };
export default function KeywordRules({busy,action}: {busy:boolean;action:(work:()=>Promise<void>)=>Promise<void>}) {
  const [rules,setRules]=useState<Rule[]>([]);
  const [editing,setEditing]=useState<string | null>(null);
  const [keyword,setKeyword]=useState("");
  const [reason,setReason]=useState("");
  const [enabled,setEnabled]=useState(true);
  const [error,setError]=useState("");
  useEffect(()=>{
    const controller=new AbortController();
    api<Rule[]>("/keyword-rules","GET",undefined,controller.signal).then(rows=>{if(!controller.signal.aborted)setRules(rows);}).catch(cause=>{if(!controller.signal.aborted)setError(cause instanceof Error ? cause.message : "Regeln konnten nicht geladen werden.");});
    return ()=>controller.abort();
  },[]);
  function clear(){setEditing(null);setKeyword("");setReason("");setEnabled(true);}
  return <section className="mt-5 space-y-3" aria-label="Schlagwort-Ausschlüsse">
    <h2>Schlagwort-Ausschlüsse</h2><p>Aktive Regeln gelten bei Suchläufen und „Neu bewerten“. Bestätigte Portfolio-Locations bleiben zur manuellen Prüfung. Nach Regeländerungen gespeicherte Funde über „Neu bewerten“ prüfen.</p>
    {error && <p role="alert">{error}</p>}
    <ul className="discovery-history-list">{rules.map(rule=><li key={rule.id}><div><strong>{rule.keyword}</strong><p>{rule.reason} · {rule.enabled ? "Aktiv" : "Inaktiv"}</p></div><button type="button" className="discovery-button" disabled={busy} onClick={()=>{setEditing(rule.id);setKeyword(rule.keyword);setReason(rule.reason);setEnabled(rule.enabled);}}>Bearbeiten: {rule.keyword}</button></li>)}</ul>
    <form className="space-y-3" onSubmit={event=>{event.preventDefault();void action(async()=>{await api(editing ? `/keyword-rules/${editing}` : "/keyword-rules",editing ? "PUT" : "POST",{keyword,reason,enabled});setRules(await api<Rule[]>("/keyword-rules"));clear();});}}>
      <label className="block">Schlagwort / Wortgruppe<input className="cc-input" required maxLength={200} value={keyword} onChange={event=>setKeyword(event.target.value)} /></label>
      <label className="block">Ausschlussgrund<input className="cc-input" required maxLength={1000} value={reason} onChange={event=>setReason(event.target.value)} /></label>
      <label className="block"><input type="checkbox" checked={enabled} onChange={event=>setEnabled(event.target.checked)} /> Aktiv</label>
      <button type="submit" className="discovery-button" disabled={busy}>{editing ? "Regel speichern" : "Regel hinzufügen"}</button>{editing && <button type="button" className="discovery-button" onClick={clear}>Bearbeiten abbrechen</button>}
    </form>
  </section>;
}
