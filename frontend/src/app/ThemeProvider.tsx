"use client";

import { createContext, useContext, useLayoutEffect, useMemo, useSyncExternalStore, type ReactNode } from "react";

import { themes, densities, defaultTheme, defaultDensity, type Theme, type Density } from "./uiPreferences";
export type { Theme, Density } from "./uiPreferences";

interface ThemeContextValue {
  theme: Theme;
  density: Density;
  setTheme: (theme: Theme) => boolean;
  setDensity: (density: Density) => boolean;
  toggleTheme: () => void;
}

const ThemeContext = createContext<ThemeContextValue | null>(null);

export function preferenceStore<T extends string>(key: string, values: readonly T[], defaultValue: T, eventName: string, normalize: (value: string | null) => string | null = value => value) {
  // Keep the current selection usable when browser storage is unavailable/full.
  let sessionValue: T | null = null;
  return {
    getSnapshot: (): T => {
      if (sessionValue !== null) return sessionValue;
      try {
        const stored = normalize(window.localStorage.getItem(key));
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

const themeStore = preferenceStore<Theme>("noizy-theme", themes, defaultTheme, "noizy-theme-change", value => value === "dark" ? "midnight" : value);
const densityStore = preferenceStore<Density>("noizy-density", densities, defaultDensity, "noizy-density-change");

export function ThemeProvider({ children }: { children: ReactNode }) {
  const theme = useSyncExternalStore(themeStore.subscribe, themeStore.getSnapshot, themeStore.getServerSnapshot);
  const density = useSyncExternalStore(densityStore.subscribe, densityStore.getSnapshot, densityStore.getServerSnapshot);

  useLayoutEffect(() => {
    document.documentElement.dataset.theme = themeStore.getSnapshot();
    document.documentElement.dataset.density = densityStore.getSnapshot();
  }, [theme, density]);

  const value = useMemo(() => ({
    theme, density,
    setTheme: themeStore.set,
    setDensity: densityStore.set,
    toggleTheme: () => themeStore.set(theme === "graphite" ? "midnight" : theme === "midnight" ? "light" : "graphite"),
  }), [theme, density]);

  return <ThemeContext.Provider value={value}>{children}</ThemeContext.Provider>;
}

export function useTheme() {
  const context = useContext(ThemeContext);
  if (!context) throw new Error("useTheme muss innerhalb von ThemeProvider verwendet werden.");
  return context;
}
