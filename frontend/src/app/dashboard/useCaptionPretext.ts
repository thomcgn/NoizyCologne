"use client";

import { useSyncExternalStore } from "react";
import { DEFAULT_CAPTION_PRETEXT } from "./captionSettings";

export const CAPTION_PRETEXT_ENDPOINT = "/api/v1/editorial-config/caption-pretext";
export const CAPTION_PRETEXT_NOTICE_KEY = "noizy.caption-pretext.notice";

export type CaptionPretextConfig = { pretext: string; revision: number };

type State = {
  config: CaptionPretextConfig;
  loading: boolean;
  error: string;
};

const POLL_INTERVAL_MS = 10_000;
const INITIAL: State = {
  config: { pretext: DEFAULT_CAPTION_PRETEXT, revision: 0 },
  loading: true,
  error: "",
};

let state: State = INITIAL;
const listeners = new Set<() => void>();
let running = false;
let startedOnce = false;
let lastSuccessAt = 0;
let pollTimer: ReturnType<typeof setInterval> | null = null;
let requestId = 0;

function isConfig(value: unknown): value is CaptionPretextConfig {
  if (!value || typeof value !== "object") return false;
  const item = value as Record<string, unknown>;
  return typeof item.pretext === "string" && item.pretext.trim().length > 0
    && typeof item.revision === "number" && Number.isSafeInteger(item.revision) && item.revision > 0;
}

function publish(next: State) {
  state = next;
  for (const listener of listeners) listener();
}

function applyConfig(config: CaptionPretextConfig) {
  if (state.config.pretext === config.pretext && state.config.revision === config.revision
      && !state.loading && !state.error) return;
  publish({ config, loading: false, error: "" });
}

async function errorMessage(response: Response): Promise<string> {
  try {
    const body = await response.json() as { message?: unknown };
    if (typeof body.message === "string" && body.message.trim()) return body.message;
  } catch { /* Antwort ohne JSON: Statusmeldung verwenden. */ }
  if (response.status === 403) return "Die Änderung wurde nicht autorisiert.";
  if (response.status === 409) return "Der Pre-Text wurde inzwischen geändert. Bitte neu laden.";
  if (response.status >= 500) return "Die Caption-Konfiguration ist momentan nicht erreichbar.";
  return `Die Caption-Konfiguration antwortete mit HTTP ${response.status}.`;
}

async function refresh() {
  const currentRequest = ++requestId;
  try {
    const response = await fetch(CAPTION_PRETEXT_ENDPOINT, { credentials: "same-origin", cache: "no-store" });
    if (!response.ok) throw new Error(await errorMessage(response));
    const body: unknown = await response.json();
    if (!isConfig(body)) throw new Error("Die Caption-Konfiguration ist unvollständig.");
    if (currentRequest !== requestId) return;
    lastSuccessAt = Date.now();
    applyConfig(body);
  } catch (cause) {
    if (currentRequest !== requestId) return;
    publish({ ...state, loading: false, error: cause instanceof Error ? cause.message : "Pre-Text konnte nicht geladen werden." });
  }
}

/** Best-effort cross-tab hint. The API answer stays authoritative; a blocked storage never signals success. */
function notifyOtherTabs(config: CaptionPretextConfig) {
  try {
    const value = JSON.stringify(config);
    window.localStorage.setItem(CAPTION_PRETEXT_NOTICE_KEY, value);
    window.dispatchEvent(new StorageEvent("storage", {
      key: CAPTION_PRETEXT_NOTICE_KEY,
      newValue: value,
      storageArea: window.localStorage,
    }));
  } catch { /* localStorage ist nur ein Hinweis-Kanal, keine Erfolgsbedingung. */ }
}

function onStorage(event: StorageEvent) {
  if (event.key !== CAPTION_PRETEXT_NOTICE_KEY || !event.newValue) return;
  try {
    const hint: unknown = JSON.parse(event.newValue);
    if (isConfig(hint) && hint.revision > state.config.revision) applyConfig(hint);
  } catch { /* Ungültige Hinweise werden verworfen. */ }
  void refresh();
}

function onVisible() {
  if (!document.hidden) void refresh();
}

function subscribe(listener: () => void) {
  listeners.add(listener);
  if (!running) {
    running = true;
    window.addEventListener("storage", onStorage);
    window.addEventListener("focus", onVisible);
    document.addEventListener("visibilitychange", onVisible);
    pollTimer = setInterval(() => { if (!document.hidden) void refresh(); }, POLL_INTERVAL_MS);
    // One load per SPA session; later mounts rely on polling, focus/visibility and storage hints.
    if (!startedOnce) {
      startedOnce = true;
      void refresh();
    } else if (state.config.revision > 0 && Date.now() - lastSuccessAt > 30_000) {
      void refresh();
    }
  }
  return () => {
    listeners.delete(listener);
    if (listeners.size > 0 || !running) return;
    running = false;
    if (pollTimer !== null) clearInterval(pollTimer);
    pollTimer = null;
    window.removeEventListener("storage", onStorage);
    window.removeEventListener("focus", onVisible);
    document.removeEventListener("visibilitychange", onVisible);
  };
}

function getSnapshot() { return state; }
function getServerSnapshot(): State { return INITIAL; }

export async function saveCaptionPretext(input: string): Promise<CaptionPretextConfig> {
  const response = await fetch(CAPTION_PRETEXT_ENDPOINT, {
    method: "PUT",
    credentials: "same-origin",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({
      pretext: input,
      ...(state.config.revision > 0 ? { expectedRevision: state.config.revision } : {}),
    }),
  });
  if (!response.ok) throw new Error(await errorMessage(response));
  const body: unknown = await response.json();
  if (!isConfig(body)) throw new Error("Der gespeicherte Pre-Text wurde nicht vollständig bestätigt.");
  applyConfig(body);
  notifyOtherTabs(body);
  return body;
}

export function useCaptionPretext() {
  const snapshot = useSyncExternalStore(subscribe, getSnapshot, getServerSnapshot);
  return {
    pretext: snapshot.config.pretext,
    revision: snapshot.config.revision,
    loading: snapshot.loading,
    error: snapshot.error,
    save: saveCaptionPretext,
  };
}
