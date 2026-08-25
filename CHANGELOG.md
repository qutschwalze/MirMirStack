# Changelog — Companion

Alle Versionswechsel werden hier dokumentiert. Jeder Build erhöht `versionCode` + `versionName` (siehe `app/build.gradle.kts`).

## 0.4.0 / 8 (2026-08-25)

**Phase 3 – LLM-Zusammenfassungen + Theme-Umschalter**

- **KI-Pipeline:** Geteilte Inhalte werden jetzt per OpenAI-kompatiblem Endpoint zusammengefasst (Gemini-OpenAI-Endpoint u. a. funktionieren). Erzwungenes JSON (`title`, `summary_md`, `decisions`, `todos`, `participants`, `tags`), toleranter Parser (Codefences/Prosa drumherum ok), Pflichtfeld-Validierung — bei Fehlern FAILED mit klarer Meldung statt Müll ins Wiki.
- **Deterministisches HTML:** Die App rendert die Markdown-Zusammenfassung selbst (Golden-Tests) — nie LLM-Rohtml im Wiki. Entscheidungen und To-dos als eigene Abschnitte, Teilnehmer als Zeile.
- **Vorlagen:** Meeting-Protokoll / Recherche-Clip / Chat-Digest / Universal. Vorauswahl automatisch nach Quelle (Sherpa→Meeting, Browser→Recherche, WhatsApp→Chat), im Share-Screen per Chip änderbar.
- **LLM-Titel übernimmt:** Der Auto-Titel („mitten im Satz"-Problem aus Phase 2) wird durch den KI-Titel ersetzt.
- **Fallback ohne KI:** Kein LLM konfiguriert → Rohtext-Seite wie in Phase 2; Pipeline steht nie still.
- **Theme-Umschalter:** Hell / System / Dunkel im Einstellungen-Tab, sofort wirksam, gespeichert; gilt auch im Share-Screen.
- **Einstellungen erweitert:** LLM-Basis-URL (inkl. `/v1/`), API-Key, Modellname + „KI testen"-Button.
- Room-Migration v2 (`templateId`, `summaryMd`); Worker-Kette PROCESS→PUBLISH mit Transient-Fehler-Retry.
- Neue Tests: Parser (Codefence, Prosa, Pflichtfelder), MD-Renderer-Golden-Tests, Template-Routing — insgesamt 44.

## 0.3.1 / 7 (2026-08-25)

**Diagnose für den 403 beim Verbindungstest**

- Feldbefund: Nach Token-Eintrag meldet der Verbindungstest HTTP 403. Host-seitige Verifikation zeigt: korrekte Credentials liefern 200 (auch mit okhttp-UA), falsche/vertauschte liefern stets **401** mit klarer Meldung — ein 403 kommt also entweder von BookStack-Berechtigungen (Token-User ohne API-Rechte) oder von einem Proxy/WAF zwischen Handy und Server.
- Verbindungstest zeigt jetzt **HTTP-Code + Response-Body-Auszug + normalisierte URL** — der nächste Versuch liefert die exakte Ursache statt nur eines Codes.
- Version 0.3.1 (versionCode 7).

## 0.3.0 / 6 (2026-08-25)

**Phase 2 – BookStack-Anbindung (Publish ohne LLM)**

- **Einstellungen-Tab:** BookStack-URL, API-Token-ID/-Secret und Ziel-Buch-ID (Default 3 = „Meetings und Notizen"), verschlüsselt gespeichert via EncryptedSharedPreferences. Button „Speichern + Verbindung testen" prüft live gegen die API.
- **BookStack-API-Client** (Retrofit + kotlinx.serialization): Monatskapitel sichern (`ensureMonthlyChapter`), Seite anlegen/updaten mit Idempotenz über den Seitennamen (gleicher Name = neue Revision statt Duplikat), Original als Attachment (Multipart-Feld `file` + `uploaded_to` — Pitfalls aus dem Bestandsskill eingebaut). Wiki-Link wird aus echten Slugs gebaut, nicht erraten.
- **Publish-Worker** (WorkManager): Netzwerk-Constraint, exponentieller Backoff; nur Netzwerkfehler wiederholen sich automatisch, Konfigurationsfehler bleiben FAILED bis zur Nutzerkorrektur. Statuspflege QUEUED→RUNNING→DONE/FAILED in der Outbox.
- **Inbox verdrahtet:** QUEUED/FAILED-Einträge antippen = Publish starten/wiederholen; DONE zeigt Button „Wiki-Seite öffnen" (echte Wiki-URL).
- **Seitenformat:** Kapitel `YYYY-MM`, Seite `YYYY-MM-DD <Titel>`, HTML deterministisch aus dem Rohtext escapet (Phase 3 ersetzt das durch LLM-Zusammenfassungen).
- Release-Builds jetzt ohne R8-Minify (stability-first: keine Reflection-/Serializer-Fallstricke).
- Neue Tests: BookStack-Client gegen MockWebServer (Auth-Header, URL-Normalisierung, Kapitel-Idempotenz, Upsert-Pfade, Multipart-Semantik, Slug-URL) + Publisher-Unit-Tests (HTML-Escaping, Attachment-Namen). Insgesamt 15+11=26 Tests.

## 0.2.3 / 5 (2026-08-25)

**Fix – Share-Crash-Ursache gefunden und behoben**

- **Root Cause (via CrashGuard 0.2.2 verifiziert):** `ClassNotFoundException` beim Start der ShareActivity. Die Klasse liegt im Paket `com.heddrich.companion.share`, das Manifest verwies aber auf `com.heddrich.companion.ShareActivity`. Beim Teilen versuchte Android, die nicht existierende Klasse per Reflection zu laden — Instant-Crash vor jeder eigenen Codezeile. Erklärt alle Befunde seit 0.2.0: direkter Start funktionierte (MainActivity-Pfad korrekt), Teilen crashte sofort, Unit-Tests blieben grün.
- **Fix:** Manifest-Eintrag korrigiert auf `.share.ShareActivity`.
- CrashGuard + Diagnose-Sektion + Selbsttest bleiben dauerhaft im Build (früher Befund, schneller Fix).

## 0.2.2 / 4 (2026-08-25)

**Diagnose-Build für den Share-Crash**

Der Feldbefund bleibt: Beim Teilen aus fremden Apps schließt sich die App sofort, direkter Start funktioniert. Da die 0.2.1-Härtung des Empfangspfads nichts änderte, liegt der Crash offenbar außerhalb dieses Pfads (vermutlich Composition oder Activity-Start). Statt weiter zu raten: **dieser Build macht jeden Absturz sichtbar** (Sherpa-Diagnose-Pattern).

- **CrashGuard:** Globaler Uncaught-Exception-Handler. Schreibt jeden Absturz in `files/crash/last_crash.txt` und zusätzlich als FAILED-Eintrag in die Outbox — der Befund überlebt den Prozesscrash.
- **Info-Tab → Diagnose-Sektion:** Letzter Crash wird angezeigt und kann per Button in die Zwischenablage kopiert werden.
- **Empfangs-Selbsttest** (Button im Info-Tab): Durchläuft denselben Codepfad wie ein echter Share (Intent bauen → Extraktion → Detektion → DB-Roundtrip) und liefert eine Ein-Zeilen-Diagnose.
- Version 0.2.2 (versionCode 4).

**Nächster Schritt nach Installation:** Teilen erneut versuchen → wenn Absturz: App normal öffnen → Info-Tab → „In Zwischenablage" + Selbsttest-Ergebnis schicken. Damit haben wir die exakte Ursache statt Vermutungen.

## 0.2.1 / 3 (2026-08-25)

**Bugfix – Share-Empfang schloss sich sofort**

- **Feldbefund (Gerätetest zu 0.2.0):** Beim Teilen aus anderen Apps schloss sich die App sofort wieder; direkter Start funktionierte.
- **Gegenmaßnahme:** Der komplette Empfangspfad (Intent-Auswertung, Datei-Lesen, DB-Schreiben) läuft jetzt ausserhalb des Hauptthreads in einem einzigen abgesicherten Block. Jede Ausnahme wird abgefangen: Die App zeigt einen Fehlerbildschirm mit Meldung + Stacktrace und protokolliert den Fehler zusätzlich als FAILED-Eintrag in der Inbox – kein kommentarloses Verschwinden mehr.
- Datei-Lesen jetzt gestreamt mit hartem Zeichenlimit (kein Voll-Laden grosser Dateien).
- Umbenennung: App-Label heisst jetzt **MirMirStack** (Launcher + Sharesheet „Mit MirMirStack teilen"), passend zum Repo-Namen.

## 0.2.0 / 2 (2026-08-25)

**Phase 1 – Share Target + Outbox**

- ShareActivity als Share Target: empfaengt `ACTION_SEND` / `ACTION_SEND_MULTIPLE` fuer Text (`text/*`) und JSON-Dateien (`application/json`); Dateiinhalte werden ueber den ContentResolver gelesen.
- Verlustfreie Persistenz: Der Inhalt wird **sofort** nach Empfang in der Room-Outbox gespeichert (Tabelle `ingest_items`, capped bei 2 MB pro Element), bevor irgendeine UI-Aktion passiert. App-Kill direkt nach dem Teilen verliert nichts.
- Quellenerkennung (`SourceDetector`) mit zwei Ebenen: 1) Referrer-Paket des Sharesheets (WhatsApp/Chrome/Firefox/Gmail/Sherpa u. a.), 2) Inhaltsheuristik ohne Referrer (Sprecher-Marker → Transkript, URL-Listen → Browser, JSON → unklar). 15 Unit-Tests decken beide Pfade ab inklusive Prioritaetsregel „Referrer schlaegt Heuristik".
- Share-Screen: Vorschau, Quellen-Chip, editierbarer Titel (Auto-Vorschlag aus erster Zeile), Speichern oder „Spaeter".
- Inbox-Screen (Haupt-App): Liste aller Outbox-Eintraege mit Status-Badge (QUEUED/RUNNING/DONE/FAILED), Quelle, Zeitstempel, Groesse; Fehlermeldungen sichtbar. Info-Tab zeigt Version.
- Grundlage fuer Phase 2: Status-Feld, DAO und DB sind fuer Publish-Worker vorbereitet; Verarbeitung selbst folgt mit der BookStack-Anbindung.

## 0.1.0 / 1 (2026-08-25)

**Phase 0 – Projektgerüst**

- Android-Projekt angelegt: Kotlin 2.1.21, AGP 8.13.2, Compose BOM 2026.06.01, minSdk 26 / targetSdk 35 — Versionen identisch mit der bewährten SherpaApp-Konfiguration.
- Leere MainActivity (Compose M3) mit Versionsanzeige (`versionName` + `versionCode` auf dem Bildschirm).
- Room + KSP von Anfang an in den Dependencies (Outbox-Datenbank folgt in Phase 1).
- Release-Signing eingerichtet: eigenes Keystore `companion-release.jks` (getrennt vom Sherpa-Keystore), Properties außerhalb des Repos unter `/root/keystores/companion-keystore.properties`.
- Gradle-Wrapper 8.13 von der SherpaApp übernommen (identische Distribution, bereits im lokalen Cache).
- README zweisprachig (EN/DE, keine Emojis) mit Architekturüberblick und Phasen-Roadmap; Phasenplan liegt unter `.hermes/plans/`.
