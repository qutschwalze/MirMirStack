# Changelog — Companion

Alle Versionswechsel werden hier dokumentiert. Jeder Build erhöht `versionCode` + `versionName` (siehe `app/build.gradle.kts`).

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
