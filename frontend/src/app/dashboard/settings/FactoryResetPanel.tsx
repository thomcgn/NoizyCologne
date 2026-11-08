"use client";

import { useState, type FormEvent } from "react";
import DashboardModal from "../DashboardModal";

type Preview = {
  recordCount: number;
  mediaFileCount: number;
  challenge: string;
  tables: { name: string; rows: number }[];
};
type Result = {
  deletedRecords: number;
  deletedFiles: number;
  filesNotDeleted: number;
  partial: boolean;
};

async function responseError(response: Response): Promise<string> {
  const body = await response.json().catch(() => ({})) as { error?: unknown };
  return typeof body.error === "string" ? body.error : `Fehler HTTP ${response.status}`;
}

export default function FactoryResetPanel() {
  const [open, setOpen] = useState(false);
  const [loading, setLoading] = useState(false);
  const [working, setWorking] = useState(false);
  const [preview, setPreview] = useState<Preview | null>(null);
  const [result, setResult] = useState<Result | null>(null);
  const [error, setError] = useState("");
  const [phrase, setPhrase] = useState("");
  const [password, setPassword] = useState("");
  const [acknowledged, setAcknowledged] = useState(false);

  async function loadPreview() {
    setLoading(true);
    setPreview(null);
    try {
      const response = await fetch("/api/admin/factory-reset", { cache: "no-store" });
      if (!response.ok) throw new Error(await responseError(response));
      setPreview(await response.json() as Preview);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Die Vorschau konnte nicht geladen werden.");
    } finally {
      setLoading(false);
    }
  }

  function begin() {
    setOpen(true); setError(""); setResult(null);
    setPhrase(""); setPassword(""); setAcknowledged(false);
    void loadPreview();
  }

  function close() {
    if (working) return;
    setOpen(false); setPassword(""); setPhrase(""); setPreview(null);
    setAcknowledged(false);
  }

  async function confirm(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (working || !preview || phrase !== "Kompletter Reset" || !acknowledged || !password) return;
    if (!window.confirm("Sind Sie sicher? Alle Anwendungsdaten und hochgeladenen Bilder werden unwiderruflich gelöscht. Nur der Login und die Serverkonfiguration bleiben erhalten.")) return;
    setWorking(true); setError("");
    try {
      const response = await fetch("/api/admin/factory-reset", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ phrase, password, challenge: preview.challenge }),
      });
      if (!response.ok) throw new Error(await responseError(response));
      const outcome = await response.json() as Result;
      setResult(outcome);
      setPreview(null);
      setPassword("");
      setPhrase("");
      setAcknowledged(false);
      if (!outcome.partial) {
        // Browser storage can be disabled; this must never turn a completed reset
        // into a misleading error message.
        try { window.localStorage.clear(); } catch { /* Browser preferences unavailable. */ }
      }
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Der Reset konnte nicht bestätigt werden.");
      setPassword("");
      await loadPreview();
    } finally {
      setWorking(false);
    }
  }

  return <section className="cc-panel cc-factory-reset" aria-labelledby="factory-reset-heading">
    <h2 id="factory-reset-heading">Werkseinstellungen</h2>
    <p>Die Anwendung vollständig auf einen leeren Datenbestand zurücksetzen. Login und technische Serverkonfiguration bleiben erhalten.</p>
    <button type="button" className="cc-button cc-button-danger" onClick={begin}>Werkseinstellungen öffnen</button>
    <DashboardModal open={open} title="Werkseinstellungen – kompletter Reset"
      description="Dieser Vorgang kann nicht rückgängig gemacht werden." onClose={close}>
      <div className="cc-factory-reset-body">
        <p className="cc-message" role="alert">
          <strong>Sind Sie sicher?</strong> Gelöscht werden alle Locations, Partyreihen, DJs,
          Genres, Termine, Discovery-Funde, redaktionellen Entwürfe, Musikdaten sowie
          sämtliche hochgeladenen Flyer und Bilder aus media_data. Bestehende Backups
          werden nicht entfernt. Der Login bleibt funktionsfähig.
        </p>
        {loading && <p role="status">Reset-Vorschau wird geladen …</p>}
        {error && <p className="cc-message" role="alert">{error}</p>}
        {result ? <>
          <p className="cc-message" role="status">
            {result.partial
              ? `Datenbank wurde geleert, aber ${result.filesNotDeleted} Mediendatei(en) konnten nicht entfernt werden. Bitte den Administrator informieren und den Medienbestand prüfen.`
              : `Werkseinstellungen wiederhergestellt: ${result.deletedRecords} Datenbankzeilen und ${result.deletedFiles} Mediendateien entfernt.`}
          </p>
          <button type="button" className="cc-button cc-button-primary" onClick={() => window.location.reload()}>Ansicht neu laden</button>
        </> : preview ? <>
          <p className="cc-factory-reset-counts"><strong>{preview.recordCount}</strong> Datenbankzeilen und <strong>{preview.mediaFileCount}</strong> Dateien im Medien-Volume sind betroffen.</p>
          <p>Bitte vor dem Fortfahren prüfen, ob ein externes Backup benötigt wird. Die Rücksetzung erhält Flyway-Migrationen, Zugangsdaten, SSL und Docker-Volumes.</p>
          <form onSubmit={event => void confirm(event)} className="cc-factory-reset-form">
            <label htmlFor="factory-reset-phrase">Zur Bestätigung exakt <strong>Kompletter Reset</strong> eingeben</label>
            <input id="factory-reset-phrase" className="cc-input" type="text" spellCheck={false}
              autoComplete="off" value={phrase} onChange={event => setPhrase(event.target.value)} />
            <label htmlFor="factory-reset-password">Aktuelles Login-Passwort erneut eingeben</label>
            <input id="factory-reset-password" className="cc-input" type="password"
              autoComplete="current-password" value={password} onChange={event => setPassword(event.target.value)} />
            <label className="cc-factory-reset-check">
              <input type="checkbox" checked={acknowledged} onChange={event => setAcknowledged(event.target.checked)} />
              Ich verstehe, dass dieser Reset dauerhaft sämtliche Anwendungsdaten löscht.
            </label>
            <div className="cc-factory-reset-actions">
              <button type="button" className="cc-button" onClick={close} disabled={working}>Abbrechen</button>
              <button type="submit" className="cc-button cc-button-danger" disabled={working || phrase !== "Kompletter Reset" || !password || !acknowledged}>
                {working ? "Werkseinstellungen werden zurückgesetzt …" : "Kompletten Reset ausführen"}
              </button>
            </div>
          </form>
        </> : !loading ? <button type="button" className="cc-button" onClick={() => void loadPreview()}>Vorschau erneut laden</button> : null}
      </div>
    </DashboardModal>
  </section>;
}
