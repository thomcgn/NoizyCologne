# ADR 0001: Instagram-Publishing-Grenze – Konzept und sichere Code-Skizze

- **Status:** Vorgeschlagen (nicht freigegeben)
- **Datum:** 24.11.2026
- **Grundlage:** `AGENTS.md`, `docs/QUALITY.md`, `docs/PROJECT.md` (insb. 3.2, 4.2, 5.2, 8, 17, 20.8, 23)
- **Umfang:** Paket I – Architektur-Entscheidungsdokument + sichere, bewusst inaktive Code-Skizze

## Lesart dieser ADR

Jede Aussage ist entweder

- **IST (belegbar):** im aktuellen Code oder in den zitierten Projektkapiteln nachweisbar, oder
- **SOLL / offen:** ein Ziel, eine Bedingung oder ein Entwurf, der **nicht** implementiert und
  **nicht** freigegeben ist.

SOLL/offen-Aussagen sind ausdrücklich **keine** Zusage, dass etwas funktioniert oder bereits
existiert. Diese ADR ändert **keine** Produktivkonfiguration und aktiviert **kein** Live-Publishing.

---

## 1. Entscheidung in Kurzform

**IST (belegbar):** Es gibt keinen Instagram-Publisher, keine Publish-Queue und keinen Scheduler,
der Beiträge sendet. `NOIZY_PUBLISH_ENABLED=false` ist als Default in der Vorlagen-`.env`
(`docs/PROJECT.md:1144`) und im Compose-Overlay (`docs/PROJECT.md:2321`) festgehalten. Gespeicherte
und freigegebene Redaktionsentwürfe (`DRAFT`, `READY_FOR_REVIEW`, `APPROVED`, vgl. Kapitel 23.9)
senden nichts (Kapitel 23.8: „Kein Instagram-Publisher und keine Versandwarteschlange vorhanden“).

**Entscheidung (SOLL/offen):** Die fachliche und technische Grenze wird als `InstagramGateway`
+ serverseitiger `PublishEligibilityPort` modelliert. Eine echte Publishing-Implementierung
(HTTP, OAuth, Tokens) darf **erst nach separater, ausdrücklicher Freigabe der fachlichen
Pflicht-Gates und eines bestandenen Meta-PoC** entstehen. Bis dahin bleibt
`NOIZY_PUBLISH_ENABLED=false` und die Skizze fail-closed.

**Nicht-Ziele dieses Pakets (hart):** kein Live-Publishing, keine echten Meta-HTTP-Calls, keine
Tokens/Secrets, keine Account-Verbindung, kein Deployment, keine Migration, keine neue Tabelle,
kein sendender Scheduler, keine Änderung an `application.properties`.

---

## 2. Was in diesem Paket tatsächlich im Code liegt (IST, belegbar)

| Artefakt | Beleg | Bedeutung |
|---|---|---|
| `InstagramGateway` | `backend/.../instagram/InstagramGateway.java` | Reines Java-Interface mit exakt den Signaturen aus `docs/PROJECT.md:712`. Kein `RestClient`, kein HTTP. |
| `InMemoryInstagramGateway` | `backend/src/test/java/...` | Deterministisches Mock, **ausschließlich in Testquellen**. Kein Produktiv-Bean, kein Netzwerk, keine Tokens. |
| `PublishEligibilityPort` + `ConfiguredPublishEligibility` | `backend/.../instagram/` | Fail-closed Gate-Sketch. Liest `noizy.publish.enabled` über `@Value(..., Default false)` (entspricht Env `NOIZY_PUBLISH_ENABLED`). Nur der exakte Wert `true` aktiviert; `null`, leer oder unbekannt → `false`. |
| `PublishCoordinator` | `backend/.../instagram/` | Plain-Java-Skizze für **einen** expliziten Versuch. Kein `@Scheduled`, kein `@Component`, kein Automatismus. |
| `PublicationStatus`, `AudioStatus`, `PublishAttempt` | `backend/.../instagram/` | Statusmodell nach Kapitel 5.2. `PublishAttempt.audioStatus()` liefert immer `NOT_VERIFIABLE`. |
| Tests | `backend/src/test/java/de/noizycologne/backend/instagram/` | Sechs Unit-Tests, alle ohne Datenbank und ohne Netzwerk. |

