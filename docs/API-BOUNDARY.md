# API-BOUNDARY — SEC-01 / SEC-02

Baseline `main` 160cd81: Caddy authentifizierte `/api/v1/*` ausschließlich über
Next-Sitzungen. Das Backend selbst vertraute jedem erreichbaren internen Client;
fachliche Mutationen hatten keinen gemeinsamen Origin-/Fetch-Metadata-Guard.

## Entscheidung und Invarianten

Caddys `forward_auth` bleibt vor der API-Weiterleitung unverändert erhalten.
Zusätzlich überschreibt ausschließlich der vertrauenswürdige Proxy drei Header:
`X-Noizy-Api-Key`, `X-Noizy-Proxy-Host`, `X-Noizy-Proxy-Proto`. Kein vom Browser
gelieferter Wert wird weiterverwendet. Der separate Schlüssel wird nur an
Caddy/API gegeben, niemals an Browser-JavaScript oder dauerhaft an den Worker.
Standard-Forwarded-Header werden nicht zur Vertrauensentscheidung benutzt:
Spring darf diese konsumieren; erwarteter Ursprung und Proxykennung sind fest
konfiguriert. Manipulierte Client-Header ändern den erwarteten Origin nicht.

Ein früher Servletfilter verweigert fachliche Zugriffe ohne gültigen Schlüssel
zeitkonstant, auch intern und auch bei fehlender Konfiguration. Keine lokale
Profil-Ausnahme. Readiness/Liveness bleiben nur auf den nicht öffentlich
weitergeleiteten Actuator-Pfaden lesbar. `/internal/maintenance/factory-reset`
behält seinen eigenen Schlüssel und die bestehende Next-Reauthentifizierung;
sein Ausnahmefall im API-Filter ist keine Autorisierung für einen Reset.
Alle anderen Pfade sind fail-closed, einschließlich unbekannter/encodierter Pfade.

POST/PUT/PATCH/DELETE erfordern den exakten konfigurierten HTTPS-Origin,
passende Proxykennung und, falls vorhanden, `Sec-Fetch-Site: same-origin`.
`same-site`, `cross-site`, `null`-/fehlender Origin und fremde Subdomains werden
abgewiesen. JSON und Multipart sind zulässig, einfache Formular-/Text-Posts nicht.
Bodylose DELETE/POST bleiben möglich. Sitzung/SameSite bleibt zusätzlicher Schutz;
der interne Schlüssel allein ersetzt Caddys Benutzerprüfung nicht.

`API_TRUST_SECRET` wird im qualifizierten Deploy einmalig erzeugt, falls noch kein
Eintrag existiert; ein leerer/zu kurzer vorhandener Eintrag bricht ab. Bestehende
Login-/Session-/Reset-/Browserkeys bleiben unverändert. Keine Host-Ports für
API/DB und kein Hostnetz. Beim Browser-Smoke wird der Schlüssel nur vom
API-Container über eine Pipe an einen einmaligen Gatewayprozess gegeben, nicht
als Hostargument/Log/Clientkonfiguration. Der Smoke behält ausschließlich die
lesende Websitevorschau bei.

## Kompatibilität, Rotation und Rollback

Neue API und neue Caddy-Konfiguration werden gemeinsam über das bestehende
SHA-geprüfte Release ausgerollt. Die Backend-Readiness verlangt kein Proxyheader,
aber ein erfolgreicher authentifizierter API-Smoke bleibt nach Cutover Pflicht.
Ein alter Proxy vor der neuen API wird abgewiesen; bei fehlgeschlagenem Cutover
stellt der bestehende Rollback **beide** vorherigen Manifeste/Images wieder her.
Kein manueller Teilaustausch und kein eigenmächtiger Produktionsdeploy.

Kontrollierte Rotation: zunächst den alten Key zeitlich begrenzt als
`API_TRUST_PREVIOUS_SECRET` auf der API erhalten, neuen aktiven Key in Caddy/API
bereitstellen und den authentifizierten Smoke prüfen. Danach vorherigen Key
wieder entfernen und dessen Ablehnung prüfen. Beide Schlüssel bleiben nur in
privater Serverkonfiguration; keine Dauerfreigabe des vorherigen Keys. Rotation
und Produktionsaktivierung verlangen weiterhin eigenes Wartungs-/Deploymandat.

## Tests und Abnahmegrenze

- `ApiBoundaryTests`: fehlender/falscher Schlüssel und fehlende Konfiguration,
  feste Proxykennung, alle Mutationsmethoden, fehlender/fremder Origin,
  Fetch-Metadata, JSON/Multipart/Formular, private Health-/Reset-Abgrenzung,
  kontrollierter Schlüsselwechsel.
- `ApiBoundaryIntegrationTests`: tatsächlich registrierter Servletfilter,
  direkte Backendzugriffe, Cross-Origin-POST, Readiness und weiterhin separater
  Reset-Schutz; ausschließlich isolierte PostgreSQL-Datenbank.
- Bestehende Controller-Integrationstests verwenden explizite Testkeys und
  vertrauenswürdige MockMvc-Standardheader; der Filter wird nicht deaktiviert.
  Dedizierte Negativtests deaktivieren nur diese Standardheader.
- `scripts/test-api-boundary.py`: echte Caddy-Konfiguration mit synthetischem
  Session-/API-Server in eigenem internem Compose-Netz, ohne Hostports oder DB.
  Ohne Cookie 401; gefälschter API-Key hilft nicht; mit Testcookie überschreibt
  Caddy Clientheader; Reset bleibt 404. Bestandteil von CI-FULL. Das Serverfixture
  simuliert nur die Backendantwort; echte Filter-/Controllerprüfung separat oben.
- Worker-Smoke: interner Key über stdin, nicht im Ergebnis; vorhandene
  Sandbox-/SSRF-/Robots-/Budgetregeln bleiben unverändert.

Produktiv NICHT AKTIVIERT oder schreibend geprüft. Ein Browser-/UI-Smoke unter
neuer Konfiguration und eine Schlüsselrotation auf dem VPS sind gesonderte
Betriebsabnahmen. Backup-/Restore-Freigabe und spätere AUTH-/RESET-Phasen bleiben
unabhängige Voraussetzungen für eine vollständige Produktionsfreigabe.
