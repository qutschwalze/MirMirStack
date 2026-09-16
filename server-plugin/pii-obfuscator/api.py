"""PII-Obfuscator fuer MirMirStack Ingest (Variant B).

PII erreicht den externen LLM nie: Eingabetext wird vor dem LLM-Call in
Pseudonym-Tokens ersetzt (PERSON_1, EMAIL_1, ORT_1, ...), die Antwort wird
danach mit den RAM-Mappings zurueckgesetzt.

Der Service ist zustandslos. Die Token-Zaehler laufen global weiter,
dadurch sind Tokens ueber Chunks und Requests hinweg eindeutig, und die
Deobfuscation in der Theme-Plugin-Datei findet jedes Original exakt.

Sprachen: de (de_core_news_md) und en (en_core_web_md); der Client waehlt
per "language". Pattern-Recognizer (Email/Telefon/Secrets/...) sind
sprachunabhaengig und je Sprache registriert.

Endpoints:
  POST /obfuscate    {"text", "language": "de"|"en", "score_threshold"?}
                     -> {"obfuscated_text", "mappings": {TOKEN: original}, "token_count"}
  POST /deobfuscate  {"text", "mappings"} -> {"text"}   (Pilot/Roundtrip-Tests)
  GET  /health       -> {"status": "ok", "models", "threshold", "token_counter", ...}
"""
import logging
import os
import threading

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse
from pydantic import BaseModel

from presidio_analyzer import AnalyzerEngine, RecognizerRegistry
from presidio_analyzer.nlp_engine import SpacyNlpEngine
from presidio_analyzer.predefined_recognizers import (
    CreditCardRecognizer,
    EmailRecognizer,
    IbanRecognizer,
    IpRecognizer,
    PhoneRecognizer,
    SpacyRecognizer,
    UrlRecognizer,
)
from presidio_analyzer import Pattern, PatternRecognizer

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger("pii-obfuscator")

MODEL_DE = os.environ.get("PII_MODEL_DE", "de_core_news_md")
MODEL_EN = os.environ.get("PII_MODEL_EN", "en_core_web_md")
LANGUAGES = ("de", "en")
DEFAULT_THRESHOLD = float(os.environ.get("PII_THRESHOLD", "0.5"))

# Entity-Typ (Presidio) -> Kurzname fuer Tokens: PERSON_1, EMAIL_1, ORT_1...
ENTITY_SHORT = {
    "PERSON": "PERSON",
    "EMAIL_ADDRESS": "EMAIL",
    "PHONE_NUMBER": "TELEFON",
    "LOCATION": "ORT",
    "ORGANIZATION": "ORG",
    "IP_ADDRESS": "IP",
    "URL": "URL",
    "CREDIT_CARD": "KARTE",
    "IBAN_CODE": "IBAN",
    "API_KEY": "APIKEY",
    "PASSWORD": "SECRET",
}

_PHONE_PATTERNS = [
    # Internationale deutsche Nummer: +49 / 0049, endet auf Ziffer
    Pattern("de_phone_intl", r"(?<!\d)(?:\+49|0049)[\d\s\/\-\(\)]{7,14}\d(?!\d)", 0.85),
    # Lokale Nummer: 0 + Vorwahl, optional Trenner, 5-10 Ziffern
    Pattern("de_phone_local", r"(?<!\d)0\d{2,4}[\s\/\-]?\d{5,10}(?!\d)", 0.7),
    # Generische internationale Nummer (+1, +43, ...) fuer en/de
    Pattern("intl_phone", r"(?<!\d)\+\d{1,3}[\d\s\/\-\(\)]{6,14}\d(?!\d)", 0.8),
]

# API-Keys/Tokens mit bekanntem Praefix (hohe Treffsicherheit)
_SECRET_KEY_PATTERNS = [
    Pattern("cred_openai", r"\b(?:sk|pk)-(?:proj-)?[A-Za-z0-9_-]{20,}", 0.9),
    Pattern("cred_anthropic", r"\bsk-ant-[A-Za-z0-9_-]{20,}", 0.9),
    Pattern("cred_github", r"\bgh[pousr]_[A-Za-z0-9]{36,}", 0.9),
    Pattern("cred_githubpat", r"\bgithub_pat_[A-Za-z0-9_]{22,}", 0.9),
    Pattern("cred_aws", r"\bAKIA[0-9A-Z]{16}", 0.9),
    Pattern("cred_google", r"\bAIza[0-9A-Za-z_-]{35}", 0.9),
    Pattern("cred_stripe", r"\bsk_(?:live|test)_[A-Za-z0-9]{16,}", 0.9),
    Pattern("cred_slack", r"\bxox[baprs]-[A-Za-z0-9-]{10,}", 0.9),
    Pattern("cred_jwt", r"\beyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}", 0.9),
    Pattern("cred_bearer", r"\bBearer\s+[A-Za-z0-9._~+/=-]{20,}", 0.85),
]

# Passwoerter/Secrets im Key-Value-Kontext (konservativer Score).
# Lookbehind (nur der Wert wird ersetzt), damit das Schluesselwort
# sichtbar bleibt - der LLM erkennt sonst nicht, dass es ein Secret war.
_SECRET_KV_PATTERNS = [
    Pattern("cred_kv",
            r"(?i)(?<=\b(?:passwort|password|pwd|secret|api[_-]?key|apikey|token)"
            r"\s{0,3}[:=]\s{0,3})[\"']?[A-Za-z0-9._~+/=!%@-]{8,}[\"']?(?=\s|$|[,;])",
            0.75),
]

_TOKEN_LOCK = threading.Lock()
_token_counter = 0


def _next_token() -> int:
    global _token_counter
    _token_counter += 1
    return _token_counter


