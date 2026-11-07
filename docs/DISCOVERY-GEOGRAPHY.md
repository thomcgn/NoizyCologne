# Discovery: geografischer Filter für Sonderquellen

Auftrag: ausschließlich das Stadtgebiet Köln, ohne Umkreis. Keine Produktionsdaten
oder zusätzlichen Worktrees. Umsetzung auf `feat/discovery-cologne-geography`.

## Phasen

1. Bestand geprüft: Locations enthalten bisher keine bestätigte Stadt/Adresse;
   Event-Rohdaten erhalten strukturierte Adressen/Koordinaten noch nicht separat.
2. Strukturierte Ortsbelege, zentraler Filter und bestätigte Ortsidentitäten: umgesetzt.
3. Separater Standortbereich, explizite Neubewertung und Importschutz: umgesetzt.
4. Modul- und Browserprüfungen bestanden; Abschluss über regulären QUICK-Hook, Commit/Push und eigenen Abnahme-PR.

## Regeln

Keine Herkunfts-, Titel- oder Freitextvermutung. Bestätigte Locations/Aliase
benötigen zusätzlich eine belastbare Ortsidentität (Ort-ID/Ort-URL/Adresse).
Bloße Namensgleichheit reicht nicht. Geografische Unsicherheit ist ein eigener
Arbeitsbereich; fachliche Prüfungen beginnen erst nach positivem Köln-Nachweis.
Kalenderimporte, manuelle Entscheidungen und Absagedaten bleiben erhalten.
Bestandsfunde werden bei einer ausdrücklich angeforderten Neubewertung klassifiziert.


## Umsetzung

Migration V18 speichert geografische Einstufung getrennt vom Prüfstatus sowie
Ortsbelege, bestätigte Location-Zugehörigkeiten und belegte Namensvarianten.
Bestehende Kalender-/Cancellation-Tabellen und Prüfentscheidungen werden nicht
migriert oder gelöscht. Nicht übernommene alte Sonderquellen-Kandidaten tragen
zunächst `NOT_CHECKED` und erscheinen außerhalb des fachlichen Prüfbedarfs.

`CologneGeography` entscheidet zentral `INSIDE`, `OUTSIDE` oder `UNRESOLVED`.
`EventPlace` erhält ausschließlich tatsächliche Veranstaltungsort-Metadaten:
JSON-LD-Place/PostalAddress/GeoCoordinates, semantische HTML-Location oder das
ICS-LOCATION/GEO-Feld. Beschreibungen, Titel, allgemeine Kontakt-/Veranstalter-
adressen, Portalpfade und Postleitzahlbereiche beweisen keine Stadtzugehörigkeit.
Bestehende JSON-LD-Rohbelege werden bei ausdrücklicher Neubewertung strukturiert
nachgelesen; Freitext bleibt ungeklärt. Getrennte Aftershow-Events behalten ihre
eigenen Ortsdaten und werden nicht durch die Adresse des Konzerts ersetzt.

Bestätigte Locations benötigen einen passenden normalisierten Namen bzw.
bestätigten Alias **und** eine passende Ortsreferenz/Adresse. Namensgleichheit
allein führt nicht zur Wiederverwendung. Eine ausdrückliche Auswahl einer Location
für einen bereits belegten Kölner Fund bestätigt dessen Zuordnung. Im separaten
Standortdialog kann das Team nach Prüfung von Stadt, Straße/Hausnummer und Beleg
auch eine neue Location anlegen. Bereits bestätigte Ortsidentitäten dürfen nicht
versehentlich einer anderen Location zugeordnet werden. Unbewiesene Namen/fehlende
Daten sowie widersprüchliche oder unpräzise Koordinaten bleiben konservativ ungeklärt.

Für Koordinaten liegt ein offline nutzbarer WGS84-Snapshot der neun Kölner
Stadtbezirke vor, ohne Umkreis oder Bounding-Box-Freigabe. Quelle, Stand und
Attribution stehen in `backend/src/main/resources/geography/README.md`.
Es gibt keine neue Geocoding-Infrastruktur und keine Laufzeit-Netzanfrage für
Ortsprüfungen. Polygonkanten, ungültige und zu grobe Koordinaten werden nicht
als positive Ortsbelege verwendet; Widersprüche erfordern Klärung.

`DiscoveryGeographyService` speichert Ortsfunde vor Genre-Matching und
Kandidatenerstellung. Auswärtige/ungeklärte neue Funde erzeugen keine normalen
Kandidaten. Das API `/api/v1/discovery/geography` liefert einen eigenen Bereich
mit Gesamtzählern und bis zu 300 Funden je Einstufung sowie 300 alten ungeprüften
Kandidaten. Bearbeitete Funde machen Platz für weitere nachgeladene Einträge.
POST `/{id}/confirm` erfordert eine ausdrücklich bestätigte Kölner Adresse und
eine bestehende oder neue Location; der Vorgang wird protokolliert, importiert
aber keinen Kalendertermin. Eine belegte auswärtige Adresse ist kein übergehbarer
Bestätigungsdialog.

