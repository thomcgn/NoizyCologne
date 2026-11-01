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
      className="theme-toggle rounded-lg border border-white/15 px-3 py-2 text-sm text-neutral-300 transition hover:border-white/40 hover:text-white focus:outline-none focus:ring-2 focus:ring-white/30"
    >
      <span aria-hidden="true">{light ? "☾" : "☼"}</span>
      <span className="ml-2 hidden sm:inline">{light ? "Dunkel" : "Hell"}</span>
    </button>
  );
}
