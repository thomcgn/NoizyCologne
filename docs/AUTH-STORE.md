# AUTH, Teil 1: persistenter interner Speicher

## Umfang und Risikobewertung

SEC-03/SEC-04 benötigen über Prozessneustarts hinweg gemeinsamen Zustand. Zwei
kleine PostgreSQL-Tabellen vermeiden eine weitere Infrastrukturkomponente.
Dieser PR stellt ausschließlich den internen Speicher bereit. Die bisherige
Next-Anmeldung nutzt ihn noch nicht; Login/Logout gelten damit noch nicht als
vollständig gehärtet. Die Integration folgt separat.

Es werden keine bestehenden Konten, Passwörter, Medien, Kandidaten oder
redaktionellen Entscheidungen geändert. Die additive Migration V24 enthält
nur Infrastruktur. Der Werksreset kennt diese Tabellen ausdrücklich, zählt und
löscht sie aber nicht. Unbekannte Tabellen blockieren den Reset weiterhin.

## Grenzen und Vertrauensbeziehung

`/internal/auth/{register,validate,revoke,attempt,clear}` akzeptiert POST mit
einem eigenen `X-Noizy-Auth-Key`. Konfiguration: `AUTH_SERVICE_SECRET`, mindestens
32 Zeichen. Ohne Konfiguration antwortet der Controller mit 503, bei falschem
Schlüssel mit 403. Das API-Proxy-Token berechtigt diese Aufrufe nicht. Caddy
leitet `/internal` weiterhin nicht an das Backend weiter. Erfolgreiche Antworten
tragen `Cache-Control: no-store`. Der Schlüssel gehört ausschließlich auf Server.

Sitzungen speichern SHA-256-Noncekennung, Credential-Generation, Ablauf und
Widerruf. Eine widerrufene Kennung kann bis zum Ablauf nicht erneut angelegt
werden. TTL maximal acht Stunden plus 30 Sekunden Zeitabweichung. Maximal 2048
Sitzungsdatensätze einschließlich noch gültiger Widerrufe; Kapazitätsfehler
verweigern neue Sitzungen, statt alte Widerrufe zu entfernen.

Anmeldeversuche speichern ausschließlich getrennte SHA-256-Kennungen für das
konfigurierte Konto und die vertrauenswürdig ermittelte IP. Je 15 Minuten sind
fünf Versuche pro IP und 25 pro Konto möglich. Höchstens 4096 Buckets; keine
Buckets für frei eingegebene Benutzernamen. Die Next-Integration muss diese
Kennungen korrekt erzeugen; der interne Dienstschlüssel authentisiert den
Aufrufer, ersetzt aber keine sichere Proxy-IP-Ermittlung.

Alle Operationen verwenden denselben transaktionalen PostgreSQL-Advisory-Lock.
Bei Konkurrenz wird mit 429 geschlossen abgebrochen. Abgelaufene Datensätze
werden bei jedem Zugriff entfernt; die maximale gespeicherte Menge bleibt auch
bei ausbleibenden Zugriffen begrenzt. Es werden weder Cookies, rohe IP-Adressen
noch Benutzernamen gespeichert. Kein öffentlicher Session-Listen-Endpunkt.

## Kompatibilität, Rollout und Rollback

Das Backend bekommt den optionalen Schlüssel; dieser Teil erzeugt/aktiviert ihn
noch nicht. Die bestehende Frontend-Version funktioniert unverändert. Erst der
nachfolgende Integrations-PR aktiviert den gemeinsamen Speicher und dokumentiert
die notwendige erneute Anmeldung bestehender Sitzungen.

Rollback des Anwendungscodes lässt V24 und Daten bestehen; keine Down-Migration,
kein DROP und kein produktiver Reset. Eine Version vor V24 kennt die neuen
Infrastrukturtabellen im Reset-Schema nicht und verweigert den Werksreset dann
bewusst. Normale Daten bleiben erhalten. Vor Aktivierung der Auth-Integration
müssen alle beteiligten Komponenten denselben neuen Stand haben.

## Nachweise

Gezielte PostgreSQL-Integration ausschließlich in einem kurzlebigen Testcontainer
mit eigenen Credentials und Medienverzeichnis: getrennte Store-Instanzen,
Widerruf, Generation, Ablauf/TTL, IP-/Kontolimits, parallele Versuche, HTTP-Key-
Prüfung und Cache-Control. Bestehende Reset-Integration prüft weiterhin das
Schema. Dies ist kein Nachweis eines echten VPS-Neustarts oder einer bereits
aktivierten Next-Integration. QUICK läuft im Hook; FULL in GitHub Actions.
