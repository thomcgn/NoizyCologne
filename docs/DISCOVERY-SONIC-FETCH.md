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