Der vorhandene `PublishEligibilityService` in `cancellation/` prüft nur Absage-/Änderungs-Blocker;
er ist **kein** Publisher und kennt den Publishing-Flag nicht. Er bleibt unangetastet.

---

## 3. Offene Pflicht-Pre-Publish-Gates (SOLL / offen)

Quellen: Kapitel 17.2 und 20.8. **Keines** dieser Gates ist in diesem Paket implementiert oder
abgenommen. Die Aufzählung im Sketch (`ConfiguredPublishEligibility.REQUIRED_GATES`) ist eine
**Absichtserklärung**, keine Erfüllung. Ein Event darf `READY_TO_PUBLISH` erst erreichen, wenn
alle für die Workflow-Version erforderlichen Gates erfüllt sind; ein fehlendes oder falsch
konfiguriertes Pflicht-Gate blockiert (Kapitel 17.1).

| Gate-Key | Ziel (SOLL) | Regeltyp | Stand |
|---|---|---|---|
| `EVENT_DATA_VALIDATED` | Termin, Venue, Pflichtangaben geprüft | hart | offen |
| `MEDIA_VALIDATED` | gültiges, freigegebenes JPEG; kein Platzhalter | hart | offen |
| `CAPTION_APPROVED` | Text und Handles geprüft | hart | offen |
| `POST_ORDER_SELECTED` | eindeutige Reihenfolge | hart | offen |
| `VENUE_FEED_DIVERSITY` | keine Venue-Wiederholung im 3er-Fenster | hart | offen |
| `WEEKEND_DAY_BALANCE_REVIEWED` | Tagesmix geprüft; begründeter Hinweis möglich | Review | offen |
| `POSTING_PLAN_CURRENT` | Planversion und Live-Feed noch gültig | hart | offen |
| `ADDITIONAL_WORKFLOW_DEFINED` | weitere angekündigte Vor-Publishing-Regeln vollständig spezifiziert | **BLOCKED** | offen |
| `VENUE_RESOLVED` (20.8) | Event exakt einer geprüften `venue_id` zugeordnet | hart | offen |
| `LINEUP_UNAMBIGUOUS` (20.8) | keine ungeklärten Performer-Kandidaten in geplanten @Mentions | hart | offen |
| `HANDLE_REVIEWED` (20.8) | automatisch gesetzte IG-Handles aus geprüften Stammdaten | hart | offen |
| `CAPTION_STALE_CHECK` (20.8) | Caption nach Stammdatenänderung erneut prüfen | hart | offen |

Diese Tabelle ersetzt die vier Gates aus 20.8 **nicht** und schließt die angekündigten Schritte
aus Kapitel 17 **nicht** ab. Kapitel 20.8 formuliert ausdrücklich: `NOIZY_PUBLISH_ENABLED=false`
bleibt bis zur gesamten Freigabe gesetzt.

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
Veröffentlichung erfordert. Diese ADR trifft dazu **keine** Entscheidung.

---

## 5. Graph-API-Flow (SOLL / offen)

Beabsichtigter Ablauf (Kapitel 8.3), **nicht** implementiert:

