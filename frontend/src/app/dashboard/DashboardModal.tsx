"use client";

import {
  useCallback,
  useEffect,
  useEffectEvent,
  useMemo,
  useRef,
  type ChangeEvent,
  type ReactNode,
  type RefObject,
} from "react";

/**
 * Expliziter Dirty-Vertrag für Formulare: `isDirty()` beantwortet die Frage,
 * `setClean()` markiert genau den aktuell bestätigten Stand als sauber.
 * `signature` serialisiert die relevanten Formularwerte; durch den Vergleich
 * mit der Bestätigungsbasis wird auch eine Rückänderung erkannt.
 */
export type DirtyGuard = {
  isDirty: () => boolean;
  setClean: () => void;
};

export function useDirtyGuard(signature: string, resetKey?: string, enabled = true): DirtyGuard {
  const current = useRef(signature);
  const confirmed = useRef(signature);

  useEffect(() => {
    current.current = signature;
  }, [signature]);

  const setClean = useCallback(() => {
    confirmed.current = current.current;
  }, []);

  // Ein neuer Datensatz oder ein erneut geöffneter Dialog beginnt sauber.
  useEffect(() => {
    confirmed.current = current.current;
  }, [resetKey, setClean]);

  const isDirty = useCallback(
    () => enabled && confirmed.current !== current.current,
    [enabled],
  );

  return useMemo(() => ({ isDirty, setClean }), [isDirty, setClean]);
}

function isFormControl(element: Element): element is HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement {
  const tag = element.tagName;
  return tag === "INPUT" || tag === "SELECT" || tag === "TEXTAREA";
}

function controlValue(element: HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement): string {
  const input = element as HTMLInputElement;
  if (input.type === "checkbox" || input.type === "radio") {
    return input.checked ? "checked" : "unchecked";
  }
  return input.value;
}

/**
 * Rückwärtskompatible Variante für Dialoge ohne eigenen Formularvertrag.
 * Auch hier zählt nur eine tatsächliche Abweichung vom Eröffnungsstand.
 */
export function useDomDirtyGuard(
  active: boolean,
  dialogRef: RefObject<HTMLDivElement | null>,
): DirtyGuard & { handleChange: (event: ChangeEvent<HTMLElement>) => void } {
  const baselines = useRef(new WeakMap<Element, string>());
  const changed = useRef(new Set<Element>());

  const setClean = useCallback(() => {
    changed.current.clear();
    const dialog = dialogRef.current;
    if (!dialog) return;
    baselines.current = new WeakMap();
    for (const control of dialog.querySelectorAll("input, select, textarea")) {
      if (isFormControl(control)) baselines.current.set(control, controlValue(control));
    }
  }, [dialogRef]);

  useEffect(() => {
    if (!active) return;
    setClean();
  }, [active, setClean]);

  const handleChange = useCallback((event: ChangeEvent<HTMLElement>) => {
    if (!active || !isFormControl(event.target)) return;
    const control = event.target;
    if (!baselines.current.has(control)) {
      // Nach dem Öffnen nachgerenderte Felder starten mit ihrem aktuellen Wert.
      baselines.current.set(control, controlValue(control));
      return;
    }
    if (controlValue(control) === baselines.current.get(control)) {
      changed.current.delete(control);
    } else {
      changed.current.add(control);
    }
  }, [active]);

  const isDirty = useCallback(() => active && changed.current.size > 0, [active]);

  return useMemo(() => ({ isDirty, setClean, handleChange }), [isDirty, setClean, handleChange]);
}

type DashboardModalProps = {
  title: string;
  description?: string;
  open: boolean;
  onClose: () => void;
  children: ReactNode;
  variant?: "dialog" | "drawer";
  protectChanges?: boolean;
  /** Expliziter Vertrag eines Formulars; ohne ihn gilt der DOM-Rückfall. */
  dirtyGuard?: DirtyGuard;
  busy?: boolean;
};