def _build_engine() -> AnalyzerEngine:
    log.info("Initialisiere SpacyNlpEngine models=%s/%s ...", MODEL_DE, MODEL_EN)
    nlp_engine = SpacyNlpEngine(models=[
        {"lang_code": "de", "model_name": MODEL_DE},
        {"lang_code": "en", "model_name": MODEL_EN},
    ])
    registry = RecognizerRegistry(supported_languages=list(LANGUAGES))
    # Sprachunabhaengige Pattern-Recognizer je Sprache registrieren
    for lang in LANGUAGES:
        for cls in (EmailRecognizer, PhoneRecognizer, IpRecognizer, UrlRecognizer,
                    CreditCardRecognizer, IbanRecognizer):
            registry.add_recognizer(cls(supported_language=lang))
        registry.add_recognizer(PatternRecognizer(
            supported_entity="PHONE_NUMBER", supported_language=lang, name="PhoneIntl",
            patterns=_PHONE_PATTERNS))
        registry.add_recognizer(PatternRecognizer(
            supported_entity="API_KEY", supported_language=lang, name="SecretKey",
            patterns=_SECRET_KEY_PATTERNS))
        registry.add_recognizer(PatternRecognizer(
            supported_entity="PASSWORD", supported_language=lang, name="SecretKV",
            patterns=_SECRET_KV_PATTERNS))
    # SpacyRecognizer je Sprache explizit (AnalyzerEngine ergaenzt ihn nur
    # bei LEERER Registry - hier ist sie gefuellt).
    for lang in LANGUAGES:
        registry.add_recognizer(SpacyRecognizer(supported_language=lang))
    return AnalyzerEngine(registry=registry, nlp_engine=nlp_engine,
                          supported_languages=list(LANGUAGES),
                          default_score_threshold=DEFAULT_THRESHOLD)


log.info("Lade Presidio (Init dauert einige Sekunden) ...")
analyzer = _build_engine()
log.info("Presidio bereit (models=%s/%s)", MODEL_DE, MODEL_EN)


class ObfuscateReq(BaseModel):
    text: str
    language: str = "de"
    score_threshold: float | None = None


class DeobfuscateReq(BaseModel):
    text: str
    mappings: dict[str, str]


app = FastAPI(title="pii-obfuscator", version="0.5.0")


@app.exception_handler(Exception)
async def _unhandled(req: Request, exc: Exception):
    log.exception("Unhandled error on %s", req.url.path)
    return JSONResponse(status_code=500, content={"error": str(exc)})


@app.get("/health")
def health():
    return {"status": "ok", "models": [MODEL_DE, MODEL_EN],
            "languages": list(LANGUAGES),
            "threshold": DEFAULT_THRESHOLD,
            "token_counter": _token_counter}


@app.post("/obfuscate")
def obfuscate(req: ObfuscateReq):
    lang = req.language if req.language in LANGUAGES else "de"
    threshold = req.score_threshold if req.score_threshold is not None else DEFAULT_THRESHOLD
    text = req.text
    results = analyzer.analyze(text=text, language=lang, score_threshold=threshold)
    if not results:
        return {"obfuscated_text": text, "mappings": {}, "token_count": 0}
    obf, mappings = _tokenize(text, results)
    log.info("obfuscate(%s): %d hits -> %d tokens (len=%d)", lang, len(results), len(mappings), len(text))
    return {"obfuscated_text": obf, "mappings": mappings, "token_count": len(mappings)}


@app.post("/deobfuscate")
def deobfuscate(req: DeobfuscateReq):
    return {"text": _replace_tokens(req.text, req.mappings)}


def _tokenize(text: str, results) -> tuple[str, dict]:
    """Ersetzt Erkanntes durch global eindeutige Tokens; Mappings Token->Original.

    Overlap-Dedupe: gezielte Pattern-Recognizer schlagen spaCy-NER (geraten),
    innerhalb einer Klasse hoeherer Score zuerst; dann Text aus Original-Spannen
    neu zusammensetzen. Kein Presidio-Anonymizer noetig, dadurch exakte
    Positionen und kontrollierte Token-Namen.
    """
    def _is_spacy(r):
        md = getattr(r, "recognition_metadata", None) or {}
        return md.get("recognizer_name") == "SpacyRecognizer"

    ranked = sorted(results, key=lambda r: (_is_spacy(r),
                                            -r.score, r.start, -(r.end - r.start)))
    # Nur Whitelist-Typen (ENTITY_SHORT) tokenisieren; alles andere
    # (z. B. DATE_TIME, Alter) bleibt unangetastet im Text.
    ranked = [r for r in ranked if r.entity_type in ENTITY_SHORT]
    kept = []
    for r in ranked:
        if any(r.start < k.end and k.start < r.end for k in kept):
            continue
        kept.append(r)
    kept.sort(key=lambda r: r.start)

    with _TOKEN_LOCK:
        parts = []
        prev = 0
        mappings = {}
        for r in kept:
            parts.append(text[prev:r.start])
            tok = "%s_%d" % (ENTITY_SHORT.get(r.entity_type, r.entity_type),
                             _next_token())
            parts.append(tok)
            mappings[tok] = text[r.start:r.end]
            prev = r.end
        parts.append(text[prev:])
    return "".join(parts), mappings


def _replace_tokens(text: str, mappings: dict[str, str]) -> str:
    if not mappings:
        return text
    # Laengste Tokens zuerst: PERSON_10 vor PERSON_1 (ein Pass, keine Kaskade)
    ordered = {}
    for tok in sorted(mappings, key=len, reverse=True):
        ordered[tok] = mappings[tok]
    out = text
    for tok, orig in ordered.items():
        out = out.replace(tok, orig)
    return out