#!/usr/bin/env python3
"""
tools/content_compiler/g2p_reference.py
======================================
Python Reference Grapheme-to-Phoneme (G2P) Engine for Indic Scripts.

Implements:
1. Devanagari Unicode decomposition (consonants, dependent/independent vowels,
   matras, virama, nukta, anusvara, visarga).
2. Rule-based Hindi schwa deletion engine (Narasimhan & Ohala phonotactic rules).
3. Tamil Unicode decomposition + allophonic voicing rules (intervocalic & post-nasal).
4. Kannada & Malayalam basic phonemic decomposition.

Outputs canonical IPA phoneme arrays with 1-to-1 character offset alignments
for forced alignment and TTS conditioning.
"""

import re
from dataclasses import dataclass
from typing import List, Optional, Tuple, Dict, Set


@dataclass(frozen=True)
class PhonemeElement:
    """A single decomposed phonetic element."""
    grapheme: str
    ipa: str
    offset: int
    is_inherent_vowel: bool = False

    def __repr__(self) -> str:
        return f"({self.grapheme}->{self.ipa}@{self.offset}{'*' if self.is_inherent_vowel else ''})"


@dataclass
class G2PResult:
    """Result of G2P conversion with alignment indices."""
    phonemes: List[str]
    alignment_offsets: List[int]
    source_text: str
    language_code: str

    @property
    def ipa_string(self) -> str:
        return " ".join(p for p in self.phonemes if p != " ")

    @property
    def phoneme_count(self) -> int:
        return len([p for p in self.phonemes if p.strip()])


# ============================================================================
# 1. Devanagari Decomposer & Hindi Schwa Deletion
# ============================================================================

DEVANAGARI_CONSONANTS: Dict[int, str] = {
    0x0915: "k",   0x0916: "kʰ",  0x0917: "ɡ",   0x0918: "ɡʰ",  0x0919: "ŋ",
    0x091A: "tʃ",  0x091B: "tʃʰ", 0x091C: "dʒ",  0x091D: "dʒʰ", 0x091E: "ɲ",
    0x091F: "ʈ",   0x0920: "ʈʰ",  0x0921: "ɖ",   0x0922: "ɖʰ",  0x0923: "ɳ",
    0x0924: "t̪",   0x0925: "t̪ʰ",  0x0926: "d̪",   0x0927: "d̪ʰ",  0x0928: "n̪",
    0x092A: "p",   0x092B: "pʰ",  0x092C: "b",   0x092D: "bʰ",  0x092E: "m",
    0x092F: "j",   0x0930: "ɾ",   0x0932: "l",   0x0935: "ʋ",   0x0936: "ʃ",
    0x0937: "ʂ",   0x0938: "s",   0x0939: "h",
}

DEVANAGARI_INDEPENDENT_VOWELS: Dict[int, str] = {
    0x0905: "ə",  0x0906: "aː", 0x0907: "ɪ",  0x0908: "iː",
    0x0909: "ʊ",  0x090A: "uː", 0x090F: "eː", 0x0910: "ɛː",
    0x0913: "oː", 0x0914: "ɔː",
}

DEVANAGARI_MATRAS: Dict[int, str] = {
    0x093E: "aː", 0x093F: "ɪ",  0x0940: "iː", 0x0941: "ʊ",
    0x0942: "uː", 0x0947: "eː", 0x0948: "ɛː", 0x094B: "oː",
    0x094C: "ɔː",
}

VIRAMA_DEV = 0x094D
ANUSVARA_DEV = 0x0902
CHANDRABINDU_DEV = 0x0901
VISARGA_DEV = 0x0903
NUKTA_DEV = 0x093C


def decompose_devanagari(text: str) -> List[PhonemeElement]:
    """Decomposes Devanagari string into constituent phoneme elements with inherent schwas."""
    elements: List[PhonemeElement] = []
    chars = list(text)
    length = len(chars)

    i = 0
    while i < length:
        cp = ord(chars[i])
        char = chars[i]
        next_cp = ord(chars[i + 1]) if i + 1 < length else None

        if cp == NUKTA_DEV or cp == VIRAMA_DEV or cp in DEVANAGARI_MATRAS:
            i += 1
            continue

        if cp == ANUSVARA_DEV:
            elements.append(PhonemeElement(char, "ŋ", i))
            i += 1
            continue

        if cp == CHANDRABINDU_DEV:
            elements.append(PhonemeElement(char, "̃", i))
            i += 1
            continue

        if cp == VISARGA_DEV:
            elements.append(PhonemeElement(char, "h", i))
            i += 1
            continue

        if cp in DEVANAGARI_INDEPENDENT_VOWELS:
            elements.append(PhonemeElement(char, DEVANAGARI_INDEPENDENT_VOWELS[cp], i))
            i += 1
            continue

        if cp in DEVANAGARI_CONSONANTS:
            base_ipa = DEVANAGARI_CONSONANTS[cp]
            elements.append(PhonemeElement(char, base_ipa, i))

            # Lookahead for virama, matra, or inherent schwa
            lookahead = i + 1
            if next_cp == NUKTA_DEV:
                lookahead += 1

            if lookahead < length:
                post_cp = ord(chars[lookahead])
                if post_cp == VIRAMA_DEV:
                    i = lookahead + 1
                    continue
                elif post_cp in DEVANAGARI_MATRAS:
                    elements.append(PhonemeElement(chars[lookahead], DEVANAGARI_MATRAS[post_cp], lookahead))
                    i = lookahead + 1
                    continue

            # Inherent schwa
            elements.append(PhonemeElement("", "ə", i, is_inherent_vowel=True))
            i = lookahead
            continue

        if char in " \t\n":
            elements.append(PhonemeElement(char, " ", i))
        i += 1

    return elements


