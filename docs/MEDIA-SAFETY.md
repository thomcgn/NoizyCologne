# Sichere Flyer- und OCR-Verarbeitung

## OCR: Bildprüfung und Ressourcen

Vor der vollständigen OCR-Dekodierung werden tatsächliche Dateigröße (höchstens
10 MiB), PNG/JPEG-Format, Dimensionen (höchstens 8 Millionen Pixel), ein einzelnes
Bild und kanonische Pfade geprüft. Symlinks einschließlich verlinkter Elternpfade
werden abgewiesen. APNG wird zusätzlich anhand seines Containers erkannt, weil
ImageIO sonst nur das erste Bild ausliest. Der PNG-Container ist auf 2048 Chunks
begrenzt. Ungültige oder unvollständige Bilder führen zu einem ausdrücklichen
Analysefehler. Originaldateien und bestehende Hashes werden nicht verändert.

Die gesamte Analyse einschließlich Dekodierung und Variantenbildung hat einen
Platz pro API-Prozess. Weitere Anfragen erhalten 429 mit einem erneuten Versuch
nach Abschluss; es gibt keine unbegrenzte Warteschlange. Auch der Tesseract-Worker
besitzt genau einen Platz. Dieser wird bei bestätigtem Prozess-/Reader-Abschluss
auch nach Startfehler, Timeout, Unterbrechung und Parserfehler wieder freigegeben.

`TESSERACT_TIMEOUT_SECONDS` bleibt standardmäßig 30 Sekunden je Durchlauf; die
wirksame Konfiguration ist auf 1–60 Sekunden begrenzt. `OMP_THREAD_LIMIT=1` bleibt
bestehen. Zusammengeführte Standard-/Fehlerausgabe ist auf 1 MiB begrenzt. Bei
Überschreitung wird der Prozess beendet, kein Teilergebnis als Erfolg ausgegeben.
Das TSV-Ergebnis besitzt höchstens 10.000 Wörter, 4096 Zeichen pro Zeile und
1024 Zeichen pro Wort. Nicht endliche Konfidenzwerte erzeugen keine Vorschläge.

Prozess und Ausgabereader müssen nach Fehlern innerhalb weiterer vier Sekunden
beendet sein. Bei unbestätigtem Abschluss bleibt OCR bis zur Administratorprüfung
und einem Neustart geschlossen (503), statt weitere Worker zu starten. Prozesse
und Threads bleiben dadurch begrenzt. Die vollständige OCR-Ausgabe wird nicht
protokolliert. Tesseract-Binary und Kommando-Konfiguration sind vertrauenswürdige
Serverkonfiguration; es werden keine vom Client eingesandten Programme ausgeführt.

Die automatisierte Abnahme verwendet gültige PNG/JPEG, APNG, mehrere JPEG-Bilder,
kaputte Container, Symlinks und übergroße Dateien. Gigapixel-Header werden in einer
eigenen JVM mit `-Xmx32m` vor der Dekodierung abgewiesen. Ausführbare, lokale
Testworker prüfen Prozessstart, Timeout, Ausgabegrenze, Parallelität, Unterbrechung
und Freigabe nach Fehlern; sie ersetzen keinen Nachweis der Erkennungsqualität auf
realen Flyern. Alle Testdateien liegen in temporären Verzeichnissen; die Unit-Tests
verwenden keine Datenbank und keine Produktionsmedien.

Upload-Lifecycle, konsistente Uploadgrenzen und kontrollierte Orphan-Bereinigung
werden als eigener MEDIA-Teilschritt abgenommen. Diese OCR-Änderung aktiviert keine
Bereinigung vorhandener Medien und keine Umkodierung ihrer Metadaten.