// Shared by standard modals and the calendar's custom dialog layouts.
export function useModalFocus(
  open: boolean,
  dialogRef: RefObject<HTMLDivElement | null>,
  onClose: () => void,
) {
  const close = useEffectEvent(onClose);

  useEffect(() => {
    if (!open) return;
    const currentDialog = dialogRef.current;
    if (!currentDialog) return;
    const dialog = currentDialog;
    const trigger = document.activeElement instanceof HTMLElement
      ? document.activeElement
      : null;

    function controls() {
      return Array.from(dialog.querySelectorAll<HTMLElement>(
        "a[href], button, input, select, textarea, summary, [tabindex]"
      )).filter((element) => {
        if (element.tabIndex < 0 || element.matches(":disabled, input[type='hidden']")) return false;
        if (element.closest("[hidden], [inert]")) return false;
        for (let parent = element.parentElement; parent && parent !== dialog; parent = parent.parentElement) {
          const parentStyle = getComputedStyle(parent);
          if (parentStyle.display === "none" || parentStyle.visibility === "hidden") return false;
          if (parent instanceof HTMLDetailsElement && !parent.open &&
              !parent.querySelector("summary")?.contains(element)) return false;
        }
        const style = getComputedStyle(element);
        return style.display !== "none" && style.visibility !== "hidden";
      });
    }

    const focusable = controls();
    const initial = focusable.find((element) =>
      element.matches("input, select, textarea, button:not([data-modal-close])")
    );
    (initial ?? focusable[0] ?? dialog).focus();

    function handleKeyDown(event: KeyboardEvent) {
      if (event.key === "Escape") {
        event.preventDefault();
        close();
        return;
      }
      if (event.key !== "Tab") return;
      const focusable = controls();
      const first = focusable[0];
      const last = focusable[focusable.length - 1];
      if (!first || !last) {
        event.preventDefault();
        dialog.focus();
      } else if (event.shiftKey && (document.activeElement === first || !dialog.contains(document.activeElement))) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && (document.activeElement === last || document.activeElement === dialog || !dialog.contains(document.activeElement))) {
        event.preventDefault();
        first.focus();
      }
    }

    document.addEventListener("keydown", handleKeyDown);
    return () => {
      document.removeEventListener("keydown", handleKeyDown);
      if (trigger?.isConnected) {
        trigger.focus();
        // Pending state can enable the opener in the following parent update.
        if (document.activeElement !== trigger) window.setTimeout(() => {
          if (trigger.isConnected && document.activeElement === document.body &&
              !document.querySelector('[role="dialog"]')) trigger.focus();
        }, 0);
      }
    };
  }, [open, dialogRef]);
}

export default function DashboardModal({
  title,
  description,
  open,
  onClose,
  children,
  variant = "dialog",
  protectChanges = false,
  dirtyGuard,
  busy = false,
}: DashboardModalProps) {
  const dialogRef = useRef<HTMLDivElement>(null);
  const fallbackGuard = useDomDirtyGuard(open && protectChanges && !dirtyGuard, dialogRef);
  const guard = dirtyGuard ?? fallbackGuard;
  const guardRef = useRef(guard);
  useEffect(() => {
    guardRef.current = guard;
  }, [guard]);
  useEffect(() => () => {guardRef.current.setClean();}, [open]);
  useEffect(() => {
    if (!open || !protectChanges) return;
    function beforeUnload(event: BeforeUnloadEvent) {
      if (guardRef.current.isDirty()) {event.preventDefault(); event.returnValue = "";}
    }
    window.addEventListener("beforeunload", beforeUnload);
    return () => window.removeEventListener("beforeunload", beforeUnload);
  }, [open, protectChanges]);
  function requestClose() {
    if (busy) return;
    if (protectChanges && guard.isDirty() && !window.confirm("Ungespeicherte Änderungen verwerfen?")) return;
    guard.setClean();
    onClose();
  }
  useModalFocus(open, dialogRef, requestClose);

  if (!open) return null;

  return (
    <div className={`dashboard-modal-backdrop ${variant === "drawer" ? "cc-drawer-backdrop" : ""}`} role="presentation">
      <div
        ref={dialogRef}
        tabIndex={-1}
        className={`dashboard-modal ${variant === "drawer" ? "cc-drawer" : ""}`}
        onChangeCapture={dirtyGuard ? undefined : fallbackGuard.handleChange}
        onClickCapture={event => {
          if (event.target instanceof HTMLElement && event.target.closest("[data-modal-dismiss]")) {
            event.preventDefault();
            event.stopPropagation();
            requestClose();
          }
        }}
        role="dialog"
        aria-modal="true"
        aria-labelledby="dashboard-modal-title"
        aria-describedby={description ? "dashboard-modal-description" : undefined}
      >
        <header className="dashboard-modal-header">
          <div>
            <h2 id="dashboard-modal-title" className="dashboard-modal-title">
              {title}
            </h2>
            {description ? (
              <p id="dashboard-modal-description" className="dashboard-modal-description">
                {description}
              </p>
            ) : null}
          </div>
          <button
            type="button"
            data-modal-close
            disabled={busy}
            className="icon-button"
            onClick={requestClose}
            aria-label="Dialog schließen"
          >
            ×
          </button>
        </header>
        <div className="dashboard-modal-content">{children}</div>
      </div>
    </div>
  );
}
