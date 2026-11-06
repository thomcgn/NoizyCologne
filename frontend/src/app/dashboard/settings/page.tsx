import Link from "next/link";
import { DisplayPreferenceFields } from "../DisplayPreferences";
export default function SettingsPage() {
  return <main className="cc-page"><header className="cc-page-heading"><p className="cc-eyebrow">Verwaltung</p><h1>Einstellungen</h1><p className="cc-description">Deine Arbeitsoberfläche und Projektvorgaben.</p></header><div className="cc-settings-grid"><section className="cc-panel"><h2>Darstellung</h2><DisplayPreferenceFields /></section><section className="cc-panel"><h2>Projektvorgaben</h2><p>Musikgenres und Caption-Vorlage gemeinsam verwalten.</p><Link className="cc-button" href="/dashboard/settings/genres">Genres &amp; Caption-Vorlage</Link><Link className="cc-button" href="/dashboard/automatisierung">Automatisierung öffnen</Link></section></div></main>;
}
