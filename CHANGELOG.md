# Changelog — Companion

Alle Versionswechsel werden hier dokumentiert. Jeder Build erhöht `versionCode` + `versionName` (siehe `app/build.gradle.kts`).

## 0.1.0 / 1 (2026-08-25)

**Phase 0 – Projektgerüst**

- Android-Projekt angelegt: Kotlin 2.1.21, AGP 8.13.2, Compose BOM 2026.06.01, minSdk 26 / targetSdk 35 — Versionen identisch mit der bewährten SherpaApp-Konfiguration.
- Leere MainActivity (Compose M3) mit Versionsanzeige (`versionName` + `versionCode` auf dem Bildschirm).
- Room + KSP von Anfang an in den Dependencies (Outbox-Datenbank folgt in Phase 1).
- Release-Signing eingerichtet: eigenes Keystore `companion-release.jks` (getrennt vom Sherpa-Keystore), Properties außerhalb des Repos unter `/root/keystores/companion-keystore.properties`.
- Gradle-Wrapper 8.13 von der SherpaApp übernommen (identische Distribution, bereits im lokalen Cache).
- README zweisprachig (EN/DE, keine Emojis) mit Architekturüberblick und Phasen-Roadmap; Phasenplan liegt unter `.hermes/plans/`.
