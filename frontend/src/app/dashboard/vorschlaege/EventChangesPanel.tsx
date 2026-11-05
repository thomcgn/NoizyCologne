"use client";

import { useCallback, useEffect, useState } from "react";
import DashboardModal from "../DashboardModal";
import { useCatalog } from "../CatalogProvider";

export type EventData = { title: string; starts: string[]; location: string; state: string; evidence: string; url: string };
export type EventChange = { id: string; revision: number; kind: string; state: string; venue_name: string; instagram: string; before_data: string; after_data: string; source_url: string; reason: string; blocks_publication: boolean; calendar_entry_id: string | null; calendar_party_id: string | null; calendar_party_name?: string | null; calendar_venue_name?: string | null; calendar_venue_id: string | null; calendar_date: string | null; calendar_start_time: string | null; dm_text: string; answer: string; inquiry_status: string };
type Cancellation = { id: string; revision: number; calendar_entry_id: string; party_name: string; venue_name: string; event_date: string; reason_code: string; announcement_caption: string; announcement_status: string; media_asset_id: string | null; replacement_candidate_id: string | null };
type History = { id: number; action: string; note: string; created_at: string };
export const changeLabels: Record<string, string> = { CANCELLATION_DETECTED: "Absage erkannt", CANCELLATION_UNCONFIRMED: "Absage unbestätigt – prüfen", RESCHEDULE_DETECTED: "Verschiebung", POSSIBLE_REPLACEMENT: "Mögliche Ersatzparty", SOURCE_MISSING: "Nicht mehr bestätigt", EVENT_CHANGED: "Uhrzeit, Location oder weitere Angaben geändert", CONFLICTING_SOURCES: "Widersprüchliche Quellen", LINEUP_CHANGE: "Line-up-Änderung" };
const button = "min-h-11 rounded-lg border border-current/25 px-4 py-2 disabled:opacity-50";
const field = "mt-1 w-full rounded-lg border border-white/20 bg-neutral-900 p-3 text-white";
export function safeLink(value: string) { try { const url = new URL(value); return url.protocol === "https:" && !url.username && !url.password ? url.href : undefined; } catch { return undefined; } }
function eventData(raw: string): EventData { return JSON.parse(raw) as EventData; }
async function request(path: string, method = "GET", body?: unknown) {
  const response = await fetch(`/api/v1/${path}`, { method, cache: "no-store", headers: { "Content-Type": "application/json" }, ...(body === undefined ? {} : { body: JSON.stringify(body) }) });
  const text = await response.text();
  const data = text ? JSON.parse(text) : null;
  if (!response.ok) throw new Error(data?.message ?? `HTTP ${response.status}`);
  return data;
}

