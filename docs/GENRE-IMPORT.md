# Genre-Katalog importieren und Basisgenres wiederherstellen

Unter **Einstellungen → Genres** lädt „Genres importieren“ JSON oder die bereitgestellte
CSV. Eine Datei wird zunächst nur gelesen; „Vorschau prüfen“ zeigt geplante neue Genres,
unveränderte Einträge, ergänzte Aliase, aktive/inaktive Genres und Konflikte einschließlich
Zeile/Eintrag und Feld. „Import bestätigen“ ist erst nach gültiger Vorschau möglich.
Die Anwendung prüft den aktuellen Datenbestand erneut in derselben Transaktion wie
alle Inserts. Namen, Aktivierungen, bestehende Aliase, IDs und Zuordnungen werden
nicht überschrieben. Zusätzliche manuelle Aliase bleiben erhalten. Fehlende Aliase
werden nur einem eindeutig identischen Genre ergänzt. Konflikte brechen den gesamten
Import mit HTTP 409 ab; es gibt keinen Teilimport. Ein identischer Folgeimport ändert nichts.

„Basisgenres wiederherstellen“ verwendet denselben Vorschau-/Bestätigungsablauf.
Die 38 ursprünglichen kanonischen Namen und Anzeigenamen aus Flyway V6 stehen
versioniert in `backend/src/main/resources/genres/base-v1.json`. Normalisierte,
redundante V6-Aliasschreibweisen sind dort zusammengefasst; kanonische Namen werden
bereits selbst erkannt. Der Stand dient auch dem vorhandenen eigenständigen
OCR-Lexikon ohne Repository-Anbindung. Ein Regressionstest vergleicht die Namen
mit der unveränderten V6-Migration. Flyway wird weder erneut ausgeführt noch geändert.

**Produktentscheidung:** Ein Werkseinstellungsreset leert weiterhin die Fachdaten
vollständig. Es gibt keine automatische Wiederbefüllung. Die Basiswiederherstellung
muss separat ausgelöst und bestätigt werden. Die RESET-Implementierung bleibt durch
diese Änderung unberührt.

## API und Grenzen

Beide Endpunkte verwenden den bestehenden authentifizierten API-Proxy und dessen
Origin-/CSRF-Schutz:

- `POST /api/v1/genres/import/preview`: Vorschau ohne Änderung der Fachdaten.
- `POST /api/v1/genres/import`: erneute Prüfung und atomare Anwendung.

JSON-Beispiel:

```json
{"schemaVersion":1,"genres":[{"canonicalName":"EBM","displayName":"EBM","active":true,"aliases":["electronic body music"]}]}
```

Alternativ `{"schemaVersion":1,"preset":"base-v1"}` für die Basisvorgabe oder
`{"schemaVersion":1,"csv":"…"}` für CSV. Genau eine dieser Quellen ist zulässig.
Die Oberfläche verpackt CSV selbst in diesen JSON-Umschlag; dadurch bleibt der
bestehende JSON-Schutz erhalten. Keine SQL-Datei wird ausgeführt.

CSV: UTF-8, optional BOM, Semikolon, exakte Kopfzeile
`canonicalName;displayName;active;aliases;gruppe;status;quelle;hinweis`.
Aliase sind mit ` | ` getrennt, `active` ist `true` oder `false`. Quotierte Felder
unterstützen Semikola, Zeilenumbrüche und verdoppelte Anführungszeichen. Fehler in
CSV-Datensätzen beziehen sich auf die physische Startzeile, JSON-Feldfehler auf den
1-basierten Genre-Eintrag; JSON-Syntaxfehler nennen die physische Zeile.

Maximal 1 MiB tatsächliche Anfragebytes (auch ohne Content-Length), 250 Genres,
20 Aliase je Genre und jeweils 100 Zeichen pro Name/Alias. Die Oberfläche prüft
Dateigröße und Größe der vollständigen Anfrage; CSV-Verpackung zählt mit.
Unbekannte Felder, doppelte JSON-Felder, zusätzliche JSON-Dokumente, ungültiges UTF-8
und fehlerhafte Werte werden abgelehnt. Recherche-Metadaten bleiben Vorschautext;
Quellen werden nicht aufgerufen oder in neue DB-Spalten geschrieben.

CRUD und Import verwenden dieselbe Normalisierung und kurze Datenbank-Tabellensperren
mit 1,5 Sekunden Lock-Timeout. Vorschauen laden/sperren nur den Katalog, nicht andere
Fachbereiche. Der OCR-Cache wird bei tatsächlichen Änderungen einmal nach Commit
aktualisiert; Rollback und unveränderte Folgeimporte aktualisieren ihn nicht. Inaktive
Genres bleiben in der Verwaltung und werden nicht für neue OCR-/Discovery-Matches
herangezogen.

## Geprüfter Stand

Die einzige bereitgestellte Vollreferenz war die CSV. Ihre inhaltlich unveränderte Kopie (Zeilenenden LF, BOM erhalten) liegt
als Testfixture in `backend/src/test/resources/genres/catalog-2026-11-09.csv`:
104 Genres, 80 aktiv, 24 inaktiv, 85 Aliase. Es wurde kein fehlender JSON-Katalog
angenommen und kein Produktionskatalog automatisch importiert.

Gezielte Prüfungen:

- Parser-/Grenzwerttests einschließlich CSV-Quoting, Zeilenangaben, UTF-8,
  unbekannter Content-Length und originaler V6-Basisnamen.
- Import-Integration in einer zusätzlich angelegten, isolierten und anschließend
  gelöschten PostgreSQL-Datenbank: leerer Katalog, Wiederholung mit stabilen IDs,
  manuelle Änderungen, normalisierte Konflikte, parallele Importe und erzwungener
  später Schreibfehler mit vollständigem Rollback und unverändertem Cache.
- Bestehendes Genre-CRUD, OCR-Lexikon und GenreCoverage; Erkennung von Dark Electro,
  EBM, Pop-Punk, Power Pop und Shoegaze nach Commit; inaktive Erweiterungen ausgeschlossen.
- 63 UI-Tests für bestehende Formulare und Import einschließlich Vorschau,
  Modal-Schließen nach Erfolg, Dateierhalt bei Fehler und Doppel-Submit-Sperre.
- 12 reale lokale Next-/Chromium-Szenarien: 1440/390 Pixel × drei Themes × zwei
  Dichten; CSV-Vorschau, Konflikt und erneute Prüfung, Tastaturbedienung,
  sichtbar bleibende Aktionen bei aufgeklappter Gesamtliste, keine horizontalen
  Überläufe oder Browserfehler. API-Antworten dabei ausschließlich lokale Fixtures.
- Strikte Java-/ECJ-, TypeScript- und ESLint-Prüfungen; verpflichtender QUICK-Hook
  vor Commit und FULL in GitHub Actions. CLI-Nachweise ersetzen keine Live-IDE-Prüfung.

Ansichten: [Smartphone mit Konflikt](screenshots/genre-import/mobile-conflict.png),
[Desktop nach Import](screenshots/genre-import/desktop-success.png).

Ein leerer Produktionskatalog wird für eine Probe **nicht** durch Reset hergestellt.
Der echte leere Importfall ist durch die isolierten API-/DB-Integrationstests abgedeckt.
Nach einer Auslieferung sind Erreichbarkeit, Vorschau und Authentifizierung zu prüfen;
der fachliche Import produktiver Genres bleibt eine ausdrückliche Bestätigung im UI.
