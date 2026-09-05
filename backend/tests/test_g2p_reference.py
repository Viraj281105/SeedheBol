"""
tools/tests/test_g2p_reference.py
================================
Unit tests for Python Reference Indic G2P Engine.
Supports both unittest and pytest.
"""

import sys
import unittest
from pathlib import Path

# Ensure tools/content_compiler is importable
sys.path.insert(0, str(Path(__file__).parents[1] / "content_compiler"))

from g2p_reference import (
    IndicG2P,
    decompose_devanagari,
    apply_hindi_schwa_deletion,
    decompose_tamil,
    apply_tamil_allophonic_voicing,
)


class TestHindiG2P(unittest.TestCase):
    def setUp(self):
        self.g2p = IndicG2P()

    def test_word_final_schwa_deletion(self):
        """Word-final inherent schwas must be deleted in multi-syllabic words."""
        res = self.g2p.convert("कमल", "hindi")
        self.assertEqual(res.phonemes[-1], "l")
        self.assertIn("ə", res.phonemes)

    def test_hindi_aspirated_stops(self):
        """Aspirated stops must preserve aspiration feature."""
        res = self.g2p.convert("भारत", "hindi")
        self.assertEqual(res.phonemes[0], "bʰ")
        self.assertEqual(res.phonemes[1], "aː")

    def test_matra_overrides_inherent_schwa(self):
        """Dependent vowel signs (matras) replace inherent schwa."""
        res = self.g2p.convert("किताब", "hindi")
        self.assertEqual(res.phonemes[0], "k")
        self.assertEqual(res.phonemes[1], "ɪ")


class TestTamilG2P(unittest.TestCase):
    def setUp(self):
        self.g2p = IndicG2P()

    def test_tamil_retroflex_zha(self):
        """Tamil special retroflex approximant /ɻ/ (ழ) must be correctly mapped."""
        res = self.g2p.convert("பழம்", "tamil")
        self.assertIn("ɻ", res.phonemes)

    def test_intervocalic_stop_voicing(self):
        """Intervocalic stops must be voiced in surface phonology."""
        res = self.g2p.convert("படம்", "tamil")
        self.assertIn("ɖ", res.phonemes)

    def test_post_nasal_stop_voicing(self):
        """Post-nasal stops must be voiced in surface phonology."""
        res = self.g2p.convert("தம்பி", "tamil")
        self.assertIn("b", res.phonemes)

    def test_word_initial_stop_remains_voiceless(self):
        """Word-initial stops remain voiceless."""
        res = self.g2p.convert("காசு", "tamil")
        self.assertEqual(res.phonemes[0], "k")


if __name__ == "__main__":
    unittest.main()
