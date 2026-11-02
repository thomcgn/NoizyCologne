"use client";

import { useEffect, useRef, type ReactNode } from "react";

type DashboardModalProps = {
  title: string;
  description?: string;
  open: boolean;
  onClose: () => void;
  children: ReactNode;
};

export default function DashboardModal({
  title,
  description,
  open,
  onClose,
  children,
}: DashboardModalProps) {
  const dialogRef = useRef<HTMLDivElement>(null);
  const triggerRef = useRef<HTMLElement | null>(null);

  useEffect(() => {
    if (!open) {
      triggerRef.current?.focus();
      return;
    }

    triggerRef.current = document.activeElement instanceof HTMLElement
      ? document.activeElement
      : null;

    const dialog = dialogRef.current;
    const firstControl = dialog?.querySelector<HTMLElement>(
      "input, select, textarea, button:not([data-modal-close])"
    );
    firstControl?.focus();

    function handleKeyDown(event: KeyboardEvent) {
      if (event.key === "Escape") {
        event.preventDefault();
        onClose();
        return;
      }

      if (event.key !== "Tab" || !dialog) return;
      const focusable = Array.from(
        dialog.querySelectorAll<HTMLElement>(
          "a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex='-1'])"
        )
      );
      if (focusable.length === 0) return;
      const first = focusable[0];
      const last = focusable[focusable.length - 1];
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first.focus();
      }
    }

    document.addEventListener("keydown", handleKeyDown);
    return () => document.removeEventListener("keydown", handleKeyDown);
  }, [open, onClose]);

  if (!open) return null;

  return (
    <div className="dashboard-modal-backdrop" role="presentation">
      <div
        ref={dialogRef}
        className="dashboard-modal"
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
            onClick={onClose}
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
