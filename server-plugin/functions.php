<?php
/**
 * MirMirStack Ingest – Logical Theme Plugin fuer BookStack
 *
 * Endpoint: POST /mirmirstack/ingest
 * Header:   X-MirMir-Token: <Wert aus MIRMIR_INGEST_TOKEN>
 * Body:     {"text": "...", "template": "meeting|research|chat|universal"}
 * Response: 202 {"status":"accepted"} – Verarbeitung laeuft asynchron.
 *
 * Ablauf nach ACK: LLM-Aufruf -> JSON validieren -> Monatskapitel sicherstellen
 * -> Seite anlegen/updaten (Tags inklusive) -> Original als Attachment.
 */

/**
 * Konfiguration ueber BookStack-.env (keine Secrets im Theme-Code):
 * MIRMIR_INGEST_TOKEN, MIRMIR_LLM_KEY, MIRMIR_API_TOKEN_ID, MIRMIR_API_TOKEN_SECRET
 * Optional: mirmir_cfg('MIRMIR_LLM_URL', 'https://opencode.ai/zen/go/v1/chat/completions'), mirmir_cfg('MIRMIR_LLM_MODEL', 'mimo-v2.5'), (int)mirmir_cfg('MIRMIR_BOOK_ID', '3'), mirmir_cfg('MIRMIR_API_BASE', 'http://localhost:6875/api')
 */
function mirmir_cfg(string $key, string $default = ''): string {
    $v = getenv($key);
    return ($v === false || $v === '') ? $default : $v;
}

/** Buch-Slug aus der BuchStack-API (static-Cache je Request). */
function mirmir_book_slug(int $bookId): string {
    static $cache = [];
    if (isset($cache[$bookId])) return $cache[$bookId];
    $book = json_decode(mirmir_api('GET', '/books/' . $bookId), true);
    return $cache[$bookId] = (string) ($book['slug'] ?? 'books');
}

/** Monatskapitel im Ziel-Buch sicherstellen (Idempotenz). */
function mirmir_ensure_chapter(int $bookId, string $name): int {
    $chapters = json_decode(mirmir_api('GET', "/chapters?count=100&filter[book_id]=$bookId"), true);
    foreach (($chapters['data'] ?? []) as $c) {
        if ($c['name'] === $name) return (int) $c['id'];
    }
    $created = json_decode(mirmir_api('POST', '/chapters', json_encode([
        'book_id' => $bookId, 'name' => $name,
    ])), true);
    return (int) ($created['id'] ?? 0);
}

use BookStack\Theming\ThemeEvents;

