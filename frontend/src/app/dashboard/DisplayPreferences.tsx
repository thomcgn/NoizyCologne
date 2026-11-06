"use client";

import { useState } from "react";
import { useTheme, type Theme, type Density } from "../ThemeProvider";
import DashboardModal from "./DashboardModal";

export function DisplayPreferenceFields() {
  const { theme, density, setTheme, setDensity } = useTheme();
  const [notice, setNotice] = useState("");

  function report(persisted: boolean) {
    setNotice(persisted ? "Darstellung in diesem Browser gespeichert." : "Browserspeicher nicht verfügbar. Die Auswahl gilt bis zum Neuladen.");
  }

  return (
      <div className="display-preferences">
        <div><label htmlFor="display-theme">Theme</label><select id="display-theme" value={theme} onChange={event => report(setTheme(event.target.value as Theme))}>
          <option value="graphite">Graphit</option><option value="midnight">Midnight</option><option value="light">Hell</option>
        </select></div>
        <div><label htmlFor="display-density">Informationsdichte</label><select id="display-density" value={density} onChange={event => report(setDensity(event.target.value as Density))}>
          <option value="compact">Kompakt</option><option value="comfortable">Komfortabel</option>
        </select></div>
        <p>Die Auswahl wird sofort angewendet. Kompakt zeigt Inhalte mit geringeren Abständen; auf Touchscreens bleiben die Bedienelemente gut erreichbar.</p>
        <p role="status">{notice}</p>
      </div>
  );
}

export default function DisplayPreferences() {
  const [open, setOpen] = useState(false);
  return <>
    <button type="button" className="control-button control-button-secondary" onClick={() => setOpen(true)}>Darstellung</button>
    <DashboardModal open={open} onClose={() => setOpen(false)} title="Darstellung" description="Theme und Informationsdichte gelten lokal in diesem Browser.">
      <DisplayPreferenceFields />
      <button type="button" className="cc-button mt-4" onClick={() => setOpen(false)}>Schließen</button>
    </DashboardModal>
  </>;
}