Die vorhandene Reset-Vorschau zeigt die geografische Einstufung des Bestands
separat. Erst die ausdrücklich bestätigte Neubewertung schreibt diese Metadaten.
Manuelle Party-/Datums-/Rückfrageentscheidungen bleiben erhalten. Neue technische
Ortsbelege werden auch bei manuellen Kandidaten aktuell gehalten: Ein später
belegter auswärtiger Veranstaltungsort kann weder mit veralteten Review-Daten
importiert noch bei einer Neubewertung wieder zu Köln erklärt werden. Importierte
Kandidaten, Kalendertermine und Cancellation-Daten bleiben geschützt; bestehendes
Monitoring bleibt möglich. Import- und Portfolio-APIs prüfen den Ortsnachweis
zusätzlich zur Listenfilterung.

`CanonicalSourceUrl` entfernt `_rsc` unabhängig vom Host aus Quellen-/Abruf- und
Eventadressen. Andere Query-Parameter und echte Ortsreferenzen bleiben erhalten.
Bestehende `_rsc`-Identitäten werden ihrer bisherigen Kandidaten-ID zugeordnet,
statt geschützte Ablehnungen als neue Vorschläge wiederzubeleben. Es gibt keine
individuelle Qrush-Erkennungsregel.

## Prüfung und Abnahmegrenzen

- Backend-STANDARD: javac, ECJ einschließlich Null-Safety und Maven-Paket bestanden.
  224 Tests: 223 erfolgreich; ein optionaler privater Snapshot-Replay mangels Export
  ausgelassen. Datenbanktests ausschließlich im kurzlebigen Gate-PostgreSQL.
- Frontend-STANDARD: TypeScript, ESLint, 77 Tests und Next.js-Build bestanden.
- Synthetischer Chromium-Browser: Standorttrennung, ausdrückliche Bestätigung,
  Fehlermeldung mit erhaltenen Eingaben und anschließender Übergang in den
  normalen Prüfbereich bei 1440/390/320 px erfolgreich. Keine Seitenfehler oder
  horizontalen Überläufe. Reproduzierbar über
  `frontend/tests/visual-discovery-geography.mjs`, Bilder in
  `.quality/discovery-geography/` geprüft.
- Regressionen enthalten Blue Shell/MTC/Sonic Ballroom Köln, Bonn/Aachen/Berlin/
  Frankfurt, unbekannte Orte, normalisierte Namen/bestätigte Aliase, Namensvetter,
  „Köln“ im Titel mit Bonn als Adresse, Polygon- statt Umkreisprüfung, getrennte
  Kölner Aftershow, bestätigte neue Location, geschützten Bestand inklusive
  Cancellation-Historie und spätere Verlegung eines manuellen Funds nach Bonn.

QUICK wurde während der Entwicklung verwendet; ECJ-Befunde wurden ohne
Abschwächung der Regeln korrigiert. Der lokale Commit läuft über den regulären
QUICK-Hook. CLI und synthetischer Browser ersetzen keine Live-IDE-/Geräteabnahme.
Ein bereits gespeichertes öffentliches Qrush-HTML-Beispiel wurde zusätzlich
offline gelesen: strukturierter Ort Chemnitz → OUTSIDE. Kein neuer Live-Portalabruf,
kein Datenbankzugriff für dieses Beispiel und keine Produktionsdatenänderung. Der Grenzsnapshot
ist versioniert; Änderungen daran benötigen erneute Regressionen. FULL in CI für
den exakt akzeptierten Commit bleibt vor Merge erforderlich.

## UI-Nachtrag: Standortklärung als Accordion (07.11.2026)

Bestand: Der bisher offene Standortbereich verwendet bereits aktuelle API-Zähler
und lädt sie nach einer bestätigten Zuordnung neu. Backend und Datenmodell bleiben
unverändert. Umsetzung: Der gesamte Bereich ist standardmäßig geschlossen und
nutzt natives `details`/`summary` mit `aria-expanded`, vorhandenen Fokuszuständen
und dem bestehenden Accordion-Stil einschließlich 44-px-Kopfbereich. Titel und
Zähler bleiben sichtbar; alle Inhalte und Aktionen bleiben beim Zuklappen erhalten.

Gezielte Prüfung: `node --test --test-concurrency=1 --test-name-pattern='geography|source'
frontend/tests/discovery-assessment.test.mjs` – 14 Tests bestanden. Abgedeckt sind
geschlossener Anfangszustand, fokussierbarer Header, Öffnen/Schließen,
`aria-expanded`, unveränderte Inhalte/Aktionen, aktualisierter Zähler nach Zuordnung
und erhaltene Formulareingaben bei Fehlern. Speicherwächter: mindestens 7.945 MiB
verfügbar, kein Abbruch. Abschluss über den regulären QUICK-Commit-Hook; FULL
bleibt GitHub Actions vorbehalten. Keine lokalen Builds, Browserprüfungen oder
Datenbanktests für diese UI-Anpassung; keine Live-Geräte-/IDE-Abnahme.
