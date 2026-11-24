"use client";

import { useEffect, useRef } from "react";

export const DISCOVERY_POLL_INTERVAL_MS = 10_000;

type PollListener = () => void;

// Discovery overview, history and change panels share one ticker. A hidden tab
// never polls; becoming visible again refreshes all subscribers once.
const listeners = new Set<PollListener>();
let intervalId: number | null = null;
let visibilityListenerActive = false;

export function isDocumentVisible(): boolean {
  return typeof document === "undefined" || document.visibilityState !== "hidden";
}

function notifyVisibleListeners(): void {
  if (!isDocumentVisible()) return;
  for (const listener of [...listeners]) listener();
}

function handleVisibilityChange(): void {
  if (isDocumentVisible()) notifyVisibleListeners();
}

function ensureTicker(): void {
  if (intervalId !== null) return;
  intervalId = window.setInterval(notifyVisibleListeners, DISCOVERY_POLL_INTERVAL_MS);
  if (!visibilityListenerActive) {
    document.addEventListener("visibilitychange", handleVisibilityChange);
    visibilityListenerActive = true;
  }
}

function releaseTicker(): void {
  if (listeners.size > 0 || intervalId === null) return;
  window.clearInterval(intervalId);
  intervalId = null;
  document.removeEventListener("visibilitychange", handleVisibilityChange);
  visibilityListenerActive = false;
}

export function useDiscoveryPolling(callback: PollListener): void {
  const latest = useRef(callback);
  useEffect(() => {
    latest.current = callback;
  }, [callback]);

  useEffect(() => {
    const listener: PollListener = () => latest.current();
    listeners.add(listener);
    ensureTicker();
    return () => {
      listeners.delete(listener);
      releaseTicker();
    };
  }, []);
}
