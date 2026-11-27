# ADR 0001: Instagram-Publishing-Grenze – Infrastruktur implementiert, Aktivierung NO-GO

- **Status:** Infrastruktur implementiert, Aktivierung **NO-GO** (bewusst fail-closed)
- **Datum:** 24.11.2026 · aktualisiert 27.11.2026
- **Grundlage:** `AGENTS.md`, `docs/QUALITY.md`, `docs/PROJECT.md` (insb. 3.2, 4.2, 5.2, 8, 17, 20.8, 23), `backend/src/main/java/de/noizycologne/backend/instagram/**`, `backend/src/main/resources/db/migration/V28__instagram_publishing.sql`, `V29__external_event_sources.sql`
- **Umfang:** Architektur-Entscheidungsdokument zur Publishing-Grenze. Die technische Infrastruktur ist umgesetzt, aber nicht aktiviert und nicht freigegeben.

## Lesart dieser ADR

Jede Aussage ist entweder

- **IST (belegbar):** im aktuellen Code oder in den zitierten Projektkapiteln nachweisbar, oder
- **SOLL / offen:** ein Ziel, eine Bedingung oder ein Schritt, der **nicht** freigegeben und/oder
  **nicht** gegen ein echtes Meta-Konto nachgewiesen ist.

SOLL/offen-Aussagen sind ausdrücklich **keine** Zusage, dass etwas funktioniert oder bereits
live genutzt wird. Diese ADR ändert **keine** Produktivkonfiguration, aktiviert **kein** Live-Publishing
und behauptet **keine** Meta-Fähigkeit oder einen geglückten Testpost.

---

## 1. Entscheidung in Kurzform

**IST (belegbar):** Eine Publishing-Infrastruktur ist vorhanden: Migration V28 mit eigener
Jobtabelle und Gate-/Versuchs-/Befundtabellen, ein serverseitiger Gate-Auswerter, ein
idempotenter Job-Store mit Lease, ein Graph-API-Gateway-Adapter, ein opt-in Worker sowie eine
ausschließlich lesende Reconciliation. Sie ist in mehreren Ebenen fail-closed:

- `NOIZY_PUBLISH_ENABLED=false` ist der Default in `application.properties`; in
  `deploy/server/compose.yaml` steht hart `NOIZY_PUBLISH_ENABLED: "false"`.
  Die `.env.example` hält Publishing ebenfalls deaktiviert.
- Der Worker-Bean entsteht nur bei `noizy.instagram.worker.enabled=true` **und**
  `noizy.publish.enabled=true`; der Gateway-Bean nur bei
  `noizy.instagram.gateway.enabled=true` **und** vorhandenem Token. Andernfalls greifen
  Fail-Closed-Beans, die jede Operation verweigern.
- Alle sechs konfigurativen Review-Gate-Freigaben sind standardmäßig `false`, insbesondere
  `noizy.instagram.gates.additional-workflow-defined`.

Es werden **keine** Beiträge automatisch gepostet. Es gibt keinen OAuth-Flow, keine
Tokenvergabe, keine Account-Verbindung und keinen bestandenen Meta-PoC.

**Entscheidung (weiterhin NO-GO):** Die Infrastruktur darf erst nach separater, ausdrücklicher
fachlicher Freigabe aktiviert werden. Voraussetzungen siehe Abschnitt 10. Das bloße Setzen
eines Flags ist keine Freigabe.

**Nicht-Ziele der Aktivierung (hart):** kein Live-Publishing, kein bestandener Meta-PoC, keine
Behauptung einer Meta-Capability, keine Tokens in der Konfiguration, keine Account-Verbindung,
kein Deployment und keine Produktivdatenänderung.

---

## 2. Was tatsächlich im Code liegt (IST, belegbar)