1. Eligibility / Planversion / Live-Feed prüfen (siehe Abschnitt 3).
2. Job exklusiv claimen; Status `CLAIMED`.
3. JPEG über öffentlich abrufbare, zeitlich begrenzte HTTPS-URL bereitstellen.
4. Medien-Container mit Caption anlegen → `MediaContainerResult` (container_id).
5. Container-Status abrufen, bis Meta die Veröffentlichung erlaubt → `ContainerStatus`.
6. `media_publish` exakt kontrolliert **einmal** ausführen → `PublishResult`.
7. `media_id`/`permalink` speichern; Status `PUBLISHED`.
8. Veröffentlichung dem Wochenend-Batch zuordnen; **keine Einzel-Push-Erzeugung**.
9. Bei Timeout: `UNKNOWN_REMOTE_RESULT`, erst Remote-Zustand abgleichen.

**Grenze (belegbar, Kapitel 8.3):** Der Graph-API-Container ist ein Publishing-Zwischenschritt,
**kein** Instagram-App-Entwurf. Änderung eines freigegebenen Entwurfs nach Container-Erzeugung
muss erneut geprüft werden (vgl. 23.3: offene unveränderliche Medienversion).

---

## 6. Retry, `UNKNOWN_REMOTE_RESULT` und Reconciliation (SOLL / offen)

- **SOLL:** Ein Timeout an der externen Grenze führt zu `UNKNOWN_REMOTE_RESULT`, niemals zu einem
  blinden erneuten `media_publish` (Kapitel 5.2, 8.3). Ein Retry ohne Kenntnis des Remote-Zustands
  erzeugt einen Doppelpost.
- **IST (belegbar im Sketch):** `PublishCoordinator` ruft `publishContainer` genau einmal auf;
  `GatewayTimeoutException` wird zu `PublishAttempt.unknownRemoteResult()` abgebildet. Der Test
  `timeoutYieldsUnknownRemoteResultWithoutSecondPublishCall` prüft `publishCalls() == 1`.
- **SOLL / offen (bewusst nicht implementiert):** Reconciliation über `fetchPublication` und
  `recentMedia` (beide im Interface deklariert), um den tatsächlichen Remote-Zustand zu klären.
  `PUBLISHED` darf erst nach bestätigtem Erfolg gesetzt werden; `UNKNOWN_REMOTE_RESULT` ist kein
  Erfolg.
- **SOLL / offen:** Ein manuell gelöschter Beitrag wird gesondert zu `REMOVED_EXTERNALLY`
  reconciled (Kapitel 5.2).

---

## 7. Audio und Stories – manuell, `NOT_VERIFIABLE` (SOLL / offen)

- **IST (belegbar, Kapitel 5.2 und 8.4):** `AudioStatus = { NOT_APPLICABLE,
  MANUAL_AUDIO_PENDING, NOT_VERIFIABLE }`. `NOT_VERIFIABLE` darf **nicht** als
  `COMPLETED`/`CONFIRMED` ausgegeben werden. Der Sketch liefert nach erfolgreichem Feed-Post
  weiterhin `NOT_VERIFIABLE`; entsprechend prüft der Test
  `publishedFeedPostNeverClaimsAudioSuccess`.
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

Kapitel 8.4 und 5.1 sind verbindlich, aber nicht implementiert:

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
- Das Zielschema (Kapitel 8.4) ist ein Entwurf, **keine** ausgeführte Migration. Dieses Paket
  erzeugt keine Tabelle.

---

## 9. Tokens, Redirect-URI und Capabilities (SOLL / offen)

- **Tokens verschlüsselt speichern**, niemals in Frontend-Responses, nicht loggen (Kapitel 8.2,
  10.3). Keine echten Access-Tokens in DTOs/Responses. Das Sketch-Modell `InstagramAccount`
  enthält bewusst nur eine `id`.
- **OAuth `state`** sicher und einmalig speichern (Ablaufdatum + Nutzer-/Session-Zuordnung),
  CSRF-Schutz, minimal erforderliche Scopes (Kapitel 8.2, 10.3).
- **Redirect-URI exakt** mit der Meta-Konfiguration übereinstimmen (Kapitel 8.2, 10.3,
  16: z. B. `https://app.example.de/api/instagram/oauth/callback`). Keine Näherungs- oder
  Platzhalter-URIs.
