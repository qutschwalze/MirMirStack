# MirMirStack Server-Plugin

BookStack Logical-Theme-Plugin, das der App einen serverseitigen
Verarbeitungsmodus gibt: Die App sendet nur Text + Vorlage an einen
authentifizierten Endpoint; das Plugin ruft das LLM auf, validiert das
Ergebnis, baut HTML mit Tags und legt die Seite im Ziel-Buch ab.

## Endpoint

    POST /mirmirstack/ingest
    Header: X-MirMir-Token: <MIRMIR_INGEST_TOKEN>
    Body:   {"text": "...", "template": "meeting|research|chat|universal",
             "title": "optional"}
    Response 202 {"status":"accepted"} – Verarbeitung laeuft asynchron.

Auth-Fehler -> 401, fehlender Text -> 422.

## Installation (LinuxServer-Container)

1. Verzeichnis anlegen und Datei kopieren:

       mkdir -p /root/bookstack/data/config/www/themes/mirmirstack
       scp functions.php root@<vm>:/root/bookstack/data/config/www/themes/mirmirstack/

2. Variablen in der docker-compose.yml des bookstack-Services ergaenzen
   (LinuxServer-Container lesen Konfig primair von dort):

       - APP_THEME=mirmirstack
       - MIRMIR_INGEST_TOKEN=<zufalliges Secret>
       - MIRMIR_LLM_URL=https://opencode.ai/zen/go/v1/chat/completions
       - MIRMIR_LLM_KEY=<key>
       - MIRMIR_LLM_MODEL=mimo-v2.5
       - MIRMIR_API_BASE=http://bookstack/api     # container-intern!
       - MIRMIR_API_TOKEN_ID=<bookstack api token id>
       - MIRMIR_API_TOKEN_SECRET=<bookstack api token secret>
       - MIRMIR_BOOK_ID=3

   Wichtig: THEME heisst hier APP_THEME; localhost zeigt im Container
   auf den Container selbst, daher den Compose-Dienstnamen nutzen.

3. Anwenden und pruefen:

       cd /root/bookstack && docker compose up -d
       sleep 10
       docker exec bookstack php /app/www/artisan route:list | grep mirmirstack
       curl -s -o /dev/null -w "%{http_code}\n" -X POST http://localhost:6875/mirmirstack/ingest   # 401 erwartet
       curl -s -X POST http://localhost:6875/mirmirstack/ingest \
         -H "X-MirMir-Token: <token>" -H "Content-Type: application/json" \
         -d '{"text":"Test"}'                                                                     # 202 erwartet

4. Log kontrollieren:

       tail /root/bookstack/data/config/www/themes/mirmirstack/ingest.log

## Update-Kompatibilitaet (BookStack-Upgrades)

Das Plugin nutzt ausschliesslich das offizielle Logical Theme System
(functions.php + APP_BOOT-Routenregistrierung) - keine Core-Aenderungen.
Das Theme liegt im gemounteten /config-Volume und ueberlebt Container-
Updates; ebenso die MIRMIR_*-Variablen im compose-File.

Nach jedem BookStack-Update den 30-Sekunden-Check fahren:

    docker exec bookstack php /app/www/artisan route:list | grep mirmirstack
    curl -s -o /dev/null -w "%{http_code}\n" -X POST http://localhost:6875/mirmirstack/ingest   # 401 erwartet

Falls etwas nicht geht: In der compose THEME-Zeile APP_THEME= leeren,
Container neu starten -> Standard-BookStack ohne Plugin; die App kann
im Geraete-Modus weiterarbeiten, bis das Plugin gefixt ist.

## App-Seitige Konfiguration

Einstellungen -> Verarbeitung -> "Auf dem Server":
BookStack-URL (z. B. wiki.heddrich.com) + denselben Ingest-Token wie
oben eintragen, dann "Server testen".