| Artefakt | Beleg | Bedeutung |
|---|---|---|
| Migration `V28__instagram_publishing.sql` | `backend/src/main/resources/db/migration/` | Schema `publishing` mit `instagram_publish_job` (UNIQUE `idempotency_key`, `UNIQUE(draft_id, account_id)`, `lease_owner`/`lease_until`, Status-Enum, `media_id`/`permalink`, `needs_reconciliation`), `instagram_publish_gate_result` (PK `job_id` + `gate_key`), `instagram_publish_attempt` (`UNIQUE(job_id, attempt_no)`) und `instagram_remote_publication` (`media_id` als PK). Audit-Trigger greifen auf die vorhandenen V25-Funktionen zurück. Es werden keine Tokens gespeichert. |
| `PublishEligibilityPort`, `ConfiguredPublishEligibility` | `backend/.../instagram/` | Serverseitige Entscheidung über 12 Pflicht-Gates inklusive `ADDITIONAL_WORKFLOW_DEFINED`. Ohne `noizy.publish.enabled=true` (exakt, nur `true`) wird verweigert. Kein HTTP, keine Tokens, kein Versand. |
| `ServerSideGateEvaluator`, `PublishFactReader`, `PublishFacts`, `GateEvaluation` | `backend/.../instagram/gates/` | Ermittelt Gate-Ergebnisse aus Datenbankfakten statt aus HTTP-Parametern. |
| `WorkflowGateApprovals`, `InstagramGateConfiguration` | `backend/.../instagram/gates/` | Sechs konfigurative Freigaben (`post-order-selected`, `weekend-day-balance-reviewed`, `posting-plan-current`, `handles-reviewed`, `caption-stale-check`, `additional-workflow-defined`), alle Default `false`. |
| `PublishJobStore`, `PublishJob` | `backend/.../instagram/store/` | Idempotentes Enqueue, exklusives Claimen mit `FOR UPDATE SKIP LOCKED` und Lease, Statusübergänge und Versuchsprotokoll. Parallele Worker können denselben Job nicht doppelt claimen. |
| `InstagramGateway`, `GraphApiInstagramGateway`, `FailClosedInstagramGateway` | `backend/.../instagram/gateway/` | Adapter für Container anlegen, Containerstatus lesen, `media_publish` aufrufen sowie `fetchPublication`/`recentMedia`. Der Produktiv-Bean entsteht nur bei Opt-in **und** Token; der Adapter implementiert **keinen** OAuth-Code-Flow, keine Tokenvergabe und keinen Account-Austausch. Sonst verweigert die Fail-Closed-Bean. |
| `InstagramWorkerConfiguration`, `PublishWorker` | `backend/.../instagram/worker/` | Opt-in-Scheduler mit konfigurierbarem Intervall, Lease und Batchgröße; zusätzlich an `noizy.publish.enabled` gebunden. |
| `PublishJobProcessor` | `backend/.../instagram/worker/` | Führt einen geclaimten Job aus: Gate-Prüfung, optional nur mit geprüfter öffentlicher HTTPS-Bild-URL, Container-Polling, höchstens **ein** `media_publish`-Aufruf pro Job/Versuch. Timeout bei `media_publish` → `UNKNOWN_REMOTE_RESULT` + read-only Reconciliation, niemals ein blindes Retry. |
| `ReconciliationService` | `backend/.../instagram/worker/` | Klärt unbekannte Resultate ausschließlich mit `fetchPublication`/`recentMedia`; erzeugt keinen Container und ruft kein `media_publish` auf. Ohne Treffer bleibt der Status ausdrücklich unbekannt. |
| `PublicationStatus`, `AudioStatus`, `PublishAttempt`, `RemotePublication`, `ContainerStatus`, `MediaContainerResult`, `PublishResult` | `backend/.../instagram/` | Statusmodell nach Kapitel 5.2; Audio bleibt `NOT_VERIFIABLE`. |
| Migration `V29__external_event_sources.sql`, `ExternalCalendarSourceService`, `ExternalCalendarParser` | `backend/src/main/resources/db/migration/`, `backend/.../discovery/external/` | Generische, konfigurativ freigeschaltete externe ICS-/JSON-Kalenderquellen als Fallback für JS-lastige Quellen. Keine MTC-URL wird erfunden; kein Live-Erfolg wird behauptet. Gehört zur Discovery, nicht zum Versand. |
| Tests | `backend/src/test/java/de/noizycologne/backend/instagram/`, `.../discovery/external/` | Unit- und Integrationstests für Flags/Gates, Job-Store/Idempotenz/Lease, Processor-Timeouts, Reconciliation und externe Kalenderquellen. Sie laufen ohne echte Meta-Konten und ohne Live-Posting. |

