"use client";
import { useEffect, useEffectEvent, useRef } from "react";

export function recordLink(route: string, id: string) {
  return `${route}#record=${encodeURIComponent(id)}`;
}

// Resolve after catalogue loading as well as after navigation within the same page.
export function useRecordLink<T extends {id: string}>(items: T[], onOpen?: (item: T) => void, disabled = false) {
  const opened = useRef("");
  const resolve = useEffectEvent(() => {
    if (disabled || !onOpen || opened.current === window.location.hash) return;
    const hash = window.location.hash;
    if (!hash.startsWith("#record=")) return;
    let id: string;
    try { id = decodeURIComponent(hash.slice(8)); } catch { return; }
    const item = items.find(item => item.id === id);
    if (!item) return;
    opened.current = hash;
    onOpen(item);
  });
  useEffect(() => { resolve(); }, [items, disabled]);
  useEffect(() => {
    const changed = () => { opened.current = ""; resolve(); };
    window.addEventListener("hashchange", changed);
    return () => window.removeEventListener("hashchange", changed);
  }, []);
}
