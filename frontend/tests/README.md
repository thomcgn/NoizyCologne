# Modal-Fokusregression

Ausführen im Verzeichnis `frontend`: `npm test`.

Die Tests rendern React unter Strict Mode in jsdom. TypeScript wird mit dem bereits
vorhandenen Compiler für den Testlauf übersetzt. Die einzige zusätzliche
Testabhängigkeit ist die exakt versionierte Entwicklungsabhängigkeit jsdom.

Geprüft werden:

- Die tatsächlichen Anlegen- und Bearbeiten-Formulare für Genres, Partyreihen,
  Locations, DJs/Akteure und Kalendereinträge: mehrere Zeichen in einem weiteren
  Textfeld, Checkboxen, Selects, Tab-/Shift-Tab-Grenzen, Escape, Abbrechen und
  Rückgabe des Fokus an den jeweiligen Auslöser.
- Die tatsächlichen Dialoge für Vorschlagsprüfung und Quellen-/Extraktionsprofile:
  Texteingaben, Escape und Fokus-Rückgabe.
- Kalenderinformationen: Fokusfalle, Escape, Rückgabe und Wechsel zum Editor.
- Beide zentral unterstützten Layouts: aktuelle Inline-Callbacks nach State-Updates,
  Speichern, Validierungsfehler, erneutes Öffnen und Abbrechen.
- Schließen durch Unmount, geschlossene Dialoge, deaktivierte/versteckte Felder,
  eingeklappte Details und leere Dialoge.
- Theme-Attribute dark/light und Resize-Ereignisse bei 390 und 1280 Pixeln in den
  tatsächlichen Anlegen-/Bearbeiten-Formularen verändern den Fokus nicht.
- Die primären Formularbuttons für Genres, Partyreihen, Locations, Akteure und
  Kalender behalten beim Anlegen/Bearbeiten in beiden Themes mindestens 4,5:1
  Textkontrast. Dafür werden die echten globalen Theme-Regeln auf zwei im Test
  bereitgestellte Tailwind-Farbbaselines angewendet; jsdom kompiliert kein Tailwind.

Katalogdaten und HTTP-Antworten sind Testdaten; es werden keine echten Daten
verändert. Speichern/Validierung wird am zentralen Testformular geprüft, nicht
als Backend-Integrationstest aller Fachformulare. jsdom bietet keine reale
Layout-Engine: visuelle Light-/Dark-Mode-Abnahme, mobile Bildschirmtastaturen und
native Tab-Navigation innerhalb der Feldfolge benötigen weiterhin einen echten
Browser. Die Tests prüfen die durch den Modalcode behandelten Tab-Grenzen.

Der Quellcode-Audit umfasst sämtliche 28 TS/TSX-Dateien in `src`: keine weiteren
`autoFocus`-Stellen, keine konkurrierende `.focus()`-Logik und keine innerhalb von
Renderfunktionen definierten Komponenten. Der Kalender-Key aus Datum und
Datensatz-ID bleibt während normaler Eingaben stabil. Die zuvor eigenständigen
Kalenderdialoge verwenden jetzt die zentrale Fokusverwaltung.