Der vorhandene `PublishEligibilityService` in `cancellation/` prüft nur Absage-/Änderungs-Blocker;
er ist **kein** Publisher und kennt den Publishing-Flag nicht. Er bleibt unangetastet.

---

## 3. Pflicht-Pre-Publish-Gates (IST implementiert, fachlich offen)

Quellen: Kapitel 17.2 und 20.8. Die 12 Gates sind im Code als
`ConfiguredPublishEligibility.REQUIRED_GATES` festgehalten und werden serverseitig ausgewertet;
ein fehlendes oder nicht erfülltes Gate blockiert (Kapitel 17.1). **Erfüllt** sind sie damit nicht:
die Review-Gates benötigen ausdrückliche fachliche Freigabe.

| Gate-Key | Ziel | Regeltyp | Stand |
|---|---|---|---|
| `EVENT_DATA_VALIDATED` | Termin, Venue, Pflichtangaben geprüft | hart | implementiert, Fachdaten zu prüfen |
| `MEDIA_VALIDATED` | gültiges, freigegebenes JPEG; kein Platzhalter | hart | implementiert, Fachdaten zu prüfen |
| `CAPTION_APPROVED` | Text und Handles geprüft | hart | implementiert, Fachdaten zu prüfen |
| `POST_ORDER_SELECTED` | eindeutige Reihenfolge | hart | Freigabe-Flag Default `false` |
| `VENUE_FEED_DIVERSITY` | keine Venue-Wiederholung im 3er-Fenster | hart | implementiert, Fachdaten zu prüfen |
| `WEEKEND_DAY_BALANCE_REVIEWED` | Tagesmix geprüft; begründeter Hinweis möglich | Review | Freigabe-Flag Default `false` |
| `POSTING_PLAN_CURRENT` | Planversion und Live-Feed noch gültig | hart | Freigabe-Flag Default `false` |
| `ADDITIONAL_WORKFLOW_DEFINED` | weitere angekündigte Vor-Publishing-Regeln vollständig spezifiziert | **BLOCKED** | Default `false` |
| `VENUE_RESOLVED` (20.8) | Event exakt einer geprüften `venue_id` zugeordnet | hart | implementiert, Fachdaten zu prüfen |
| `LINEUP_UNAMBIGUOUS` (20.8) | keine ungeklärten Performer-Kandidaten in geplanten @Mentions | hart | implementiert, Fachdaten zu prüfen |
| `HANDLE_REVIEWED` (20.8) | automatisch gesetzte IG-Handles aus geprüften Stammdaten | hart | Freigabe-Flag `handles-reviewed` Default `false` |
| `CAPTION_STALE_CHECK` (20.8) | Caption nach Stammdatenänderung erneut prüfen | hart | Freigabe-Flag `caption-stale-check` Default `false` |

Die sechs konfigurativen Review-Freigaben (`post-order-selected`, `weekend-day-balance-reviewed`,
`posting-plan-current`, `handles-reviewed`, `caption-stale-check`, `additional-workflow-defined`)
müssen vor einer Aktivierung einzeln fachlich abgenommen und gesetzt werden. `ADDITIONAL_WORKFLOW_DEFINED`
bleibt blockiert, solange die angekündigte zusätzliche Workflowstufe nicht spezifiziert ist.

---

## 4. Meta-Login-Optionen (SOLL / offen, nicht identisch)

**Empfehlung (SOLL, Kapitel 4.2):** Facebook Login mit verknüpftem Professional-Instagram-Konto
als erste zu prüfende Integration, weil optional die Instagram Audio API für die Suche vorgesehen
ist. Ist die Voraussetzung nicht erfüllt, muss Foto-Publishing alternativ über **Instagram Login**
möglich bleiben.

**Wichtig (belegbar in Kapitel 4.2):** Die zwei OAuth-Verfahren sind **nicht identisch** – sie
unterscheiden sich in Tokens, Berechtigungen und Endpunkten. OAuth-Code und Tokens dürfen nicht
blind vermischt werden.

**Verbindlich erst nach echtem Meta-PoC festlegbar (offen):** welche Variante NoizyCologne
tatsächlich autorisieren kann, welche Musik-Suchfunktionen verfügbar sind und welche Rechte
Veröffentlichung erfordert. Der vorhandene Adapter nimmt ein bereits bereitgestelltes Token
entgegen; er implementiert **keinen** OAuth-Flow. Diese ADR trifft dazu **keine** Entscheidung.

