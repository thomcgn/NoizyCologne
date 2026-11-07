# Discovery: statische Partyübersichten abrufen

Auftrag vom 06.11.2026, Branch `fix/discovery-static-party-fetch`.
Gezielte Fehlerdiagnose, allgemeine Fetcher-/Extraktorkorrektur und Regressionen.
Keine Worktrees, Datenbankänderungen oder Deployments.

## Befund und Korrektur

Der ursprüngliche Java-Aufruf scheiterte bereits bei `SafeUrls.resolve` mit
`UnknownHostException: Nicht öffentliche Zieladresse gesperrt`. Die DNS-Antworten
waren `217.160.0.64` und `2001:8d8:100f:f000::241`. Die bisherige IPv6-Maske sperrte
irrtümlich `2001::/19` einschließlich dieser öffentlichen Hosting-Adresse.
Die Prüfung grenzt jetzt `2001::/23` und den separaten Dokumentationsbereich
`2001:db8::/32` ab. Private, lokale, Übergangs- und Dokumentationsadressen bleiben
gesperrt; sämtliche DNS-Antworten müssen öffentlich sein. Referenz:
[IANA IPv6 Special-Purpose Registry](https://www.iana.org/assignments/iana-ipv6-special-registry).

Der direkte HTTPS-Aufruf am 06.11.2026 und anschließend der korrigierte Java-Fetcher
bestätigen für https://www.sonic-ballroom.de/index.php/parties:
HTTP 200 ohne Redirect, unveränderte finale URL, gültige TLS-Verbindung,
`text/html; charset=UTF-8`, keine Content-Encoding-Angabe. `/robots.txt` liefert
HTTP 200; die dort gesperrten Anwendungsordner betreffen `/index.php/parties` nicht.
Es gab bei diesen Aufrufen keinen DNS-, TLS- oder Timeout-Fehler der Website.

Redirects werden auf maximal fünf begrenzt und ausschließlich manuell verfolgt:
HTTPS-/URL-Prüfung für jedes Ziel, öffentliche DNS-Prüfung durch den tatsächlichen
Verbindungsresolver bei jedem Request, einschließlich robots.txt. Vor dem Abruf
eines umgeleiteten Inhalts müssen die Robots-Regeln des Zielhosts seinen Pfad
erlauben. Schleifen, unsichere Ziele und Zugriffssperren bleiben Fehler.
Gzip/Deflate werden kontrolliert dekodiert; das Größenlimit gilt auch entpackt.

DNS-, TLS-, Timeout-, HTTP-Status-, Content-Type- und Encoding-Probleme enthalten
konkrete Ursachen. robots.txt-Fehler nennen diesen Prüfschritt. Fehlgeschlagene
Quellen liefern einen eigenen Befund, ohne den ganzen Quellencheck abzuwerfen oder
über einen Browser eine Sperre zu umgehen. Erfolgreiche Befunde nennen die finale URL.

Die serverseitigen H1-Karten wurden bisher vom generischen Extraktor übersehen.
Er berücksichtigt jetzt H1-Karten mit jeweils eigener Datumsangabe. Externe
Social-Links ersetzen keine Eventidentität. „Nach dem Konzert“ bleibt ein relativer
Start und ist allein kein Konzertbeleg; tatsächliche Konzerte bleiben ausgeschlossen.
Das gespeicherte HTML-Fixture (nur Einrückungen und Zeilenenden normalisiert) enthält 22 Einträge einschließlich BallroomBlitz!, Shake Appeal
und Rotten Flowers. Fehlende Jahreszahlen und HEUTE/MORGEN bleiben als unvollständige
Datumsbelege prüfpflichtig; Jahr und Uhrzeit werden nicht erfunden.

## Prüfphase und Abschluss

Gezielte Fetcher-, Extraktor-, Navigations-, Robots-, Konzertfilter- und Browser-
Abgrenzungstests bestanden; nach Vertragsanpassung nur betroffene Tests wiederholt.
Strenge javac-/ECJ-Analyse einschließlich Null-Safety ohne Diagnosen bestanden.
Live-Java-Abruf: Robots erlaubt, HTML lesbar, 22 Einträge ohne JavaScript extrahiert.
QUICK läuft einmal im verpflichtenden Commit-Hook. FULL bleibt ausschließlich
GitHub Actions vor Abnahme des exakten Commits vorbehalten. Keine UI-Änderung und
keine Browserprüfung; CLI-Ergebnisse sind kein Nachweis der Live-IDE-Diagnosen.

## Jahreslose Programmdaten (Folgeauftrag 06./07.11.2026)

Die allgemeine HTML-Kartenauswertung löst jetzt deutsche Tages-/Monatsangaben
wie „Freitag, 04. Dezember“, „Sa. 05. Dez.“ und „04.12.“ anhand vollständiger
Startdaten derselben Geschwister-Programmliste auf. Beschreibungstexte mit
vollständigem Datum liefern dabei einen Jahresanker für die Kartenliste;
Ausgabe und Auflösung erhalten die DOM-Reihenfolge. Keine Domainabfrage oder
neue Crawler-Architektur. Explizite Quellenprofile behalten ihre Datumsselektoren.

Ein Monatsrücksprung in einer chronologischen Liste erhöht das belegte Jahr,
auch bei ausgelassenen Dezemberterminen. Ein Tagesrücksprung innerhalb desselben
Monats, widersprüchliche Jahresanker, falsche Wochentage oder ungültige Daten
führen nicht zu einer geratenen Auflösung. Ohne Anker wird kein aktuelles Jahr
angenommen. HEUTE/MORGEN beziehen sich auf eine injizierbare Clock in
Europe/Berlin. Uhrzeiten werden aus relativen Beginnangaben nicht abgeleitet.
Bestehende vollständige Startzeiten bleiben erhalten. Die ursprünglichen
absatzbasierten Vorschau-/Jahresbereichsregeln bleiben unverändert.

Bei geändertem Datumsformat ordnet der Processor einen datierten Fund nur einem
eindeutigen Altvorschlag derselben Quelle, desselben vollständigen Titels und
passenden Tages-/Monatsbelegs zu. Relative Altbelege benötigen ihr gespeichertes
Beobachtungsdatum. Neue Identitätshashes werden als vorhandene Identitätsaliase
vermerkt. Mehrdeutige Altzuordnungen erzeugen keinen dritten Kandidaten und
keinen automatischen Import. Mehrere gelistete Ausgaben derselben Reihe bleiben
getrennt, auch über zusätzliche Quellenbeobachtungen; stabile externe Identitäten
mit widersprüchlichen Angaben behalten die bisherigen Konfliktprüfungen.

Manuelle/Legacy-Entscheidungen und abgelehnte Kandidaten bleiben geschützt,
einschließlich eines historisch als AUTOMATIC markierten REJECTED-Status.
Geschützte Datums-/Party-/Rückfragewerte und Kalenderdaten werden nicht durch
neue Quellenwerte überschrieben. Ein unvollständiger technischer Monitoring-
Vergleichsstand wird bei bestätigter Wiederbeobachtung aktualisiert, ohne daraus
eine Terminverschiebung zu behaupten.

Gezielte Prüfungen: sieben neue Jahreslos-/Fixturetests, sechs Batch-Merger-
Tests und bestehende deutsche Programm-, Extraktions- und statische Fetcher-
Regressionen bestanden. Die Fixture prüft unter einer fremden Domain alle 22
Karten, konkrete Dezember-/Januartage, zwei ROMAN-EMPIRE-Ausgaben sowie dieselbe
Auflösung ohne HEUTE/MORGEN und mit einer absichtlich abweichenden Clock 2040.
Weitere Fälle: fehlender/lokal getrennter Anker, Wochentags-/Jahreswiderspruch,
Schaltjahrfehler, Berlin-Mitternacht/Sommerzeit und relative Beginnangaben.

Vier gezielte Datenbankfälle: 22 Alt-IDs nach zwei Scans erhalten; manuelle
Entscheidungen, Antworttexte und Kalenderzeile unverändert; keine zusätzlichen
Änderungsmeldungen; technischer Vergleichsstand auf vollständigem Quelldatum;
falscher Wochentag verhindert Import; mehrdeutige Altzeilen werden nicht gewählt
oder vermehrt; abgelehnter Altvorschlag wird nicht erneut übernommen. Zunächst
fand die Bestandsregression eine falsche innere Kartengruppierung und geänderte
DOM-Reihenfolge; nach Korrektur nur die betroffenen Fälle erneut erfolgreich
geprüft. Ausschließlich neue isolierte Testdatenbanken, anschließend entfernt.

Inkrementelle javac-Prüfungen bestanden. Befunde der Nullanalyse wurden mit
expliziten Prüfungen an Bibliotheksgrenzen und präzisem Listentyp korrigiert.
Frische ECJ-Haupt-/Testanalyse ohne Diagnosen bestanden. Der reguläre QUICK-
Commit-Hook ist die abschließende Commitprüfung;
Einzelprotokolle unter `.quality/calendar-visibility/yearless-*` und `.quality/run.*/`.
Alle intensiven Schritte nacheinander mit Heap-/RSS-Grenzen und laufender RAM-
Überwachung, bisher mindestens 6.845 MiB verfügbar, kein Speicherabbruch.
Kein Live-Suchlauf, Produktionsdatenzugriff, Browser, lokaler STANDARD/FULL- oder
Produktionsbuild. FULL bleibt vor Integration der GitHub-CI vorbehalten.

### CI-Konfliktregressionen korrigiert (07.11.2026, PR #18)

Bestandsaufnahme: sauberer Branch `fix/discovery-yearless-dates`, letzter Commit
`8ae6641`. Zuerst ausschließlich `SpecialSourcesIntegrationTests` und
`DiscoveryIntegrationTests` gegen eine neue isolierte PostgreSQL-Testdatenbank
ausgeführt: 54 Tests, genau die beiden gemeldeten Konfliktfälle fehlgeschlagen.

Ursache: `DiscoveryBatchMerger.separateEditions` trennte unterschiedliche Tage
derselben Quelle vor dem Vergleich der stabilen externen Veranstaltungs-ID.
Diese ID hat jetzt Vorrang: widersprüchliche Beobachtungen derselben Veranstaltung
bleiben gemeinsam prüfpflichtig, mit beiden Datumsbelegen und `SOURCE_CONFLICT`.
Widersprüchliche Lifecycle-Signale erhalten ebenfalls diesen Konfliktmarker.
Getrennte Ausgaben einer Reihe und die deutsche Jahresauflösung bleiben erhalten.
`SpecialSourceService` und `DiscoveryScheduler` sammeln bereits vor der
Kandidatenverarbeitung den gesamten begrenzten Batch. `EventVerification` und
die bestehende Auswertung blockieren `CONFLICTING_DATA`; die Schutzpfade für
manuelle Entscheidungen und vorhandene Kalenderdaten bleiben unverändert.

Nach der Korrektur dieselben beiden Integrationstestklassen erneut ausgeführt:
55 Tests bestanden, einschließlich beider unveränderter CI-Regressionen und
eines zusätzlichen Uhrzeitkonflikts ohne Kalenderimport. Danach 58 unmittelbar
betroffene Unit-Regressionen bestanden: Batch-Merger, Assessment, Lifecycle,
deutsche Jahresauflösung/Programme, Extraktion und statischer Fetcher.
Neue Merger-Fälle prüfen widersprüchliche Tage/Uhrzeiten derselben Quellen-ID
und Absage/Aktiv-Widersprüche jeweils in beiden Eingabereihenfolgen.
Die Integrationstests prüfen auch die bestehenden manuellen Entscheidungen,
Kalendereinträge und die Aktualisierung jahresloser Altvorschläge.

Prüfbefehle: Maven `test-compile surefire:test` mit
`-Dtest=SpecialSourcesIntegrationTests,DiscoveryIntegrationTests`, danach
`surefire:test` mit `-Dtest=DiscoveryBatchMergerTests,DiscoveryAssessmentTests,EventLifecycleTests,GermanYearlessDateTests,GermanProgramExtractionTests,StaticPartyFetchTests,DiscoveryTests`.
Einzelprotokolle unter `.quality/calendar-visibility/conflicts-*.log`.
Beide isolierten Testcontainer entfernt; keine Entwicklungs-/Produktionsdatenbank
verwendet. Intensive Schritte nacheinander mit Speicherprüfung und laufender
Überwachung, bisher mindestens 7.319 MiB verfügbar, kein Speicherabbruch.
Abschlussprüfung über den regulären QUICK-Commit-Hook; FULL bleibt in GitHub
Actions. Kein zusätzlicher PR, Browser, lokaler Build, Merge oder Deployment.