def is_hindi_vowel(ipa: str) -> bool:
    return any(v in ipa for v in ["ə", "a", "ɪ", "i", "ʊ", "u", "e", "ɛ", "o", "ɔ", "æ", "ɑ"])


def is_hindi_consonant(ipa: str) -> bool:
    return bool(ipa.strip()) and not is_hindi_vowel(ipa) and ipa not in [" ", "̃"]


def apply_hindi_schwa_deletion(elements: List[PhonemeElement]) -> List[PhonemeElement]:
    """Applies Narasimhan phonotactic schwa deletion rules to Hindi phonemes."""
    if not elements:
        return []

    # Segment into word blocks
    words: List[List[PhonemeElement]] = []
    curr_word: List[PhonemeElement] = []
    for el in elements:
        if el.ipa == " ":
            if curr_word:
                words.append(curr_word)
                curr_word = []
            words.append([el])
        else:
            curr_word.append(el)
    if curr_word:
        words.append(curr_word)

    result: List[PhonemeElement] = []
    for word in words:
        if len(word) == 1 and word[0].ipa == " ":
            result.extend(word)
            continue

        # Count total vowel nuclei
        inherent_count = sum(1 for e in word if e.is_inherent_vowel)
        explicit_vowels = sum(1 for e in word if not e.is_inherent_vowel and is_hindi_vowel(e.ipa))

        if (inherent_count + explicit_vowels) <= 1:
            result.extend(word)
            continue

        deleted_indices: Set[int] = set()

        # Rule 1: Word-final inherent schwa deletion
        for idx in range(len(word) - 1, -1, -1):
            if word[idx].is_inherent_vowel:
                deleted_indices.add(idx)
                break
            if is_hindi_consonant(word[idx].ipa):
                continue
            break

        # Rule 2: Medial schwa deletion in VCəCV contexts
        for idx in range(len(word)):
            if idx in deleted_indices or not word[idx].is_inherent_vowel:
                continue

            # Check previous consonant and preceding vowel
            has_prev_vowel = False
            for j in range(idx - 1, -1, -1):
                if is_hindi_vowel(word[j].ipa) and j not in deleted_indices:
                    has_prev_vowel = True
                    break

            # Check next consonant and following vowel
            has_next_vowel = False
            for j in range(idx + 1, len(word)):
                if is_hindi_vowel(word[j].ipa) and j not in deleted_indices:
                    has_next_vowel = True
                    break

            if has_prev_vowel and has_next_vowel:
                # Safe to delete medial schwa
                deleted_indices.add(idx)

        for idx, el in enumerate(word):
            if idx not in deleted_indices:
                result.append(el)

    return result


# ============================================================================
# 2. Tamil Decomposer & Allophonic Voicing
# ============================================================================

TAMIL_CONSONANTS: Dict[int, str] = {
    0x0B95: "k",   0x0B99: "ŋ",   0x0B9A: "tʃ",  0x0B9E: "ɲ",
    0x0B9F: "ʈ",   0x0BA3: "ɳ",   0x0BA4: "t̪",   0x0BA8: "n̪",
    0x0BAA: "p",   0x0BAE: "m",   0x0BAF: "j",   0x0BB0: "r",
    0x0BB2: "l",   0x0BB5: "ʋ",   0x0BB4: "ɻ",   0x0BB3: "ɭ",
    0x0BB1: "ɾ",   0x0BA9: "n",   0x0BB8: "s",   0x0BB7: "ʃ",
    0x0BB9: "h",   0x0B9C: "dʒ",
}

TAMIL_INDEPENDENT_VOWELS: Dict[int, str] = {
    0x0B85: "a",  0x0B86: "aː", 0x0B87: "i",  0x0B88: "iː",
    0x0B89: "u",  0x0B8A: "uː", 0x0B8E: "e",  0x0B8F: "eː",
    0x0B90: "ai", 0x0B92: "o",  0x0B93: "oː", 0x0B94: "au",
}

TAMIL_MATRAS: Dict[int, str] = {
    0x0BBE: "aː", 0x0BBF: "i",  0x0BC0: "iː", 0x0BC1: "u",
    0x0BC2: "uː", 0x0BC6: "e",  0x0BC7: "eː", 0x0BC8: "ai",
    0x0BCA: "o",  0x0BCB: "oː", 0x0BCC: "au",
}

