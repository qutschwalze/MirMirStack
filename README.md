# Companion — Android

Companion share-target app for Android that forwards shared text and files to a self-hosted **BookStack** wiki as AI-summarized pages. Built with **Kotlin**, **Jetpack Compose** and a strictly local-first pipeline (Room outbox + WorkManager).

Share anything — meeting transcripts, browser text, chat messages, Markdown/JSON files — pick a template, and the app stores a structured summary as a page in your wiki. No intermediate server required: the app talks directly to the LLM API (OpenAI-compatible endpoint) and to the BookStack REST API.

**Sprache / Language:** [English](#english) · [Deutsch](#deutsch)

---

<a name="english"></a>
## English

### Status

Phase 0 — project scaffold. The app builds, runs and shows its version. Functionality arrives phase by phase (see roadmap); every version is tagged and released here with debug + release APKs.

### Roadmap

| Phase | Scope | Version |
|-------|-------|---------|
| 0 | Project scaffold, CI-less build, docs | 0.1.0 |
| 1 | Share target (text/markdown/json), source detection, Room outbox, inbox list | 0.2.0 |
| 2 | BookStack API: settings store, client, publish worker (chapter per month, page per entry, original file as attachment, update-instead-of-duplicate) | 0.3.0 |
| 3 | LLM integration: OpenAI-compatible client, template prompts, structured JSON output, deterministic markdown-to-HTML rendering | 0.4.0 |
| 4 | Templates (meeting protocol / research clip / chat digest / universal), source-based default routing, page tags | 0.5.0 |
| 5 | Convenience: quick capture field, done/failed notifications with deep link, review mode | 0.6.0 |
| 6 | Hardening, error paths, signing, v1.0 | 1.0.0 |

### Architecture

```
Any Android app --share--> Companion
                             |-- capture: referrer-based source detection, template choice
                             |-- outbox: Room + WorkManager (lossless, retry-safe, survives reboot)
                             |-- process: LLM (OpenAI-compatible API) -> structured JSON -> HTML
                             `-- publish: BookStack REST API -> book "Meetings und Notizen"
                                          monthly chapters, dated pages, original as attachment
```

Design decisions:

- Strictly separate project (no code coupling to other apps on this device).
- No intermediate server — only two external APIs: the LLM endpoint and BookStack.
- Secrets (BookStack token, LLM key) live in EncryptedSharedPreferences, entered once on first start.
- Summaries templates can be extended without app updates: the app reads template definitions (JSON) from a private wiki page, with built-in defaults as fallback.
- Lossless persistence: a shared item is stored locally before anything else happens; failed jobs stay visible and retryable.

### Build

Requirements: JDK 17, Android SDK (API 35). Standard Gradle build:

```bash
./gradlew assembleDebug     # debug APK
./gradlew assembleRelease   # signed release APK (needs keystore.properties, see app/build.gradle.kts)
```

### Configuration

Not applicable yet (arrives with Phase 2): BookStack base URL + API token, LLM base URL + key + model name, all entered in-app and stored encrypted on the device.

---

<a name="deutsch"></a>
## Deutsch

### Status

Phase 0 — Projektgerüst. Die App baut, startet und zeigt ihre Version. Die Funktionalität kommt phasenweise (siehe Roadmap); jede Version wird hier getaggt und mit Debug- und Release-APK released.

### Roadmap

| Phase | Umfang | Version |
|-------|--------|---------|
| 0 | Projektgerüst, Build, Dokumentation | 0.1.0 |
| 1 | Share Target (Text/Markdown/JSON), Quellenerkennung, Room-Outbox, Inbox-Liste | 0.2.0 |
| 2 | BookStack-API: Settings, Client, Publish-Worker (Monatskapitel, datierte Seite, Original als Anhang, Update statt Duplikat) | 0.3.0 |
| 3 | LLM-Anbindung: OpenAI-kompatibler Client, Vorlagen-Prompts, strukturiertes JSON, deterministisches Markdown-zu-HTML | 0.4.0 |
| 4 | Vorlagen (Meeting-Protokoll / Recherche-Clip / Chat-Digest / Universal), Quell-Routing, Seiten-Tags | 0.5.0 |
| 5 | Komfort: Quick-Capture-Feld, Benachrichtigungen mit Wiki-Link, Review-Modus | 0.6.0 |
| 6 | Härtung, Fehlerpfade, Signing, v1.0 | 1.0.0 |

### Architektur

```
Beliebige Android-App --Teilen--> Companion
                                    |-- Capture: Quellenerkennung (Referrer), Vorlagenwahl
                                    |-- Outbox: Room + WorkManager (verlustfrei, retry-sicher,
                                    |            ueberlebt Reboot)
                                    |-- Process: LLM (OpenAI-kompatible API) -> strukturiertes
                                    |            JSON -> HTML
                                    `-- Publish: BookStack-REST-API -> Buch "Meetings und Notizen"
                                                 Monatskapitel, datierte Seiten, Original als Anhang
```

Entscheidungen:

- Strikt getrenntes Projekt (keine Code-Kopplung an andere Apps auf dem Geraet).
- Kein Zwischenserver — nur zwei externe APIs: der LLM-Endpoint und BookStack.
- Secrets (BookStack-Token, LLM-Key) liegen in EncryptedSharedPreferences, einmalig beim Erststart eingegeben.
- Vorlagen sind ohne App-Update erweiterbar: Die App liest Vorlagendefinitionen (JSON) von einer privaten Wiki-Seite, eingebaute Defaults als Fallback.
- Verlustfreie Persistenz: Ein geteiltes Element wird zunaechst lokal gespeichert, bevor irgendwas anderes passiert; fehlgeschlagene Jobs bleiben sichtbar und wiederholbar.

### Build

Voraussetzungen: JDK 17, Android SDK (API 35). Ueblicher Gradle-Build:

```bash
./gradlew assembleDebug     # Debug-APK
./gradlew assembleRelease   # signiertes Release-APK (benoetigt keystore.properties, siehe app/build.gradle.kts)
```

### Konfiguration

Noch nicht vorhanden (kommt mit Phase 2): BookStack-Basis-URL + API-Token, LLM-Basis-URL + Key + Modellname — alles in der App einzugeben, verschluesselt auf dem Geraet gespeichert.