Theme::listen(ThemeEvents::APP_BOOT, function () {

    \Route::post('/mirmirstack/ingest', function () {

        // ── Auth ────────────────────────────────────────────────────────
        $token = (string) request()->header('X-MirMir-Token', '');
        if (!hash_equals(mirmir_cfg('MIRMIR_INGEST_TOKEN'), $token)) {
            return response()->json(['error' => 'unauthorized'], 401);
        }

        // ── Input ───────────────────────────────────────────────────────
        $text     = trim((string) request()->input('text', ''));
        $template = strtolower(trim((string) request()->input('template', 'meeting')));
        $title    = trim((string) request()->input('title', ''));

        if ($text === '') {
            return response()->json(['error' => 'text required'], 422);
        }
        if (mb_strlen($text) > 300000) {
            $text = mb_substr($text, 0, 300000);
        }
        if (!in_array($template, ['meeting', 'research', 'chat', 'universal'], true)) {
            $template = 'universal';
        }

        // ── Sofort ACK, Verarbeitung im Hintergrund ────────────────────
        header('Content-Type: application/json');
        http_response_code(202);
        echo json_encode(['status' => 'accepted', 'template' => $template]);
        @flush();
        if (function_exists('fastcgi_finish_request')) {
            fastcgi_finish_request();
        }

        ignore_user_abort(true);
        @set_time_limit(300);

        mirmir_process($text, $template, $title);
        exit;
    });

    // ── Seiten-Lookup: nach Ingest die finale Wiki-URL liefern ───────────
    // GET /mirmirstack/page  (Header X-MirMir-Token)
    // Antwort 200: {"url": "...", "book_url": "..."}
    // Antwort 404: {"error": "not found", "book_url": "..."} – Seite noch nicht
    //              angelegt oder aelter; die App kann dann ins Buch springen.
    \Route::get('/mirmirstack/page', function () {
        $token = (string) request()->header('X-MirMir-Token', '');
        if (!hash_equals(mirmir_cfg('MIRMIR_INGEST_TOKEN'), $token)) {
            return response()->json(['error' => 'unauthorized'], 401);
        }

        $bookId = (int) mirmir_cfg('MIRMIR_BOOK_ID', '3');
        $bookSlug = mirmir_book_slug($bookId);
        $bookUrl = rtrim((string) config('app.url'), '/') . '/books/' . $bookSlug;

        // Die zuletzt angelegten Seiten des Buchs: neueste innerhalb ~15 Min.
        $pages = json_decode(mirmir_api(
            'GET', "/pages?count=20&filter[book_id]=$bookId"
        ), true);
        $newest = null;
        foreach (($pages['data'] ?? []) as $p) {
            $created = strtotime((string) ($p['created_at'] ?? ''));
            if ($created && time() - $created < 900) {
                $newest = $p;
            }
        }
        if (!$newest) {
            return response()->json(['error' => 'not found', 'book_url' => $bookUrl], 404);
        }
        $detail = json_decode(mirmir_api('GET', '/pages/' . $newest['id']), true);
        $pageUrl = $bookUrl . '/page/' . ($detail['slug'] ?? $newest['id']);
        return response()->json(['url' => $pageUrl, 'book_url' => $bookUrl]);
    });

    // ── Datei-Upload (Datensammler): Unbekannte Formate als Attachment ──
    // POST /mirmirstack/upload  (multipart: file + name, Header X-MirMir-Token)
    // Antwort 200: {"status":"ok","url":"…seite…"} – die Datei haengt an der
    // Tages-Seite „Gesammelte Dateien YYYY-MM-DD“ im Monatskapitel.
    \Route::post('/mirmirstack/upload', function () {
        $token = (string) request()->header('X-MirMir-Token', '');
        if (!hash_equals(mirmir_cfg('MIRMIR_INGEST_TOKEN'), $token)) {
            return response()->json(['error' => 'unauthorized'], 401);
        }
        $file = request()->file('file');
        if (!$file || !$file->isValid()) {
            return response()->json(['error' => 'file required'], 422);
        }
        $name = preg_replace('/[^A-Za-z0-9._-]/', '_',
            (string) request()->input('name', $file->getClientOriginalName()));
        $name = substr((string) $name, 0, 80) ?: 'datei';

        $bookId = (int) mirmir_cfg('MIRMIR_BOOK_ID', '3');
        $chapterId = mirmir_ensure_chapter($bookId, date('Y-m'));
        $pageTitle = 'Gesammelte Dateien ' . date('Y-m-d');

        $existing = json_decode(mirmir_api('GET',
            '/pages?count=5&filter[name]=' . urlencode($pageTitle) .
            "&filter[chapter_id]=$chapterId"), true);
        $pageId = (int) ($existing['data'][0]['id'] ?? 0);
        if (!$pageId) {
            $created = json_decode(mirmir_api('POST', '/pages', json_encode([
                'chapter_id' => $chapterId,
                'name' => $pageTitle,
                'html' => '<p>Hier landen geteilte Dateien unbekannter Formate (Datensammler).</p>',
            ])), true);
            $pageId = (int) ($created['id'] ?? 0);
        }
        if (!$pageId) {
            return response()->json(['error' => 'page creation failed'], 500);
        }
        $page = json_decode(mirmir_api('GET', '/pages/' . $pageId), true);

        try {
            mirmir_api_multipart('/attachments',
                $file->getRealPath(), $name, $pageId,
                $file->getMimeType() ?: 'application/octet-stream');
        } catch (Throwable $ex) {
            return response()->json(['error' => 'upload failed: ' . $ex->getMessage()], 500);
        }

        $bookUrl = rtrim((string) config('app.url'), '/') . '/books/' . mirmir_book_slug($bookId);
        return response()->json([
            'status' => 'ok',
            'url' => $bookUrl . '/page/' . ($page['slug'] ?? $pageId),
        ]);
    });
});

// ══════════════════════════════════════════════════════════════════════

function mirmir_log(string $msg): void {
    @file_put_contents(__DIR__ . '/ingest.log',
        sprintf("[%s] %s\n", date('Y-m-d H:i:s'), $msg),
        FILE_APPEND);
}

