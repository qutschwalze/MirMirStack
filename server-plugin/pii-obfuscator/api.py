"""PII-Obfuscator fuer MirMirStack Ingest (Variant B).

PII erreicht den externen LLM nie: Eingabetext wird vor dem LLM-Call in
Pseudonym-Tokens ersetzt (PERSON_1, EMAIL_1, ORT_1, ...), die Antwort wird
danach mit den RAM-Mappings zurueckgesetzt.

Der Service ist zustandslos. Die Token-Zaehler laufen global weiter,
dadurch sind Tokens ueber Chunks und Requests hinweg eindeutig, und die
Deobfuscation in der Theme-Plugin-Datei findet jedes Original exakt.

Endpoints:
  POST /obfuscate    {"text", "language": "de", "score_threshold"?}
                     -> {"obfuscated_text", "mappings": {TOKEN: original}, "token_count"}
  POST /deobfuscate  {"text", "mappings"} -> {"text"}   (Pilot/Roundtrip-Tests)
  GET  /health       -> {"status": "ok", "model", "threshold", "token_counter", ...}
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

MODEL = os.environ.get("PII_MODEL", "de_core_news_md")
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
}

_DE_PHONE_PATTERNS = [
    # Internationale deutsche Nummer: +49 / 0049, endet auf Ziffer
    Pattern("de_phone_intl", r"(?<!\d)(?:\+49|0049)[\d\s\/\-\(\)]{7,14}\d(?!\d)", 0.85),
    # Lokale Nummer: 0 + Vorwahl, optional Trenner, 5-10 Ziffern
    Pattern("de_phone_local", r"(?<!\d)0\d{2,4}[\s\/\-]?\d{5,10}(?!\d)", 0.7),
]

_TOKEN_LOCK = threading.Lock()
_token_counter = 0


def _next_token() -> int:
    global _token_counter
    _token_counter += 1
    return _token_counter


def _build_engine() -> AnalyzerEngine:
    log.info("Initialisiere SpacyNlpEngine model=%s ...", MODEL)
    nlp_engine = SpacyNlpEngine(models=[{"lang_code": "de", "model_name": MODEL}])
    registry = RecognizerRegistry(supported_languages=["de"])
    for cls in (EmailRecognizer, PhoneRecognizer, IpRecognizer, UrlRecognizer,
                CreditCardRecognizer, IbanRecognizer):
        registry.add_recognizer(cls(supported_language="de"))
    registry.add_recognizer(SpacyRecognizer(supported_language="de"))
    registry.add_recognizer(PatternRecognizer(
        supported_entity="PHONE_NUMBER", supported_language="de", name="PhoneDE",
        patterns=_DE_PHONE_PATTERNS))
    return AnalyzerEngine(registry=registry, nlp_engine=nlp_engine,
                          supported_languages=["de"],
                          default_score_threshold=DEFAULT_THRESHOLD)


log.info("Lade Presidio (Init dauert einige Sekunden) ...")
analyzer = _build_engine()
log.info("Presidio bereit (model=%s)", MODEL)


class ObfuscateReq(BaseModel):
    text: str
    language: str = "de"
    score_threshold: float | None = None


class DeobfuscateReq(BaseModel):
    text: str
    mappings: dict[str, str]


app = FastAPI(title="pii-obfuscator", version="0.3.0")


@app.exception_handler(Exception)
async def _unhandled(req: Request, exc: Exception):
    log.exception("Unhandled error on %s", req.url.path)
    return JSONResponse(status_code=500, content={"error": str(exc)})


@app.get("/health")
def health():
    return {"status": "ok", "model": MODEL,
            "threshold": DEFAULT_THRESHOLD,
            "token_counter": _token_counter}


@app.post("/obfuscate")
def obfuscate(req: ObfuscateReq):
    threshold = req.score_threshold if req.score_threshold is not None else DEFAULT_THRESHOLD
    text = req.text
    results = analyzer.analyze(text=text, language=req.language, score_threshold=threshold)
    if not results:
        return {"obfuscated_text": text, "mappings": {}, "token_count": 0}
    obf, mappings = _tokenize(text, results)
    log.info("obfuscate: %d hits -> %d tokens (len=%d)", len(results), len(mappings), len(text))
    return {"obfuscated_text": obf, "mappings": mappings, "token_count": len(mappings)}


@app.post("/deobfuscate")
def deobfuscate(req: DeobfuscateReq):
    return {"text": _replace_tokens(req.text, req.mappings)}


def _tokenize(text: str, results) -> tuple[str, dict]:
    """Ersetzt Erkanntes durch global eindeutige Tokens; Mappings Token->Original.

    Overlap-Dedupe (hoeherer Score behaelt; bei Gleichstand laengere Span),
    dann Text aus Original-Spannen neu zusammensetzen. Kein Presidio-Anonymizer
    noetig, dadurch exakte Positionen und kontrollierte Token-Namen.
    """
    ranked = sorted(results, key=lambda r: (-r.score, r.start, -(r.end - r.start)))
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