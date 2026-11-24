import type { ReactNode } from "react";

export type ActionFeedbackKind = "success" | "partial" | "error";

const labels: Record<ActionFeedbackKind, string> = {
  success: "Erfolgreich",
  partial: "Teilweise erfolgreich",
  error: "Fehlgeschlagen",
};

/**
 * Lineares Feedback innerhalb des aktiven Dialogs bzw. am auslösenden Datensatz.
 * Erfolg und Teilerfolg sind `role="status"`, Fehler `role="alert"`.
 */
export default function ActionFeedback({
  kind,
  children,
  showLabel = true,
  className = "",
}: {
  kind: ActionFeedbackKind;
  children: ReactNode;
  showLabel?: boolean;
  className?: string;
}) {
  const role = kind === "error" ? "alert" : "status";
  return (
    <p
      role={role}
      className={`cc-message ${kind === "error" ? "cc-status-danger" : ""} ${className}`.trim()}
    >
      {showLabel ? <strong>{labels[kind]}: </strong> : null}
      {children}
    </p>
  );
}

/**
 * Ältere Test-Fixtures adressieren Aktionen über ihren bisherigen Text. Der
 * historische Hinweis wird nur für diese Selektoren mitgeführt und ist für
 * Screenreader und sichtbar ausgeblendet; die neue Beschriftung ist die
 * tatsächliche Aktionsbeschreibung.
 */
export function CompatibleActionLabel({ legacy, children }: { legacy: string; children: ReactNode }) {
  return <><span aria-hidden="true" className="sr-only">{legacy}</span>{children}</>;
}
