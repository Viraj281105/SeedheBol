"""
tools/tests/test_content_compiler.py
===================================
Unit tests for curriculum content compiler and schema validator.
Supports both unittest and pytest.
"""

import json
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parents[1] / "content_compiler"))

from generate_situations import get_construction_tamil_situations
from compile_l1_confusion import build_bhojpuri_tamil_confusion_dataset


class TestCurriculumCompiler(unittest.TestCase):
    def test_construction_situations_schema_integrity(self):
        """All 10 construction situations must have valid AST nodes and branch targets."""
        situations = get_construction_tamil_situations()
        self.assertEqual(len(situations), 10)

        for sit in situations:
            self.assertIn("situation_id", sit)
            self.assertIn("entry_node_id", sit)
            self.assertIn(sit["entry_node_id"], sit["nodes"])

            for node_id, node in sit["nodes"].items():
                self.assertIn("l2_text", node)
                self.assertGreater(len(node["l2_text"]), 0)
                self.assertIn("transliteration", node)
                self.assertIn("l1_translation", node)

                for branch in node.get("branches", []):
                    target = branch["target_node_id"]
                    self.assertIn(target, sit["nodes"])

    def test_confusion_dataset_integrity(self):
        """L1 confusion dataset must contain all core Dravidian retroflex targets."""
        data = build_bhojpuri_tamil_confusion_dataset()
        pairs = data["confusion_pairs"]
        targets = [p["target_phoneme_ipa"] for p in pairs]

        self.assertIn("ʈ", targets)  # Retroflex stop
        self.assertIn("ɻ", targets)  # Tamil zha
        self.assertIn("ɭ", targets)  # Retroflex L
        self.assertIn("ɳ", targets)  # Retroflex N


if __name__ == "__main__":
    unittest.main()