/** System-Prompts je Vorlage (gleiche Struktur wie in der App). */
function mirmir_prompt(string $tpl): string {
    $fields = '{"title": string (kurz, max 60 Zeichen), '
        . '"summary_md": string (Markdown-Zusammenfassung auf Deutsch), '
        . '"decisions": string[], "todos": string[], '
        . '"participants": string[], "tags": string[] (2-5 thematische Tags)}';
    $base = "Antworte AUSSCHLIESSLICH mit einem JSON-Objekt mit genau diesen Feldern:\n$fields\n"
          . "Kein Markdown-Codeblock, kein Text ausserhalb des JSON.";
    switch ($tpl) {
        case 'research': return "Du erstellst Recherche-Zusammenfassungen auf Deutsch.\n$base";
        case 'chat':     return "Du erstellst kompakte Zusammenfassungen von Chatverlaeufen auf Deutsch.\n$base";
        case 'meeting':  return "Du erstellst praezise Meeting-Protokolle auf Deutsch.\n$base";
        default:         return "Du klassifizierst den Inhalt (Meeting/Recherche/Chat) und erstellst eine passende Zusammenfassung auf Deutsch.\n$base";
    }
}

function mirmir_process(string $text, string $template, string $userTitle): void {
    try {
        // 1) LLM aufrufen
        $payload = json_encode([
            'model' => mirmir_cfg('MIRMIR_LLM_MODEL', 'mimo-v2.5'),
            'messages' => [
                ['role' => 'system', 'content' => mirmir_prompt($template)],
                ['role' => 'user',   'content' => $text],
            ],
            'response_format' => ['type' => 'json_object'],
            'temperature' => 0.2,
        ]);
        $raw = mirmir_http(mirmir_cfg('MIRMIR_LLM_URL', 'https://opencode.ai/zen/go/v1/chat/completions'), 'POST', $payload, [
            'Authorization: Bearer ' . mirmir_cfg('MIRMIR_LLM_KEY'),
            'Content-Type: application/json',
        ], 120);
        $llm = json_decode($raw, true);
        $answer = $llm['choices'][0]['message']['content'] ?? null;
        if (!$answer) throw new Exception('LLM leere Antwort: ' . substr($raw, 0, 200));

        // 2) JSON extrahieren (tolerant gegen Codefences)
        $clean = trim(preg_replace('/^```(?:json)?|```$/m', '', trim($answer)));
        $s = strpos($clean, '{'); $e = strrpos($clean, '}');
        if ($s === false || $e === false || $e <= $s) throw new Exception('kein JSON in Antwort');
        $sum = json_decode(substr($clean, $s, $e - $s + 1), true);
        if (empty($sum['title']) || empty($sum['summary_md'])) {
            throw new Exception('Pflichtfelder fehlen (title/summary_md)');
        }

        // 3) HTML bauen (escape + Absaetze; Listen als <ul> grob unterstuetzt)
        $html = '<p>' . nl2br(htmlspecialchars($sum['summary_md'], ENT_QUOTES)) . '</p>';
        foreach (['decisions' => 'Entscheidungen', 'todos' => 'To-dos'] as $k => $label) {
            if (!empty($sum[$k]) && is_array($sum[$k])) {
                $html .= "<h3>$label</h3><ul>";
                foreach ($sum[$k] as $it) $html .= '<li>' . htmlspecialchars((string)$it, ENT_QUOTES) . '</li>';
                $html .= '</ul>';
            }
        }
        if (!empty($sum['participants'])) {
            $html .= '<p><em>Teilnehmer: ' . htmlspecialchars(implode(', ', $sum['participants']), ENT_QUOTES) . '</em></p>';
        }

        // 4) Tags: typ + quelle(unbekannt serverseitig) + thema + person
        $tags = [];
        $typMap = ['meeting' => 'meeting', 'research' => 'recherche', 'chat' => 'chat', 'universal' => 'allgemein'];
        $tags[] = ['name' => 'typ', 'value' => $typMap[$template] ?? 'allgemein'];
        foreach (($sum['tags'] ?? []) as $t)        $tags[] = ['name' => 'thema', 'value' => (string)$t];
        foreach (($sum['participants'] ?? []) as $p) $tags[] = ['name' => 'person', 'value' => (string)$p];

        // 5) Kapitel YYYY-MM sichern
        $month = date('Y-m');
        $chapters = json_decode(mirmir_api('GET', "/chapters?count=100&filter[book_id]=" . (int)mirmir_cfg('MIRMIR_BOOK_ID', '3')), true);
        $chapterId = null;
        foreach (($chapters['data'] ?? []) as $c) {
            if ($c['name'] === $month) { $chapterId = $c['id']; break; }
        }
        if (!$chapterId) {
            $created = json_decode(mirmir_api('POST', '/chapters',
                json_encode(['book_id' => (int)mirmir_cfg('MIRMIR_BOOK_ID', '3'), 'name' => $month])), true);
            $chapterId = $created['id'] ?? null;
            if (!$chapterId) throw new Exception('Kapitel konnte nicht angelegt werden');
        }

        // 6) Seite anlegen/updaten (Idempotenz ueber Namen)
        $pageTitle = date('Y-m-d') . ' ' . trim($sum['title']);
        $existing = json_decode(mirmir_api('GET',
            '/pages?count=10&filter[name]=' . urlencode($pageTitle) . "&filter[chapter_id]=$chapterId"), true);
        $pagePayload = json_encode([
            'chapter_id' => $chapterId, 'name' => $pageTitle,
            'html' => $html, 'tags' => $tags,
        ]);
        if (!empty($existing['data'][0]['id'])) {
            $pid = $existing['data'][0]['id'];
            mirmir_api('PUT', "/pages/$pid", $pagePayload);
            mirmir_log("updated page $pid: $pageTitle");
        } else {
            $newPage = json_decode(mirmir_api('POST', '/pages', $pagePayload), true);
            $pid = $newPage['id'] ?? null;
            if (!$pid) throw new Exception('Seite konnte nicht angelegt werden');
            mirmir_log("created page $pid: $pageTitle");
        }

        // 7) Original als Attachment
        $tmp = tempnam(sys_get_temp_dir(), 'mmi');
        file_put_contents($tmp, $text);
        $fname = preg_replace('/[^A-Za-z0-9._-]/', '_', ($userTitle ?: 'original')) . '.txt';
        mirmir_api_multipart('/attachments', $tmp, $fname, $pid);
        @unlink($tmp);

        mirmir_log("OK template=$template");
    } catch (Throwable $ex) {
        mirmir_log('ERROR: ' . $ex->getMessage());
    }
}

