"use client";

import { createContext, useContext, useEffect, useMemo, useSyncExternalStore, type ReactNode } from "react";

type Theme = "dark" | "light";

interface ThemeContextValue {
  theme: Theme;
  toggleTheme: () => void;
}

const ThemeContext = createContext<ThemeContextValue | null>(null);

const themeStore = {
  getSnapshot: (): Theme => {
    const stored = window.localStorage.getItem("noizy-theme");
    return stored === "light" ? "light" : "dark";
  },
  getServerSnapshot: (): Theme => "dark",
  subscribe: (onChange: () => void) => {
    window.addEventListener("noizy-theme-change", onChange);
    return () => window.removeEventListener("noizy-theme-change", onChange);
  },
};

export function ThemeProvider({ children }: { children: ReactNode }) {
  const theme = useSyncExternalStore(
    themeStore.subscribe,
    themeStore.getSnapshot,
    themeStore.getServerSnapshot
  );

  useEffect(() => {
    document.documentElement.dataset.theme = theme;
  }, [theme]);

  const value = useMemo(
    () => ({
      theme,
      toggleTheme: () => {
        const next = theme === "dark" ? "light" : "dark";
        window.localStorage.setItem("noizy-theme", next);
        window.dispatchEvent(new Event("noizy-theme-change"));
      },
    }),
    [theme]
  );

  return <ThemeContext.Provider value={value}>{children}</ThemeContext.Provider>;
}

export function useTheme() {
  const context = useContext(ThemeContext);
  if (!context) throw new Error("useTheme muss innerhalb von ThemeProvider verwendet werden.");
  return context;
}
