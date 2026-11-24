"use client";
import { useEffect, useState } from "react";
import ActionFeedback from "../ActionFeedback";
import { discoveryApi as api } from "./discoveryApi";

type Rule = { id: string; keyword: string; reason: string; enabled: boolean };
export default function KeywordRules({busy,action}: {busy:boolean;action:(work:()=>Promise<void>)=>Promise<void>}) {
  const [rules,setRules]=useState<Rule[]>([]);
  const [editing,setEditing]=useState<string | null>(null);
  const [keyword,setKeyword]=useState("");
  const [bulkKeywords,setBulkKeywords]=useState("");
  const [reason,setReason]=useState("");
  const [enabled,setEnabled]=useState(true);
  const [error,setError]=useState("");
  const [feedback,setFeedback]=useState<{kind:"success"|"partial";message:string} | null>(null);
  useEffect(()=>{
    const controller=new AbortController();
    api<Rule[]>("/keyword-rules","GET",undefined,controller.signal).then(rows=>{if(!controller.signal.aborted)setRules(rows);}).catch(cause=>{if(!controller.signal.aborted)setError(cause instanceof Error ? cause.message : "Regeln konnten nicht geladen werden.");});
    return ()=>controller.abort();
  },[]);
  function clear(){setEditing(null);setKeyword("");setReason("");setEnabled(true);}
  return <section className="mt-5 space-y-3" aria-label="Schlagwort-Ausschlüsse">
    <h2>Schlagwort-Ausschlüsse</h2><p>Kommagetrennte Schlagwörter werden bei unbekannten Locations automatisch archiviert. Bestätigte Portfolio-Locations bleiben zur manuellen Prüfung. Bereits gespeicherte Funde nach Regeländerungen über „Neu bewerten“ erneut auswerten.</p>
    {error && <p role="alert">{error}</p>}
    {feedback && <ActionFeedback kind={feedback.kind}>{feedback.message}</ActionFeedback>}
    <ul className="discovery-history-list">{rules.map(rule=><li key={rule.id}><div><strong>{rule.keyword}</strong><p>{rule.reason} · {rule.enabled ? "Aktiv" : "Inaktiv"}</p></div><button type="button" className="discovery-button" disabled={busy} onClick={()=>{setEditing(rule.id);setKeyword(rule.keyword);setReason(rule.reason);setEnabled(rule.enabled);}}>Bearbeiten: {rule.keyword}</button></li>)}</ul>
    {!editing && <form className="space-y-3 rounded-lg border border-white/15 p-3" onSubmit={event=>{event.preventDefault();
      const seen=new Set<string>();
      const terms=bulkKeywords.split(",").map(value=>value.trim()).filter(value=>{
        const key=value.toLocaleLowerCase("de-DE");
        if(!value || seen.has(key)) return false;
        seen.add(key);
        return true;
      });
      if(terms.length===0){setError("Bitte mindestens ein Schlagwort eintragen.");return;}
      if(terms.length>100){setError("Maximal 100 Schlagwörter pro Vorgang.");return;}
      setError("");setFeedback(null);
      void action(async()=>{const result=await api<{created:number;alreadyPresent:number}>("/keyword-rules/batch","POST",{keywords:terms,reason,enabled});
        setRules(await api<Rule[]>("/keyword-rules"));setBulkKeywords("");setReason("");setEnabled(true);
        if(result.alreadyPresent) setFeedback({kind:"partial",message:`${result.created} neu · ${result.alreadyPresent} bereits vorhandene Schlagwörter übersprungen.`});
        else setFeedback({kind:"success",message:`${result.created} Schlagwörter gespeichert.`});
      });
    }}>
      <label className="block">Schlagwörter (durch Komma getrennt)
        <textarea className="cc-input" required rows={3} maxLength={16000} placeholder="Karneval, B2B, Ausverkauft, Jecke, Release Party" value={bulkKeywords} onChange={event=>setBulkKeywords(event.target.value)} />
      </label>
      <label className="block">Gemeinsamer Ausschlussgrund (optional)
        <input className="cc-input" maxLength={1000} placeholder="Leer lassen für individuellen Grund je Schlagwort" value={reason} onChange={event=>setReason(event.target.value)}/>
      </label>
      <label className="block"><input type="checkbox" checked={enabled} onChange={event=>setEnabled(event.target.checked)} /> Aktiv</label>
      <button type="submit" className="discovery-button" disabled={busy}>Schlagwörter gesammelt hinzufügen</button>
    </form>}
    {editing && <form className="space-y-3" onSubmit={event=>{event.preventDefault();void action(async()=>{await api(`/keyword-rules/${editing}`,"PUT",{keyword,reason,enabled});setRules(await api<Rule[]>("/keyword-rules"));clear();});}}>
      <label className="block">Schlagwort / Wortgruppe<input className="cc-input" required maxLength={200} value={keyword} onChange={event=>setKeyword(event.target.value)} /></label>
      <label className="block">Ausschlussgrund<input className="cc-input" required maxLength={1000} value={reason} onChange={event=>setReason(event.target.value)} /></label>
      <label className="block"><input type="checkbox" checked={enabled} onChange={event=>setEnabled(event.target.checked)} /> Aktiv</label>
      <button type="submit" className="discovery-button" disabled={busy}>Regel speichern</button><button type="button" className="discovery-button" onClick={clear}>Bearbeiten abbrechen</button>
    </form>}
  </section>;
}
