# Discovery-Bestandsdiagnose vom 05.11.2026

Die vorhandene lokale PostgreSQL-Datenbank auf Port 5433 wurde ausschließlich mit
`PGOPTIONS="-c default_transaction_read_only=on"` und SELECT-Abfragen gelesen.
Es gab keinen produktiven Zugriff, keine Migration und keinen Reset dieses Bestands.
Die anschließende Klassifikation erfolgte offline auf einer lokalen JSON-Kopie.

| Nachgewiesener Bestand | Anzahl |
|---|---:|
| Automatisch ignorierte Kandidaten | 94 |
| Manuell abgelehnte Kandidaten | 0 |
| Weitere offene Vorschläge | 2 |
| Gespeicherte Partyreihen | 0 |
| Aktive Genres | 42 |
| Genre-Aliasse insgesamt | 65 |

Alle 94 ignorierten Zeilen gehören zur Location Blue Shell. Ihr identischer Grund
lautet „Kein Partyreihen-Treffer; Partycharakter und passendes Kataloggenre nicht
gemeinsam belegt“. Der alte Matcher prüfte fehlenden Partycharakter **oder**
fehlenden Genrebeleg vor den Datumsprüfungen und gab direkt `IGNORED` zurück.
Das beweist keine fachliche Unpassendheit der einzelnen Veranstaltungen.

Der zweite nachgewiesene Fehler war der Fingerprint-Shortcut im alten
`EventCandidateProcessor`: Unveränderte Daten wurden ohne Berücksichtigung einer
Matcher-Regelversion übersprungen. Neue Regeln allein konnten alte Ausschlüsse
somit nicht zuverlässig korrigieren.

Von den 94 Zeilen enthalten 70 nur Titel-/Datumsbelege mit „Jahreskontext“,
24 enthalten zusätzliche Karten-/JSON-LD-/Detailangaben. Bei 40 fehlt der gespeicherte
Starttag, bei 70 die Uhrzeit. Die alte Speicherung bewahrte widersprüchliche
Startlisten, den strukturierten Eventstatus und die Quell-Location nicht vollständig
auf. Eine sichere vollständige Rekonstruktion ist deshalb nicht möglich.
Fehlende Uhrzeiten werden weiterhin nicht erfunden.

Mangels gespeicherter Partyreihen können **0 Kandidaten nachweislich einer
vorhandenen Partyreihe zugeordnet werden**. Bekannt klingende Veranstaltungstitel
sind kein Ersatz für fehlende Stammdaten. Ob die zwei offenen Vorschläge bereits
manuell bearbeitet wurden, lässt sich aus der alten Struktur nicht sicher ableiten;
sie werden bei der Migration vorsorglich als geschützte `LEGACY`-Entscheidungen behandelt.

## Ergebnis des Offline-Replays mit `party-rules-2`

| Neuer fachlicher Befund der bisher 94 ignorierten Zeilen | Anzahl |
|---|---:|
| `POSSIBLE_MATCH` – vorhandene passende Genrebelege | 8 |
| `UNVERIFIED` – Informationen reichen nicht aus | 78 |
| `IGNORED` – explizit fachfremde Belege | 8 |
| `MATCH` – bestätigte bekannte Reihe | 0 |

Die acht möglichen Treffer verteilen sich auf sechs verschiedene Titel:

- NEON PARADISE VS. NEON KULT VOL: 2 THE REVENCE: Synthwave, zwei Quellenzeilen.
- SHEEBABA · BABES OF ENOLA GREY · CHAMPIONNE MONDIALE: Post-Punk/Punk, zwei Quellenzeilen.
- DE RAMÖNSCHE · BÜDCHE BOYS: Punk.
- BLAIR DAVIE: Pop.
- ELA PAUL · MĀTTI HOFF: Pop.
- CARSICK: Indie/Punk.

Das sind **mögliche musikalische Treffer zur Prüfung**, keine sechs bestätigten
Clubpartys. Einige Titel könnten Konzerte sein; die gespeicherten Belege nennen
nicht überall ausdrücklich die Veranstaltungsart. Sie werden weder akzeptiert
noch importiert. Ein einzelnes allgemeines Genre beweist keinen Partycharakter.
Der zusätzliche echte Abruf und die Redaktion müssen die Veranstaltungsart klären.
Explizit belegte Konzerte bleiben auch beim manuellen Import gesperrt.

Beispiele der 78 ungeklärten Fälle sind READY TO START Party, TEENAGE DIRTBAG Party
und DISCOBUDE Party mit reinen Vorschautiteln ohne musikalische Beschreibung.
Die acht weiterhin ausgeschlossenen Zeilen enthalten belegte Comedy/Poetry-Slam-,
Privatveranstaltungs-, Dating-/Pitching- oder ausschließlich kölsche Musikangaben.
Es werden keine Genres aus Titeln, Bekanntheit oder Vermutungen erfunden.

Die Gruppierung nach Titel, Location und gespeichertem Datum ergibt 42 Gruppen,
darunter 30 mit mehreren Zeilen, also 52 zusätzliche Zeilen in solchen Gruppen.
Das weist auf Mehrfacherfassungen über unterschiedliche Darstellungen hin.
Bei fehlendem Datum sind dies **keine sicher bewiesenen Dubletten**; verschiedene
Termine könnten zusammenfallen. Deshalb bleiben sämtliche alten Zeilen erhalten.
Der neue Identitätsabgleich verhindert zusätzliche Neuerfassungen eindeutig
zuordenbarer Vorkommen; ein Reset löscht keine bestehenden Kandidaten.

Das Replay ist durch `ReadOnlyDiscoveryDiagnosisTests` mit einer ausdrücklich
bereitgestellten JSON-Kopie wiederholbar. Der Test verwendet dieselben Matcher-
und Genre-Regeln, markiert unvollständige Altbelege als unbestätigt und schreibt
nur einen lokalen Diagnosebericht neben die Kopie. Weder der Export noch
personenbezogene Rückfragetexte werden eingecheckt. Ein Live-Neuabruf aller
Location-Websites und ein Reset des bestehenden Datenbestands wurden bei dieser
Implementierung nicht ausgeführt.
