"""
tools/tests/test_gop_reference.py
================================
Unit tests for Goodness of Pronunciation (GOP) Scoring and L1 Diagnostic feedback.
Supports both unittest and pytest.
"""

import sys
import unittest
import numpy as np
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parents[1] / "content_compiler"))

from gop_reference import GOPScorer, ARTICULATORY_GUIDANCE_DB


class TestGOPScorer(unittest.TestCase):
    def setUp(self):
        self.scorer = GOPScorer(gop_threshold=-2.5)

    def test_high_confidence_phoneme_scores_pass(self):
        """When CTC posterior strongly matches target phoneme, GOP must pass."""
        vocab = {"k": 1, "a": 2, "m": 3}
        target = ["k", "a", "m"]

        log_probs = np.full((30, 5), -10.0)
        log_probs[0:10, 1] = 0.0   # k
        log_probs[10:20, 2] = 0.0  # a
        log_probs[20:30, 3] = 0.0  # m

        res = self.scorer.align_and_score(log_probs, vocab, target, "kam")
        self.assertGreater(res.overall_score, 90.0)
        self.assertTrue(all(s.is_acceptable for s in res.segments))
        self.assertEqual(len(res.weakest_phonemes), 0)

    def test_l1_substitution_triggers_diagnostic_feedback(self):
        """When an L1 substitution error occurs, diagnostic advice must be returned."""
        vocab = {"p": 1, "a": 2, "ʈ": 3, "t̪": 4}
        target = ["p", "a", "ʈ"]

        log_probs = np.full((30, 5), -10.0)
        log_probs[0:10, 1] = 0.0   # p
        log_probs[10:20, 2] = 0.0  # a
        log_probs[20:30, 4] = 0.0  # Dental t̪ instead of retroflex ʈ

        res = self.scorer.align_and_score(log_probs, vocab, target, "paʈ")
        retroflex_seg = res.segments[2]
        self.assertFalse(retroflex_seg.is_acceptable)
        self.assertIn("ʈ", res.weakest_phonemes)
        self.assertIsNotNone(retroflex_seg.diagnostic_message)
        self.assertIn("तालू", retroflex_seg.diagnostic_message)


if __name__ == "__main__":
    unittest.main()