---

## 5. Graph-API-Flow (IST implementiert, nicht gegen Meta nachgewiesen)

Der Ablauf entspricht Kapitel 8.3 und ist im Code umgesetzt:

1. Eligibility / Planversion / Live-Feed prüfen (siehe Abschnitt 3).
2. Job exklusiv claimen; Status `CLAIMED`.
3. JPEG über öffentlich abrufbare, zeitlich begrenzte HTTPS-URL bereitstellen
   (`noizy.instagram.media-public-base-url`; leer = kein Versand).
4. Medien-Container mit Caption anlegen → `MediaContainerResult` (container_id).
5. Container-Status abrufen, bis Meta die Veröffentlichung erlaubt → `ContainerStatus`
   (begrenzt durch `noizy.instagram.gateway.max-container-polls`).
6. `media_publish` exakt kontrolliert **einmal** ausführen → `PublishResult`.
7. `media_id`/`permalink` speichern; Status `PUBLISHED`.
8. Veröffentlichung dem Wochenend-Batch zuordnen; **keine Einzel-Push-Erzeugung**.
9. Bei Timeout: `UNKNOWN_REMOTE_RESULT`, erst Remote-Zustand abgleichen.

**Grenze (belegbar, Kapitel 8.3):** Der Graph-API-Container ist ein Publishing-Zwischenschritt,
**kein** Instagram-App-Entwurf. Änderung eines freigegebenen Entwurfs nach Container-Erzeugung
muss erneut geprüft werden (vgl. 23.3: offene unveränderliche Medienversion). Es wurde **kein**
echter Meta-Aufruf und kein Testpost ausgeführt.

---

## 6. Retry, `UNKNOWN_REMOTE_RESULT` und Reconciliation (IST implementiert)

- Ein Timeout an der externen Grenze führt zu `UNKNOWN_REMOTE_RESULT`, niemals zu einem
  blinden erneuten `media_publish` (Kapitel 5.2, 8.3). Ein Retry ohne Kenntnis des
  Remote-Zustands erzeugt einen Doppelpost.
- `PublishJobProcessor` ruft `publishContainer` höchstens einmal je Job/Versuch auf;
  `GatewayTimeoutException` wird zu `PublishAttempt.unknownRemoteResult()` abgebildet.
- `ReconciliationService` klärt den Zustand ausschließlich mit den lesenden Methoden
  `fetchPublication` und `recentMedia`. Es wird nie ein Container erzeugt oder
  `media_publish` aufgerufen; ein fehlender Befund bleibt ausdrücklich unbekannt.
- `instagram_remote_publication` speichert beobachtete Media-IDs/Permalinks; die
  `instagram_publish_job` trägt `needs_reconciliation`.
- Ein manuell gelöschter Beitrag kann gesondert zu `REMOVED_EXTERNALLY` reconciled werden
  (Kapitel 5.2). `PUBLISHED` darf erst nach bestätigtem Erfolg gesetzt werden;
  `UNKNOWN_REMOTE_RESULT` ist kein Erfolg.

---

## 7. Audio und Stories – manuell, `NOT_VERIFIABLE` (SOLL / offen)

- **IST (belegbar, Kapitel 5.2 und 8.4):** `AudioStatus = { NOT_APPLICABLE,
  MANUAL_AUDIO_PENDING, NOT_VERIFIABLE }`. `NOT_VERIFIABLE` darf **nicht** als
  `COMPLETED`/`CONFIRMED` ausgegeben werden. Die Infrastruktur meldet nach einem
  (nicht stattgefundenen) Feed-Post keinen Audio-Erfolg.
- **SOLL (Kapitel 8.5):** PoC-Testprotokoll, ob ein API-gepostetes Foto auf iPhone/Android nachträglich
  mit Musik versehen werden kann. Abbruchkriterium: falls nicht möglich, manueller
  Notification-Publishing-Fallback. Ein veröffentlichter Beitrag darf nicht als „mit Musik fertig“
  gelten.
