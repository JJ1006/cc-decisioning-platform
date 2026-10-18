"""PII masking of every tool result, with Presidio on regular expressions only.

No spaCy model is loaded (a model costs hundreds of MB of RAM; the container has 192 MB): the NLP engine is a blank English tokenizer and the
detection is by recognizers: Canadian SIN (nine digits that pass the Luhn check), Canadian postal code, e-mail, North American phone number
and payment card numbers (Luhn). Names and addresses cannot be found by patterns, so the structured fields that hold them (firstName, lastName,
dateOfBirth, line1, employer, ...) are masked by KEY before the text scan. The data this server returns is already masked by Logstash; this is the second line.
"""

from __future__ import annotations

import re
from collections.abc import Iterable, Iterator
from typing import Any, cast

import spacy
from presidio_analyzer import AnalyzerEngine, EntityRecognizer, Pattern, PatternRecognizer, RecognizerRegistry
from presidio_analyzer.nlp_engine import NlpArtifacts, NlpEngine
from presidio_anonymizer import AnonymizerEngine
from presidio_anonymizer.entities import OperatorConfig

MASK = "[MASKED]"

# keys whose value is personal by definition: masked whatever it looks like
SENSITIVE_KEYS = {
    "sin",
    "socialinsurancenumber",
    "firstname",
    "lastname",
    "givenname",
    "familyname",
    "fullname",
    "dateofbirth",
    "dob",
    "birthdate",
    "line1",
    "line2",
    "street",
    "postalcode",
    "employer",
    "email",
    "phone",
    "phonenumber",
    "password",
    "secret",
    "authorization",
}


def luhn_ok(digits: str) -> bool:
    nums = [int(c) for c in digits if c.isdigit()]
    if len(nums) < 9:
        return False
    total = 0
    for i, n in enumerate(reversed(nums)):
        if i % 2 == 1:
            n *= 2
            if n > 9:
                n -= 9
        total += n
    return total % 10 == 0


class _LuhnRecognizer(PatternRecognizer):
    def __init__(self, entity: str, regex: str, score: float = 0.9) -> None:
        super().__init__(supported_entity=entity, patterns=[Pattern(entity.lower(), regex, score)], supported_language="en")

    def validate_result(self, pattern_text: str) -> bool | None:
        return luhn_ok(pattern_text)


class _BlankNlp(NlpEngine):
    """Tokenizer only (spacy.blank): no model, no named-entity recognition."""

    def __init__(self) -> None:
        self._nlp: Any = None

    def load(self) -> None:
        self._nlp = spacy.blank("en")

    def is_loaded(self) -> bool:
        return self._nlp is not None

    def process_text(self, text: str, language: str) -> NlpArtifacts:
        doc = self._nlp(text)
        return NlpArtifacts(
            entities=[],
            tokens=doc,
            tokens_indices=[t.idx for t in doc],
            lemmas=[t.text for t in doc],
            nlp_engine=self,
            language=language,
        )

    def process_batch(
        self, texts: Iterable[str], language: str, batch_size: int = 1, n_process: int = 1, **kwargs: Any
    ) -> Iterator[tuple[str, NlpArtifacts]]:
        for text in texts:
            yield text, self.process_text(text, language)

    def is_stopword(self, word: str, language: str) -> bool:
        return False

    def is_punct(self, word: str, language: str) -> bool:
        return all(not c.isalnum() for c in word)

    def get_supported_entities(self) -> list[str]:
        return []

    def get_supported_languages(self) -> list[str]:
        return ["en"]


def _recognizers() -> list[EntityRecognizer]:
    return [
        _LuhnRecognizer("CA_SIN", r"(?<![\w-])\d{3}[ -]?\d{3}[ -]?\d{3}(?![\w-])"),
        _LuhnRecognizer("CREDIT_CARD", r"(?<![\w-])(?:\d[ -]?){13,19}(?![\w-])", 0.8),
        PatternRecognizer(
            supported_entity="CA_POSTAL_CODE",
            patterns=[
                Pattern(
                    "ca_postal", r"(?i)(?<![A-Z0-9])[ABCEGHJ-NPRSTVXY]\d[ABCEGHJ-NPRSTV-Z][ -]?\d[ABCEGHJ-NPRSTV-Z]\d(?![A-Z0-9])", 0.85
                )
            ],
            supported_language="en",
        ),
        PatternRecognizer(
            supported_entity="EMAIL_ADDRESS",
            patterns=[Pattern("email", r"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}", 0.9)],
            supported_language="en",
        ),
        PatternRecognizer(
            supported_entity="PHONE_NUMBER",
            patterns=[Pattern("na_phone", r"(?<![\w-])(?:\+?1[ .-]?)?\(?\d{3}\)?[ .-]\d{3}[ .-]\d{4}(?![\w-])", 0.7)],
            supported_language="en",
        ),
    ]


_ENGINES: tuple[AnalyzerEngine, AnonymizerEngine] | None = None


def _engines() -> tuple[AnalyzerEngine, AnonymizerEngine]:
    global _ENGINES
    if _ENGINES is None:
        registry = RecognizerRegistry(supported_languages=["en"])
        for r in _recognizers():
            registry.add_recognizer(r)
        nlp = _BlankNlp()
        nlp.load()
        analyzer = AnalyzerEngine(registry=registry, nlp_engine=nlp, supported_languages=["en"])
        _ENGINES = (analyzer, AnonymizerEngine())  # type: ignore[no-untyped-call]
    return _ENGINES


ENTITIES = ["CA_SIN", "CA_POSTAL_CODE", "EMAIL_ADDRESS", "PHONE_NUMBER", "CREDIT_CARD"]


def mask_text(text: str) -> str:
    """Replace every detected personal value in a string by its entity type, e.g. <CA_SIN>."""
    if not text or len(text) < 5:
        return text
    analyzer, anonymizer = _engines()
    found = analyzer.analyze(text=text, language="en", entities=ENTITIES)
    if not found:
        return text
    operators = {e: OperatorConfig("replace", {"new_value": f"<{e}>"}) for e in ENTITIES}
    return anonymizer.anonymize(text=text, analyzer_results=cast(Any, found), operators=operators).text


def _is_sensitive_key(key: str) -> bool:
    return re.sub(r"[^a-z0-9]", "", key.lower()) in SENSITIVE_KEYS


def mask(value: Any) -> Any:
    """Mask a JSON-like value: sensitive keys by name, every string by pattern. Numbers and booleans pass."""
    if isinstance(value, dict):
        return {k: (MASK if _is_sensitive_key(str(k)) and v not in (None, "") else mask(v)) for k, v in value.items()}
    if isinstance(value, list):
        return [mask(v) for v in value]
    if isinstance(value, str):
        return mask_text(value)
    return value
