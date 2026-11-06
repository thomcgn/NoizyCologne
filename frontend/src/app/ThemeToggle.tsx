"use client";

import { useTheme } from "./ThemeProvider";

export default function ThemeToggle() {
  const { theme, toggleTheme } = useTheme();
  const light = theme === "light";
  const nextLabel = theme === "graphite" ? "Midnight" : light ? "Graphit" : "Hell";

  return (
    <button
      type="button"
      onClick={toggleTheme}
      aria-label={theme === "graphite" ? "Midnight-Layout aktivieren" : light ? "Graphit-Layout aktivieren" : "Helles Layout aktivieren"}
      aria-pressed={light}
      className="theme-toggle control-button control-button-secondary"
    >
      <span aria-hidden="true">{light ? "☾" : "☼"}</span>
      <span className="ml-2 hidden sm:inline">{nextLabel}</span>
    </button>
  );
}
