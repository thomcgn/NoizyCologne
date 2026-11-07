"use client";

import { useCallback, useEffect, useRef, useState, type PointerEvent as ReactPointerEvent } from "react";

/** One timer for the whole month; pointer movement never updates React state. */
export function useDayPreview(scope: object, enabled: boolean) {
  const [preview, setPreview] = useState<{ date: string; scope: object } | null>(null);
  const timer = useRef<number | null>(null);
  const lock = useRef<{ date: string; rect: DOMRect } | null>(null);
  const press = useRef<{ id: number; x: number; y: number } | null>(null);
  const suppress = useRef<{ date: string; until: number } | null>(null);

  const cancel = useCallback(() => {
    if (timer.current !== null) window.clearTimeout(timer.current);
    timer.current = null;
    press.current = null;
  }, []);

  function open(date: string, element: HTMLElement) {
    cancel();
    if (!enabled) return;
    lock.current = { date, rect: element.getBoundingClientRect() };
    setPreview({ date, scope });
  }

  useEffect(() => {
    function cancelPending() { cancel(); }
    function released() {
      cancel();
      if (suppress.current) suppress.current.until = Date.now() + 800;
    }
    function move(event: PointerEvent) {
      const held = press.current;
      if (held && (event.pointerId === held.id) && Math.hypot(event.clientX - held.x, event.clientY - held.y) > 8) cancel();
      const rect = lock.current?.rect;
      if (event.pointerType === "mouse" && rect && (event.clientX < rect.left || event.clientX > rect.right || event.clientY < rect.top || event.clientY > rect.bottom)) lock.current = null;
    }
    function guard(event: MouseEvent) {
      const blocked = suppress.current;
      if (!blocked || Date.now() > blocked.until) return;
      const sameDay = event.target instanceof Element && event.target.closest("[data-calendar-day]")?.getAttribute("data-calendar-day") === blocked.date;
      if (event.type === "contextmenu" ? !sameDay : event.detail === 0) return;
      event.preventDefault();
      event.stopPropagation();
    }
    function newPointer() { cancel(); suppress.current = null; }
    window.addEventListener("pointermove", move, { passive: true });
    window.addEventListener("pointerup", released);
    window.addEventListener("pointercancel", released);
    window.addEventListener("scroll", cancelPending, { capture: true, passive: true });
    window.addEventListener("blur", cancelPending);
    document.addEventListener("pointerdown", newPointer, true);
    document.addEventListener("click", guard, true);
    document.addEventListener("dblclick", guard, true);
    document.addEventListener("contextmenu", guard, true);
    return () => {
      cancel();
      window.removeEventListener("pointermove", move);
      window.removeEventListener("pointerup", released);
      window.removeEventListener("pointercancel", released);
      window.removeEventListener("scroll", cancelPending, true);
      window.removeEventListener("blur", cancelPending);
      document.removeEventListener("pointerdown", newPointer, true);
      document.removeEventListener("click", guard, true);
      document.removeEventListener("dblclick", guard, true);
      document.removeEventListener("contextmenu", guard, true);
    };
  }, [scope, cancel]);

  return {
    date: enabled && preview?.scope === scope ? preview.date : null,
    close: () => { cancel(); setPreview(null); },
    open,
    handlers(date: string, populated: boolean) {
      return {
        onPointerEnter(event: ReactPointerEvent<HTMLDivElement>) {
          if (!enabled || !populated || event.pointerType !== "mouse" || !window.matchMedia("(any-hover: hover) and (any-pointer: fine)").matches || lock.current?.date === date) return;
          cancel();
          const element = event.currentTarget;
          timer.current = window.setTimeout(() => open(date, element), 400);
        },
        onPointerLeave(event: ReactPointerEvent<HTMLDivElement>) {
          cancel();
          const rect = lock.current?.rect;
          if (rect && (event.clientX < rect.left || event.clientX > rect.right || event.clientY < rect.top || event.clientY > rect.bottom)) lock.current = null;
        },
        onPointerDown(event: ReactPointerEvent<HTMLDivElement>) {
          cancel();
          if (!enabled || !populated || event.pointerType === "mouse" || !event.isPrimary || event.button !== 0) return;
          if (event.target instanceof Element && event.target.closest("button, a, input, select, textarea")?.matches(":not([data-day-select])")) return;
          press.current = { id: event.pointerId, x: event.clientX, y: event.clientY };
          const element = event.currentTarget;
          timer.current = window.setTimeout(() => {
            suppress.current = { date, until: Number.POSITIVE_INFINITY };
            open(date, element);
          }, 550);
        },
      };
    },
  };
}