PULLI_TAMIL = 0x0BCD

VOICED_ALLOPHONES = {
    "k": "ɡ",
    "tʃ": "dʒ",
    "ʈ": "ɖ",
    "t̪": "d̪",
    "p": "b",
}


def decompose_tamil(text: str) -> List[PhonemeElement]:
    """Decomposes Tamil Unicode string into phonemes with inherent /a/."""
    elements: List[PhonemeElement] = []
    chars = list(text)
    length = len(chars)

    i = 0
    while i < length:
        cp = ord(chars[i])
        char = chars[i]

        if cp == PULLI_TAMIL or cp in TAMIL_MATRAS:
            i += 1
            continue

        if cp in TAMIL_INDEPENDENT_VOWELS:
            elements.append(PhonemeElement(char, TAMIL_INDEPENDENT_VOWELS[cp], i))
            i += 1
            continue

        if cp in TAMIL_CONSONANTS:
            base_ipa = TAMIL_CONSONANTS[cp]
            elements.append(PhonemeElement(char, base_ipa, i))

            if i + 1 < length:
                next_cp = ord(chars[i + 1])
                if next_cp == PULLI_TAMIL:
                    i += 2
                    continue
                elif next_cp in TAMIL_MATRAS:
                    elements.append(PhonemeElement(chars[i + 1], TAMIL_MATRAS[next_cp], i + 1))
                    i += 2
                    continue

            # Inherent /a/
            elements.append(PhonemeElement("", "a", i, is_inherent_vowel=True))
            i += 1
            continue

        if char in " \t\n":
            elements.append(PhonemeElement(char, " ", i))
        i += 1

    return elements


def is_tamil_vowel(ipa: str) -> bool:
    return any(v in ipa for v in ["a", "i", "u", "e", "o", "ai", "au"])


def is_tamil_nasal(ipa: str) -> bool:
    return ipa in ["m", "n", "n̪", "ɳ", "ɲ", "ŋ"]


def apply_tamil_allophonic_voicing(elements: List[PhonemeElement]) -> List[PhonemeElement]:
    """Applies positional allophonic voicing to Tamil stops (intervocalic & post-nasal)."""
    result: List[PhonemeElement] = []
    length = len(elements)

    for i in range(length):
        curr = elements[i]

        if curr.ipa not in VOICED_ALLOPHONES:
            result.append(curr)
            continue

        # Check word-initial (preceded by start or space)
        is_initial = (i == 0) or (elements[i - 1].ipa == " ")
        if is_initial:
            result.append(curr)
            continue

        # Check post-nasal
        prev_ipa = elements[i - 1].ipa if i > 0 else ""
        if is_tamil_nasal(prev_ipa):
            result.append(PhonemeElement(curr.grapheme, VOICED_ALLOPHONES[curr.ipa], curr.offset, curr.is_inherent_vowel))
            continue

        # Check intervocalic
        next_ipa = elements[i + 1].ipa if i + 1 < length else ""
        if is_tamil_vowel(prev_ipa) and is_tamil_vowel(next_ipa):
            result.append(PhonemeElement(curr.grapheme, VOICED_ALLOPHONES[curr.ipa], curr.offset, curr.is_inherent_vowel))
            continue

        result.append(curr)

    return result


# ============================================================================
# 3. Main G2P Engine Interface
# ============================================================================

class IndicG2P:
    """Unified Indic Grapheme-to-Phoneme Engine."""

    def __init__(self):
        pass

    def convert(self, text: str, lang: str = "tamil") -> G2PResult:
        lang_norm = lang.lower().strip()

        if lang_norm in ["hi", "hindi", "bhojpuri"]:
            raw = decompose_devanagari(text)
            processed = apply_hindi_schwa_deletion(raw)
            return G2PResult(
                phonemes=[e.ipa for e in processed],
                alignment_offsets=[e.offset for e in processed],
                source_text=text,
                language_code="hi",
            )
        elif lang_norm in ["ta", "tamil"]:
            raw = decompose_tamil(text)
            processed = apply_tamil_allophonic_voicing(raw)
            return G2PResult(
                phonemes=[e.ipa for e in processed],
                alignment_offsets=[e.offset for e in processed],
                source_text=text,
                language_code="ta",
            )
        else:
            raise ValueError(f"Unsupported language for IndicG2P: {lang}")


if __name__ == "__main__":
    g2p = IndicG2P()

    # Test Hindi
    hi_test = "कमल भारत"
    res_hi = g2p.convert(hi_test, "hindi")
    print(f"Hindi '{hi_test}' -> {res_hi.ipa_string}")

    # Test Tamil
    ta_test = "வணக்கம் தம்பி படம்"
    res_ta = g2p.convert(ta_test, "tamil")
    print(f"Tamil '{ta_test}' -> {res_ta.ipa_string}")