- **SOLL (Kapitel 8.6):** Story-Reshares und @Mentions erfolgen **ausschließlich manuell** in der
  nativen App. Freitag: alle FR- und SA-Feed-Beiträge als Stories; Samstag: **nur** SA erneut – kein
  zweiter Feed-Post, kein erneuter Graph-API-Publish-Aufruf, kein Duplikat. Keine Story-Push,
  kein Erledigt-Zwang in der PWA.

---

## 8. Batch- und Sammel-Push-Regeln (SOLL / offen)

Kapitel 8.4 und 5.1 sind verbindlich; die Publishing-Infrastruktur lässt einen solchen Ablauf
zu, er ist aber nicht fachlich freigegeben:

- Ein Wochenende / Instagram-Account bildet **einen Batch** (`weekend_batch.friday_date`).
- **Genau eine** automatische `AUDIO_DIGEST`-Benachrichtigung pro Batch und zuständiger Person –
  nie 24 Einzel-Pushs, kein `Instagram → PWA bestätigen → Instagram`-Pingpong.
- Zeitpunkt: nach dem letzten relevanten Posting oder einmal zum bewusst gewählten Cutoff. Späte
  Beiträge lösen **keine zweite** automatische Audio-Push aus; ein fehlgeschlagener Push erzeugt
  keine Einzel-Pushs.
- `UNIQUE(batch_id, recipient_id, notification_kind)` + transaktionale Outbox verhindern
  Doppel-Pushs bei Retry, Worker-Neustart und parallelem Scheduler. Web Push garantiert keine
  exakt einmalige Zustellung auf dem Endgerät; das `tag` ersetzt keine serverseitige
  Einmaligkeitsregel.
- Die Zahl im Hinweis enthält nur **wirklich veröffentlichte** Posts; Fehler erscheinen in der
  optionalen Übersicht. Bei fehlendem technischen Audio-Nachweis niemals „24/24 Musik erledigt“.

---

## 9. Tokens, Redirect-URI und Capabilities (IST Grenze / SOLL offen)

- **IST:** Das Gateway nimmt einen bereits durch das Team bereitgestellten Access-Token aus
  `INSTAGRAM_ACCESS_TOKEN` entgegen. Es wird **nicht** in der Datenbank gespeichert, nicht in
  Frontend-Responses gegeben und nicht geloggt. `application.properties` und
  `deploy/server/.env.example` enthalten nur leere Platzhalter; `deploy/server/compose.yaml`
  reicht sie optional durch. Ohne Token bleibt die Fail-Closed-Bean aktiv.
- **IST:** Es gibt **keinen** OAuth-Flow, keine Tokenvergabe, keinen Account-Austausch und keine
  Redirect-URI-Implementierung.
- **SOLL/offen:** Tokens verschlüsselt speichern, niemals in Frontend-Responses, OAuth-`state`
  sicher und einmalig speichern, CSRF-Schutz, minimal erforderliche Scopes und eine exakt mit
  der Meta-Konfiguration übereinstimmende Redirect-URI (Kapitel 8.2, 10.3, 16).
- **SOLL/offen (Voraussetzung):** Erst mit echtem Konto testen, welche Login-Variante
  autorisierbar ist, welche Rechte `media_publish` benötigt und ob die optionale Audio-Suche
  verfügbar ist (Kapitel 4.2, R03, Modul 9). Bis dahin kein Account verbinden, kein Testpost
  auf einem produktiven Konto.

---

## 10. Go/No-Go-Kriterien (Aktivierung weiterhin NO-GO)

Die technische Infrastruktur ist implementiert. Ein echtes Live-Publishing ist **nur** denkbar,
wenn **alle** folgenden Punkte zutreffen. Aktuell trifft die fachliche Freigabe nicht zu; daher
lautet die Entscheidung weiterhin **NO-GO**.

