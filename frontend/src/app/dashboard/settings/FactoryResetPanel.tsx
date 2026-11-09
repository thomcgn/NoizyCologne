"use client";

import { useEffect, useRef, useState, type FormEvent } from "react";
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
  message?: string | null;
};

function object(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
function count(value: unknown): value is number {
  return typeof value === "number" && Number.isSafeInteger(value) && value >= 0;
}
function isPreview(value: unknown): value is Preview {
  return object(value) && count(value.recordCount) && count(value.mediaFileCount)
    && typeof value.challenge === "string" && !!value.challenge.trim() && Array.isArray(value.tables);
}
function isResult(value: unknown): value is Result {
  return object(value) && count(value.deletedRecords) && count(value.deletedFiles) && count(value.filesNotDeleted)
    && typeof value.partial === "boolean" && (value.partial || value.filesNotDeleted === 0)
    && (value.message == null || typeof value.message === "string");
}
async function responseError(response: Response): Promise<string> {
  const body: unknown = await response.json().catch(() => null);
  return object(body) && typeof body.error === "string" ? body.error : `Fehler HTTP ${response.status}`;
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
  const [outcomeUnknown, setOutcomeUnknown] = useState(false);
  const unknown = useRef(false);
  const inFlight = useRef(false);
  const isOpen = useRef(false);
  const previewGeneration = useRef(0);
  const previewController = useRef<AbortController | null>(null);

  useEffect(() => () => {
    isOpen.current = false;
    previewGeneration.current++;
    previewController.current?.abort();
  }, []);

  function clearConfirmation() {
    setPhrase(""); setPassword(""); setAcknowledged(false);
  }
  async function loadPreview(preserveError = false) {
    if (unknown.current || !isOpen.current) return;
    previewController.current?.abort();
    const controller = new AbortController();
    previewController.current = controller;
    const generation = ++previewGeneration.current;
    setLoading(true); setPreview(null); clearConfirmation();
    if (!preserveError) setError("");
    try {
      const response = await fetch("/api/admin/factory-reset", { cache: "no-store", signal: controller.signal });
      if (!response.ok) throw new Error(await responseError(response));
      const next: unknown = await response.json();
      if (!isPreview(next)) throw new Error("Die Reset-Vorschau ist unvollständig. Bitte erneut laden.");
      if (generation !== previewGeneration.current || !isOpen.current || controller.signal.aborted) return;
      setPreview(next);
    } catch (cause) {
      if (generation !== previewGeneration.current || !isOpen.current || controller.signal.aborted) return;
      setError(cause instanceof Error ? cause.message : "Die Vorschau konnte nicht geladen werden.");
    } finally {
      if (generation === previewGeneration.current) setLoading(false);
    }
  }
  function begin() {
    if (inFlight.current) return;
    isOpen.current = true; setOpen(true); clearConfirmation();
    // An ambiguous POST remains blocked even if the modal is closed and reopened.
    if (unknown.current) return;
    setError(""); setResult(null);
    void loadPreview();
  }
  function close() {
    if (inFlight.current) return;
    isOpen.current = false; previewGeneration.current++; previewController.current?.abort();
    setOpen(false); setLoading(false); setPreview(null); clearConfirmation();
  }
  function unconfirmed(message: string) {
    unknown.current = true;
    setOutcomeUnknown(true); setPreview(null); setResult(null); setError(message); clearConfirmation();
  }
  async function confirm(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (inFlight.current || unknown.current || !preview || phrase !== "Kompletter Reset" || !acknowledged || !password) return;
    inFlight.current = true;
    try {
      if (!window.confirm("Sind Sie sicher? Alle Anwendungsdaten und hochgeladenen Bilder werden unwiderruflich gelöscht. Nur der Login und die Serverkonfiguration bleiben erhalten.")) return;
      setWorking(true); setError("");
      const response = await fetch("/api/admin/factory-reset", {
        method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ phrase, password, challenge: preview.challenge }),
      });
      const outcome: unknown = await response.json();
      if (object(outcome) && outcome.outcomeUnknown === true) {
        unconfirmed(typeof outcome.error === "string" ? outcome.error : "Der Server hat keinen eindeutigen Reset-Ausgang bestätigt.");
        return;
      }
      if (!response.ok) {
        const message = object(outcome) && typeof outcome.error === "string" ? outcome.error : `Fehler HTTP ${response.status}`;
        if ([400, 401, 409].includes(response.status) && object(outcome) && typeof outcome.error === "string") {
          setError(message); clearConfirmation();
          await loadPreview(true);
        } else unconfirmed(message);
        return;
      }
      if (!isResult(outcome)) {
        unconfirmed("Die Serverantwort enthält kein verlässlich bestätigtes Reset-Ergebnis.");
        return;
      }
      setResult(outcome); setPreview(null); clearConfirmation();
      if (!outcome.partial) {
        try { window.localStorage.clear(); } catch { /* Completed reset remains confirmed without browser storage. */ }
      }
    } catch {
      unconfirmed("Die Antwort auf den Reset ist ausgeblieben oder konnte nicht ausgewertet werden.");
    } finally {
      inFlight.current = false; setWorking(false);
    }
  }

  const resultMessage = result?.message?.trim() || (result?.partial
    ? result.filesNotDeleted > 0
      ? `Datenbankreset ist bestätigt; ${result.filesNotDeleted} Mediendatei(en) konnten nicht entfernt werden. Bitte den Administrator informieren und den Medienbestand prüfen.`
      : "Der Datenbankreset ist bestätigt; der technische Abschluss ist noch nicht vollständig bestätigt. Bitte den Administrator informieren."
    : result ? `Werkseinstellungen wiederhergestellt: ${result.deletedRecords} Datenbankzeilen und ${result.deletedFiles} Mediendateien entfernt.` : "");

  return <section className="cc-panel cc-factory-reset" aria-labelledby="factory-reset-heading">
    <h2 id="factory-reset-heading">Werkseinstellungen</h2>
    <p>Die Anwendung vollständig auf einen leeren Datenbestand zurücksetzen. Login und technische Serverkonfiguration bleiben erhalten.</p>
    <button type="button" className="cc-button cc-button-danger" disabled={working} onClick={begin}>Werkseinstellungen öffnen</button>
    <DashboardModal open={open} busy={working} title="Werkseinstellungen – kompletter Reset"
      description="Dieser Vorgang kann nicht rückgängig gemacht werden." onClose={close}>
      <div className="cc-factory-reset-body" aria-busy={working || loading}>
        <p className="cc-message" role="alert">
          <strong>Sind Sie sicher?</strong> Gelöscht werden alle Locations, Partyreihen, DJs,
          Genres, Termine, Discovery-Funde, redaktionellen Entwürfe, Musikdaten sowie
          sämtliche hochgeladenen Flyer und Bilder aus media_data. Bestehende Backups
          werden nicht entfernt. Der Login bleibt funktionsfähig.
        </p>
        {loading && <p role="status">Reset-Vorschau wird geladen …</p>}
        {error && <p className="cc-message" role="alert">{error}</p>}
        {outcomeUnknown ? <>
          <p className="cc-message" role="alert">Reset-Ausgang unbestätigt. Der Server kann bereits Daten gelöscht haben. Bitte den Administrator den Zustand prüfen lassen und keinen weiteren Reset ausführen.</p>
          <button type="button" className="cc-button cc-button-primary" onClick={() => window.location.reload()}>Ansicht neu laden</button>
        </> : result ? <>
          <p className="cc-message" role="status">{resultMessage}</p>
          <button type="button" className="cc-button cc-button-primary" onClick={() => window.location.reload()}>Ansicht neu laden</button>
        </> : preview ? <>
          <p className="cc-factory-reset-counts"><strong>{preview.recordCount}</strong> Datenbankzeilen und <strong>{preview.mediaFileCount}</strong> Dateien im Medien-Volume sind betroffen.</p>
          <p>Bitte vor dem Fortfahren prüfen, ob ein externes Backup benötigt wird. Die Rücksetzung erhält Flyway-Migrationen, Zugangsdaten, SSL und Docker-Volumes.</p>
          <form onSubmit={event => void confirm(event)} className="cc-factory-reset-form">
            <fieldset disabled={working || loading} className="cc-factory-reset-form min-w-0">
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
            </fieldset>
          </form>
        </> : !loading ? <button type="button" className="cc-button" onClick={() => void loadPreview()}>Vorschau erneut laden</button> : null}
      </div>
    </DashboardModal>
  </section>;
}
