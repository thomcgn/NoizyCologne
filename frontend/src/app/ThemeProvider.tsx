"use client";

import { createContext, useContext, useEffect, useMemo, useSyncExternalStore, type ReactNode } from "react";

export type Theme = "graphite" | "dark" | "light";
export type Density = "compact" | "comfortable";

interface ThemeContextValue {
  theme: Theme;
  density: Density;
  setTheme: (theme: Theme) => boolean;
  setDensity: (density: Density) => boolean;
  toggleTheme: () => void;
}

const ThemeContext = createContext<ThemeContextValue | null>(null);

function preferenceStore<T extends string>(key: string, values: readonly T[], defaultValue: T, eventName: string) {
  // Keep the current selection usable when browser storage is unavailable/full.
  let sessionValue: T | null = null;
  return {
    getSnapshot: (): T => {
      if (sessionValue !== null) return sessionValue;
      try {
        const stored = window.localStorage.getItem(key);
        return values.find(value => value === stored) ?? defaultValue;
      } catch { return defaultValue; }
    },
    getServerSnapshot: (): T => defaultValue,
    subscribe: (onChange: () => void) => {
      function onStorage(event: StorageEvent) {
        try { if (event.storageArea !== window.localStorage) return; } catch { return; }
        if (event.key !== key && event.key !== null) return;
        sessionValue = null;
        onChange();
      }
      window.addEventListener(eventName, onChange);
      window.addEventListener("storage", onStorage);
      return () => {
        window.removeEventListener(eventName, onChange);
        window.removeEventListener("storage", onStorage);
      };
    },
    set: (value: T): boolean => {
      let persisted = false;
      try { window.localStorage.setItem(key, value); sessionValue = null; persisted = true; }
      catch { sessionValue = value; }
      window.dispatchEvent(new Event(eventName));
      return persisted;
    },
  };
}

const themeStore = preferenceStore<Theme>("noizy-theme", ["graphite", "dark", "light"], "graphite", "noizy-theme-change");
const densityStore = preferenceStore<Density>("noizy-density", ["compact", "comfortable"], "compact", "noizy-density-change");

export function ThemeProvider({ children }: { children: ReactNode }) {
  const theme = useSyncExternalStore(themeStore.subscribe, themeStore.getSnapshot, themeStore.getServerSnapshot);
  const density = useSyncExternalStore(densityStore.subscribe, densityStore.getSnapshot, densityStore.getServerSnapshot);

  useEffect(() => {
    document.documentElement.dataset.theme = theme;
    document.documentElement.dataset.density = density;
  }, [theme, density]);

  const value = useMemo(() => ({
    theme, density,
    setTheme: themeStore.set,
    setDensity: densityStore.set,
    toggleTheme: () => themeStore.set(theme === "graphite" ? "light" : theme === "light" ? "dark" : "graphite"),
  }), [theme, density]);

  return <ThemeContext.Provider value={value}>{children}</ThemeContext.Provider>;
}

export function useTheme() {
  const context = useContext(ThemeContext);
  if (!context) throw new Error("useTheme muss innerhalb von ThemeProvider verwendet werden.");
  return context;
}