/** JSON-Request an die eigene BookStack-API (localhost). */
function mirmir_api(string $method, string $path, ?string $body = null): string {
    $headers = [
        'Authorization: Token ' . mirmir_cfg('MIRMIR_API_TOKEN_ID') . ':' . mirmir_cfg('MIRMIR_API_TOKEN_SECRET'),
        'Accept: application/json',
        'Content-Type: application/json',
    ];
    return mirmir_http(mirmir_cfg('MIRMIR_API_BASE', 'http://localhost:6875/api') . $path, $method, $body, $headers, 30);
}

/** Multipart-Attachment-Upload (Feldname zwingend "file"). */
function mirmir_api_multipart(string $path, string $filePath, string $fileName, int $pageId, string $mime = 'text/plain'): void {
    $ch = curl_init(mirmir_cfg('MIRMIR_API_BASE', 'http://bookstack/api') . $path);
    curl_setopt_array($ch, [
        CURLOPT_POST => true,
        CURLOPT_HTTPHEADER => [
            'Authorization: Token ' . mirmir_cfg('MIRMIR_API_TOKEN_ID') . ':' . mirmir_cfg('MIRMIR_API_TOKEN_SECRET'),
            'Accept: application/json',
        ],
        CURLOPT_RETURNTRANSFER => true,
        CURLOPT_TIMEOUT => 60,
        CURLOPT_POSTFIELDS => [
            'file' => new CURLFile($filePath, $mime, $fileName),
            'uploaded_to' => (string)$pageId,
            'name' => $fileName,   // BookStack-API verlangt das Feld zwingend (422)
        ],
    ]);
    $res = curl_exec($ch);
    $code = curl_getinfo($ch, CURLINFO_HTTP_CODE);
    mirmir_log("attach($fileName -> $pageId): HTTP $code " . substr((string)$res, 0, 200));
    curl_close($ch);
}

/** Generischer HTTP-Client (cURL). */
function mirmir_http(string $url, string $method, ?string $body, array $headers, int $timeout): string {
    $ch = curl_init($url);
    curl_setopt_array($ch, [
        CURLOPT_CUSTOMREQUEST => $method,
        CURLOPT_POSTFIELDS => $body,
        CURLOPT_HTTPHEADER => $headers,
        CURLOPT_RETURNTRANSFER => true,
        CURLOPT_TIMEOUT => $timeout,
    ]);
    $res = curl_exec($ch);
    if ($res === false) {
        $err = curl_error($ch);
        curl_close($ch);
        throw new Exception("HTTP fehlgeschlagen: $err");
    }
    $code = curl_getinfo($ch, CURLINFO_HTTP_CODE);
    curl_close($ch);
    if ($code >= 400) throw new Exception("HTTP $code: " . substr((string)$res, 0, 200));
    return (string)$res;
}