| Kriterium | Aktuell |
|---|---|
| Infrastruktur (Migration, Gates, Idempotenz/Lease, Gateway-Adapter, Worker, Reconciliation, Tests) implementiert | **IST** |
| Alle 12 Pflicht-Gates aus Kapitel 17/20.8 inklusive `ADDITIONAL_WORKFLOW_DEFINED` fachlich spezifiziert, abgenommen und die sechs Review-Freigaben gesetzt | offen / `BLOCKED` (alle Flags `false`) |
| Zusätzliche angekündigte Workflowstufe vor dem Posting vollständig definiert und freigegeben | offen |
| Meta-PoC mit echtem Konto: Login-Variante, Capabilities/Permissions, Foto-Publish, Retry ohne Doppelpost dokumentiert | offen |
| Musik-PoC (8.5) und Story-/Mention-Ablauf (8.6) auf Android/iOS geprüft | offen |
| Access-Token bereitgestellt und geprüfte öffentliche HTTPS-Medien-URL konfiguriert (`INSTAGRAM_ACCESS_TOKEN`, `INSTAGRAM_MEDIA_PUBLIC_BASE_URL`) | offen / leer |
| CI FULL für den **exakt** zu aktivierenden Commit erfolgreich und Branch Protection für `main` durch einen Administrator aktiviert | offen |
| `NOIZY_PUBLISH_ENABLED` bewusst und ausdrücklich auf `true` gesetzt (nicht versehentlich) | **false** |

**Expliziter Vorbehalt:** Die Aktivierung echter Publishing-Funktionalität erfolgt
**ausschließlich nach separater, ausdrücklicher fachlicher Freigabe der sechs Review-Gates,
der zusätzlichen Workflowdefinition und eines bestandenen Meta-PoC**. Diese ADR und der
vorhandene Code stellen keine solche Freigabe dar. Es wird kein Live-Posting und keine
Meta-Fähigkeit behauptet.

---

## 11. Rollback-technische Grenzen (SOLL / offen)

- **Kein `DELETE`-Versprechen.** Kapitel 8.1 stuft `DELETE /{media-id}` als unklar/versions- und
  authabhängig ein; für V1 ist es **nicht garantiert** und standardmäßig deaktiviert. Keine
  ungetestete `DELETE`-Implementierung als produktives Versprechen.
- Verlässlicher Fallback ist der manuelle App-Workflow (`Instagram öffnen → Post entfernen`) und
  anschließend `mark-removed` im eigenen System.
- Nach Instagram-Erfolg darf **nicht** behauptet werden, ein lokales Löschen entferne den
  Instagram-Post (Kapitel 5.3).
- Vor `PUBLISHING` ist die lokale Planung sauber stornierbar; bei laufendem API-Aufruf wird der
  Status gesperrt/geprüft, nicht als garantiert abgebrochen dargestellt (Kapitel 5.3).
- Ein technisches Deployment ist **nicht** gleichbedeutend mit der Freigabe zum Posting
  (`NOIZY_PUBLISH_ENABLED=false`, Kapitel 3.7/23.1).

---

## 12. Verifikation und Grenzen

- **Gezielte Backend-Tests (IST, im Branch vorhanden):** Unit- und Integrationstests unter
  `backend/src/test/java/de/noizycologne/backend/instagram/` und
  `backend/src/test/java/de/noizycologne/backend/discovery/external/`. Sie prüfen unter anderem
  Flag/Fail-Closed-Verhalten, Gate-Auswertung, Idempotenz und Lease des Job-Store,
  `UNKNOWN_REMOTE_RESULT` ohne zweiten Publish-Aufruf, lesende Reconciliation sowie externe
  ICS-/JSON-Quellen. Kein Netzwerkzugriff auf Meta, keine echten Konten, keine Tokens.
- **Commit-Gate (IST):** Der verpflichtende Pre-Commit-Hook führt QUICK aus. FULL läuft
  ausschließlich in GitHub Actions und bleibt vor Merge/`main` und Deployment für den exakten
  akzeptierten Commit verbindlich; die Branch Protection muss dafür aktiviert sein.
- **Grenze:** Ein erfolgreicher CLI-Lauf ist kein Nachweis für die Live-IDE-Diagnosen; ein
  solcher Zugriff wird im Abschluss ausdrücklich benannt. Diese ADR behauptet **keinen**
  End-to-End-Test gegen Meta und **keine** Produktivfreigabe.
- **Live-Publishing weiterhin deaktiviert.** `NOIZY_PUBLISH_ENABLED` bleibt `false`; die
  Gateway-/Worker-Opt-ins und Review-Freigaben bleiben `false`, es gibt keine Tokenvergabe,
  keine Account-Verbindung und keinen echten Meta-HTTP-Aufruf.
