"use client";

import { useEffect, useEffectEvent, useRef, type ReactNode, type RefObject } from "react";

type DashboardModalProps = {
  title: string;
  description?: string;
  open: boolean;
  onClose: () => void;
  children: ReactNode;
  variant?: "dialog" | "drawer";
  protectChanges?: boolean;
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
      if (trigger?.isConnected) trigger.focus();
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
}: DashboardModalProps) {
  const dialogRef = useRef<HTMLDivElement>(null);
  const dirty = useRef(false);
  useEffect(() => {dirty.current = false;}, [open]);
  useEffect(() => {
    if (!open || !protectChanges) return;
    function beforeUnload(event: BeforeUnloadEvent) {
      if (dirty.current) {event.preventDefault(); event.returnValue = "";}
    }
    window.addEventListener("beforeunload", beforeUnload);
    return () => window.removeEventListener("beforeunload", beforeUnload);
  }, [open, protectChanges]);
  function requestClose() {
    if (protectChanges && dirty.current && !window.confirm("Ungespeicherte Änderungen verwerfen?")) return;
    dirty.current = false;
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
        onChangeCapture={() => {dirty.current = true;}}
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