export default function EventChangesPanel({ onReviewCandidate }: { onReviewCandidate?: (id: string) => void } = {}) {
  const { entries, parties, venues, reloadEntries, reloadDrafts } = useCatalog();
  const [changes, setChanges] = useState<EventChange[]>([]);
  const [cancellations, setCancellations] = useState<Cancellation[]>([]);
  const [selected, setSelected] = useState<EventChange | null>(null);
  const [announcement, setAnnouncement] = useState<Cancellation | null>(null);
  const [manual, setManual] = useState(false);
  const [manualEntry, setManualEntry] = useState("");
  const [reasonCode, setReasonCode] = useState("UNSPECIFIED");
  const [category, setCategory] = useState("OPEN");
  const [note, setNote] = useState("");
  const [dm, setDm] = useState("");
  const [answer, setAnswer] = useState("");
  const [date, setDate] = useState("");
  const [time, setTime] = useState("");
  const [venue, setVenue] = useState("");
  const [trigger, setTrigger] = useState("MANUAL");
  const [replacementOptions, setReplacementOptions] = useState<{ id: string; title: string }[]>([]);
  const [replacement, setReplacement] = useState("");
  const [cancellationHistory, setCancellationHistory] = useState<History[]>([]);
  const [history, setHistory] = useState<History[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const reload = useCallback(async () => {
    const [nextChanges, nextCancellations] = await Promise.all([request("discovery/changes"), request("cancellations")]);
    setChanges(nextChanges); setCancellations(nextCancellations);
  }, []);
  useEffect(() => {
    const load = () => void reload().catch(cause => setError(cause instanceof Error ? cause.message : "Laden fehlgeschlagen"));
    const timer = window.setTimeout(load, 0); const poll = window.setInterval(load, 10000);
    return () => { window.clearTimeout(timer); window.clearInterval(poll); };
  }, [reload]);
  async function action(work: () => Promise<void>) {
    setBusy(true); setError(""); setNotice("");
    try { await work(); await Promise.all([reload(), reloadEntries(), reloadDrafts()]); }
    catch (cause) { setError(cause instanceof Error ? cause.message : "Aktion fehlgeschlagen"); }
    finally { setBusy(false); }
  }
  async function open(change: EventChange) {
    const after = eventData(change.after_data);
    setSelected(change); setNote(""); setDm(change.dm_text); setAnswer(change.answer); setReasonCode("UNSPECIFIED"); setReplacement("");
    setDate(after.starts[0]?.slice(0, 10) ?? change.calendar_date ?? ""); setTime(after.starts[0]?.split("T")[1]?.slice(0, 5) ?? change.calendar_start_time?.slice(0, 5) ?? "");
    setVenue(change.calendar_venue_id ?? ""); setTrigger(change.kind === "CANCELLATION_DETECTED" ? "OFFICIAL_SOURCE" : "MANUAL");
    setHistory([]); setReplacementOptions([]); setError("");
    try { const [history, replacements] = await Promise.all([request(`discovery/changes/${change.id}/history`), request(`discovery/changes/${change.id}/replacements`)]); setHistory(history); setReplacementOptions(replacements); } catch (cause) { setError(cause instanceof Error ? cause.message : "Historie nicht verfügbar"); }
  }
  async function openCancellation(item: Cancellation) {
    setAnnouncement({ ...item }); setCancellationHistory([]); setError("");
    try { setCancellationHistory(await request(`cancellations/${item.id}/history`)); }
    catch(cause) { setError(cause instanceof Error ? cause.message : "Historie nicht verfügbar"); }
  }
  async function decide(name: string) {
    if (!selected) return;
    await request(`discovery/changes/${selected.id}`, "PUT", { revision: selected.revision, action: name, note, dmText: dm, answer, partyId: selected.calendar_party_id, venueId: venue, date, startTime: time || null, trigger, reasonCode, replacementCandidateId: replacement || null });
    setSelected(null); setNotice("Entscheidung gespeichert. Keine Veröffentlichung und keine Instagram-Nachricht ausgelöst.");
  }
  const before = selected ? eventData(selected.before_data) : null;
  const after = selected ? eventData(selected.after_data) : null;
  const visible = changes.filter(change => category === "ALL" || (category === "OPEN" ? change.blocks_publication : change.kind === category));
  return <section className="space-y-4 rounded-xl border border-amber-400/40 p-4" aria-label="Änderungen und Absagen">
    <div className="flex flex-wrap items-center justify-between gap-3"><h2 className="text-xl font-semibold">Änderungen &amp; Absagen</h2><div className="flex flex-wrap gap-2">
      <button className={button} disabled={busy} onClick={() => void action(async () => { await request("discovery/monitor", "POST"); setNotice("Änderungscheck angefordert."); })}>Änderungen jetzt prüfen</button>
      <button className={button} onClick={() => { setManualEntry(""); setReasonCode("UNSPECIFIED"); setManual(true); setError(""); }}>Veranstaltung manuell absagen</button>
    </div></div>
    <p className="text-sm">Offene Befunde sperren Originalpost-Freigaben. Bestehende Inhalte bleiben erhalten. Instagram-Publishing und die Behandlung bereits veröffentlichter Posts erfolgen hier nicht automatisch.</p>
    {error && <p role="alert" className="text-red-400">{error}</p>}{notice && <p role="status">{notice}</p>}
    <label className="block">Änderungsfilter<select className={field} value={category} onChange={event => setCategory(event.target.value)}><option value="OPEN">Offen / Veröffentlichung gesperrt</option><option value="ALL">Alle einschließlich Historie</option>{Object.entries(changeLabels).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select></label>
    {visible.length === 0 && <p>Keine passenden Änderungsmeldungen.</p>}
    {visible.map(change => <article key={change.id} className="rounded-lg border border-current/20 p-3"><h3 className="font-semibold">{changeLabels[change.kind] ?? change.kind} · {change.venue_name}</h3><p>{eventData(change.before_data).title} → {eventData(change.after_data).title}</p><p className="text-sm">{change.reason}</p><p className="text-sm">{change.blocks_publication ? "Originalpost gesperrt" : "Befund geklärt"} · {change.state}</p><button className={`${button} mt-3`} onClick={() => void open(change)}>Änderung vergleichen</button></article>)}
    <details><summary>Cancellation-Workflow · {cancellations.length} bestätigte Absagen</summary>
      {cancellations.map(item => <article key={item.id} className="mt-3 rounded-lg border border-current/20 p-3"><h3>{item.party_name} · {item.event_date} · {item.venue_name}</h3><p>Cancellation abgeschlossen · {item.reason_code === "PERSONALBEDINGTER_AUSFALL" ? "Bestätigter personalbedingter Ausfall" : item.reason_code} · Absageentwurf: {item.announcement_status}</p><button className={`${button} mt-2`} onClick={() => void openCancellation(item)}>Cancellation-Workflow öffnen</button></article>)}
    </details>
    <DashboardModal open={!!selected} onClose={() => setSelected(null)} title="Veranstaltungsänderung vergleichen">
      {selected && before && after && <div className="space-y-4">
        {error && <p role="alert" className="text-red-400">{error}</p>}
        <p>{changeLabels[selected.kind]} · {selected.reason}</p>
        <div className="overflow-x-auto"><table className="w-full text-left text-sm"><thead><tr><th>Feld</th><th>Bisher</th><th>Neu</th></tr></thead><tbody>{[["Party", selected.calendar_party_name || before.title, after.title], ["Datum / Beginn", selected.calendar_date ? `${selected.calendar_date} ${selected.calendar_start_time ?? ""}` : before.starts.join(", "), after.starts.join(", ")], ["Location", selected.calendar_venue_name || before.location || selected.venue_name, after.location || selected.venue_name], ["Status", before.state || "Im Kalender / bisher bestätigt", after.state || changeLabels[selected.kind]]].map(([label, old, next]) => <tr key={label}><th className="p-2">{label}</th><td className="p-2">{old}</td><td className="p-2">{next}</td></tr>)}</tbody></table></div>
        <a className="underline" href={safeLink(selected.source_url)} target="_blank" rel="noreferrer">Quelle öffnen</a>
        <details><summary>Quellenvergleich und Historie</summary><p className="whitespace-pre-wrap break-words">{before.evidence}</p><hr /><p className="whitespace-pre-wrap break-words">{after.evidence}</p>{history.map(item => <p key={item.id}>{item.created_at} · {item.action} · {item.note}</p>)}</details>
        <label className="block">Begründung / bestätigter Sachverhalt<textarea className={field} rows={3} maxLength={4000} value={note} onChange={event => setNote(event.target.value)} /></label>
        <p className="text-sm">Nur allgemeine Gründe dokumentieren, keine Gesundheitsdetails. Eine Freigabe muss nach Klärung erneut in der Redaktion geprüft werden.</p>
        <div className="flex flex-wrap gap-2">{[["CONFIRM", "Änderung bestätigen"], ["SEPARATE", "Als getrennte Veranstaltungen behandeln"], ["REJECT", "Vorschlag ablehnen"], ["DJ_ONLY", "Nur DJ-Ausfall bestätigen"], ["RELEASE", "Nach Klärung Sperre aufheben"]].map(([name, label]) => <button key={name} className={button} disabled={busy || !note.trim()} onClick={() => void action(() => decide(name))}>{label}</button>)}</div>
        <details><summary>Rückfrage an Location vorbereiten</summary>
          <button className={`${button} mt-3`} disabled={busy} onClick={() => void action(async () => { const result = await request(`discovery/changes/${selected.id}/dm`); setDm(result.text); })}>DM-Entwurf erstellen</button>
          <label className="block mt-3">DM-Entwurf<textarea className={field} rows={8} maxLength={12000} value={dm} onChange={event => setDm(event.target.value)} /></label>
          <div className="flex flex-wrap gap-2"><button className={button} disabled={!dm || busy} onClick={() => void action(async () => { await navigator.clipboard.writeText(dm); setNotice("DM kopiert. Bitte selbst in Instagram versenden."); })}>DM kopieren</button>{/^[A-Za-z0-9._]+$/.test(selected.instagram ?? "") && <a className={button} href={`https://www.instagram.com/${selected.instagram}/`} target="_blank" rel="noreferrer">Instagram-Profil öffnen</a>}
            <button className={button} disabled={busy || !note.trim() || !dm} onClick={() => void action(() => decide("INQUIRY"))}>Rückfrage speichern</button><button className={button} disabled={busy || !note.trim() || !dm} onClick={() => void action(() => decide("SENT"))}>Rückfrage gesendet (manuell)</button></div>
          <label className="block mt-3">Bestätigte Antwort (ohne private Details)<textarea className={field} rows={3} maxLength={4000} value={answer} onChange={event => setAnswer(event.target.value)} /></label><button className={button} disabled={busy || !note.trim() || !answer.trim()} onClick={() => void action(() => decide("ANSWER"))}>Antwort dokumentieren</button>
        </details>
        {selected.calendar_entry_id && <>
          <details><summary>Kalender nach Prüfung aktualisieren</summary><p className="text-sm">Partyreihe, Originalflyer und redaktionelle Inhalte werden nicht ersetzt. Eine Ersatzparty separat prüfen.</p><label className="block">Bestätigtes Datum<input type="date" className={field} value={date} onChange={event => setDate(event.target.value)} /></label><label className="block">Bestätigter Beginn<input type="time" className={field} value={time} onChange={event => setTime(event.target.value)} /></label><label className="block">Bestätigte Location<select className={field} value={venue} onChange={event => setVenue(event.target.value)}>{venues.map(item => <option key={item.id} value={item.id}>{item.name}</option>)}</select></label><button className={`${button} mt-3`} disabled={busy || !note.trim() || selected.kind === "POSSIBLE_REPLACEMENT"} onClick={() => void action(() => decide("UPDATE_CALENDAR"))}>Kalender aktualisieren</button></details>
          <details><summary>Gesamte Veranstaltung absagen</summary><label className="block">Bestätigung<select className={field} value={trigger} onChange={event => setTrigger(event.target.value)}><option value="MANUAL">Durch Teammitglied bestätigt</option><option value="OFFICIAL_SOURCE">Ausdrückliche offizielle Absage</option><option value="DM_CONFIRMED">Nach dokumentierter DM-Antwort bestätigt</option></select></label><ReasonSelect value={reasonCode} onChange={setReasonCode} /><label className="block">Bestätigte Ersatzparty (optional)<select className={field} value={replacement} onChange={event => setReplacement(event.target.value)}><option value="">Kein bestätigter Ersatz</option>{replacementOptions.map(item => <option key={item.id} value={item.id}>{item.title}</option>)}</select></label><button className={`${button} mt-3`} disabled={busy || !note.trim()} onClick={() => void action(() => decide("CANCEL"))}>Absage bestätigen</button></details>
        </>}
        {selected.kind === "POSSIBLE_REPLACEMENT" && <div><label className="block">Mögliche Ersatzparty<select className={field} value={replacement} onChange={event => setReplacement(event.target.value)}><option value="">Bitte wählen</option>{replacementOptions.map(item => <option key={item.id} value={item.id}>{item.title}</option>)}</select></label><button className={button} disabled={!replacement || !onReviewCandidate} onClick={() => { setSelected(null); onReviewCandidate?.(replacement); }}>Ersatzparty prüfen</button></div>}
        <button className={button} onClick={() => setSelected(null)}>Abbrechen</button>
      </div>}
    </DashboardModal>
    <DashboardModal open={manual} onClose={() => setManual(false)} title="Veranstaltung manuell absagen">
      <form className="space-y-4" onSubmit={event => { event.preventDefault(); void action(async () => { await request("cancellations", "POST", { calendarEntryId: manualEntry, trigger: "MANUAL", reasonCode }); setManual(false); setNotice("Bestätigte Absage gespeichert. Separaten Absageentwurf im Cancellation-Workflow prüfen."); }); }}>
        {error && <p role="alert" className="text-red-400">{error}</p>}
        <label className="block">Veranstaltung<select required className={field} value={manualEntry} onChange={event => setManualEntry(event.target.value)}><option value="">Bitte wählen</option>{entries.filter(item => item.type === "event").map(item => <option key={item.id} value={item.id}>{item.date} · {parties.find(party => party.id === item.partyId)?.name} · {venues.find(venue => venue.id === item.locationId)?.name}</option>)}</select></label><ReasonSelect value={reasonCode} onChange={setReasonCode} /><p>Hiermit bestätigst du die Absage der gesamten Veranstaltung. Ein einzelner DJ-Ausfall ist keine Veranstaltungsabsage.</p><button className={button} disabled={busy || !manualEntry}>Gesamte Veranstaltung verbindlich absagen</button>
      </form>
    </DashboardModal>
    <DashboardModal open={!!announcement} onClose={() => setAnnouncement(null)} title="Zentraler Cancellation-Workflow">
      {announcement && <div className="space-y-4">
        {error && <p role="alert" className="text-red-400">{error}</p>}
        <p>{announcement.party_name} · {announcement.venue_name} · {announcement.event_date}</p>
        <p role="status">Originalpost gesperrt. Bereits veröffentlichte Instagram-Inhalte bleiben unangetastet. Bei laufender oder unklarer Veröffentlichung den tatsächlichen Instagram-Status manuell prüfen; hier wird kein Remote-Abbruch bestätigt.</p>
        <button className={button} disabled={busy} onClick={() => void action(async () => { const result = await request(`cancellations/${announcement.id}/graphic`, "POST"); setAnnouncement({ ...announcement, media_asset_id: result.mediaAssetId }); })}>Absagegrafik vorbereiten</button>
        {announcement.media_asset_id && <a className="block underline" target="_blank" rel="noreferrer" href={`/api/v1/cancellations/${announcement.id}/graphic`}>Neue CANCELLED-Grafik öffnen</a>}
        <label className="block">Separater Absagetext<textarea className={field} rows={7} maxLength={12000} value={announcement.announcement_caption} onChange={event => setAnnouncement({ ...announcement, announcement_caption: event.target.value })} /></label>
        <label className="block">Freigabestatus des Absageentwurfs<select className={field} value={announcement.announcement_status} onChange={event => setAnnouncement({ ...announcement, announcement_status: event.target.value })}><option value="DRAFT">Entwurf</option><option value="READY_FOR_REVIEW">Zur Prüfung</option><option value="APPROVED">Freigegeben, nicht veröffentlicht</option></select></label>
        <button className={button} disabled={busy} onClick={() => void action(async () => { await request(`cancellations/${announcement.id}/announcement`, "PUT", { caption: announcement.announcement_caption, status: announcement.announcement_status, revision: announcement.revision }); setAnnouncement(null); })}>Absageentwurf speichern</button>
        <details><summary>Cancellation-Historie</summary>{cancellationHistory.map(item => <p key={item.id}>{item.created_at} · {item.action} · {item.note}</p>)}</details>
        <p className="text-sm">Keine automatische Veröffentlichung. Eine Notfallpriorisierung im Feed benötigt eine gesonderte Publisher-Freigabe; sie wird hier nicht automatisch gesetzt.</p>
      </div>}
    </DashboardModal>
  </section>;
}
function ReasonSelect({ value, onChange }: { value: string; onChange: (value: string) => void }) {
  return <label className="block">Allgemeiner Absagegrund<select className={field} value={value} onChange={event => onChange(event.target.value)}><option value="UNSPECIFIED">Keine nähere öffentliche Angabe</option><option value="PERSONALBEDINGTER_AUSFALL">Bestätigter personalbedingter Ausfall</option><option value="ORGANISATORISCH">Organisatorischer Grund</option><option value="REPLACEMENT">Bestätigte Ersetzung durch andere Party</option></select></label>;
}
