"use client";

import { useTheme } from "./ThemeProvider";

export default function ThemeToggle() {
  const { theme, toggleTheme } = useTheme();
  const light = theme === "light";

  return (
    <button
      type="button"
      onClick={toggleTheme}
      aria-label={light ? "Dunkles Layout aktivieren" : "Helles Layout aktivieren"}
      aria-pressed={light}
      className="theme-toggle control-button control-button-secondary"
    >
      <span aria-hidden="true">{light ? "☾" : "☼"}</span>
      <span className="ml-2 hidden sm:inline">{light ? "Dunkel" : "Hell"}</span>
    </button>
  );
}