- **Capabilities/Permissions-PoC als Voraussetzung (offen):** Erst mit echtem Konto testen,
  welche Login-Variante autorisierbar ist, welche Rechte `media_publish` benötigt und ob die
  optionale Audio-Suche verfügbar ist (Kapitel 4.2, R03, Modul 9). Bis dahin kein Account
  verbinden, kein Token anlegen, kein Testpost auf einem produktiven Konto.

---

## 10. Go/No-Go-Kriterien (SOLL / offen)

Ein echtes Live-Publishing ist **nur** denkbar, wenn **alle** folgenden Punkte zutreffen. Aktuell
trifft keiner davon zu; daher lautet die Entscheidung **NO-GO**.

| Kriterium | Aktuell |
|---|---|
| Alle Pflicht-Gates aus Kapitel 17 (inkl. `ADDITIONAL_WORKFLOW_DEFINED`) spezifiziert, implementiert, getestet, abgenommen | offen / `BLOCKED` |
| Zusätzliche Gates aus 20.8 (`VENUE_RESOLVED`, `LINEUP_UNAMBIGUOUS`, `HANDLE_REVIEWED`, `CAPTION_STALE_CHECK`) erfüllt | offen |
| Meta-PoC mit echtem Konto: Login-Variante, Capabilities/Permissions, Foto-Publish, Retry ohne Doppelpost dokumentiert | offen |
| Musik-PoC (8.5) und Story-/Mention-Ablauf (8.6) auf Android/iOS geprüft | offen |
| Tokens verschlüsselt, nicht in Responses, Redirect-URI exakt, `state`/CSRF geprüft | offen |
| Batch-/Outbox-Dedupe und Reconciliation implementiert und getestet | offen |
| Vollständige QUICK-/CI-FULL-Prüfung für den exakt freigegebenen Commit | offen |
| `NOIZY_PUBLISH_ENABLED` bewusst und ausdrücklich auf `true` gesetzt (nicht versehentlich) | **false** |

**Expliziter Vorbehalt:** Die Implementierung echter Publishing-Funktionalität erfolgt **ausschließlich
nach separater, ausdrücklicher Freigabe der fachlichen Gates und des Meta-PoC**. Diese ADR und
die beigefügte Skizze stellen keine solche Freigabe dar.

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

## 12. Verifikation und Grenzen dieses Pakets

- **Gezielte Backend-Tests (IST, ausgeführt):** `InstagramPublishSketchTests` mit sechs Tests:
  Flag `false` verweigert trotz formal freigegebenem Entwurf; fehlender/ungültiger Flag →
  fail-closed; Mock liefert deterministische Container-/Publish-Ergebnisse; Timeout →
  `UNKNOWN_REMOTE_RESULT` ohne zweiten Publish-Aufruf; veröffentlichter Feed-Post meldet keinen
  Audio-Erfolg; Flag `true` mit offenem Workflow-Gate verweigert weiterhin. Kein Netzwerkzugriff,
  keine Datenbank, keine echten Tokens.
- **Commit-Gate (IST):** Der verpflichtende Pre-Commit-Hook führt QUICK aus. FULL läuft
  ausschließlich in GitHub Actions und bleibt vor Merge/`main` und Deployment für den exakten
  akzeptierten Commit verbindlich.
- **Grenze:** Ein erfolgreicher CLI-Lauf ist kein Nachweis für die Live-IDE-Diagnosen; ein
  solcher Zugriff wird im Abschluss ausdrücklich benannt. Diese ADR behauptet **keinen**
  End-to-End-Test gegen Meta und **keine** Produktivfreigabe.
- **Live-Publishing weiterhin deaktiviert.** `NOIZY_PUBLISH_ENABLED` bleibt `false`; es gibt
  weiterhin keinen echten Meta-HTTP-Aufruf, keine Tokenvergabe und keine Account-Verbindung.
