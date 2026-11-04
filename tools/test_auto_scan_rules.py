#!/usr/bin/env python3
"""离线回归：70 项修复必须保留扫描范围、原始指令流和字符串约束。"""

import hashlib
import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'app/src/main/assets/host-profiles'


def digest(value):
    return hashlib.sha256(json.dumps(value, ensure_ascii=False, separators=(',', ':')).encode('utf-8')).hexdigest()


class AutomaticRulesTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.rules = json.loads((ASSETS / 'automatic-2.3.4-b1.rules.json').read_text(encoding='utf-8'))
        cls.audit = json.loads((ROOT / 'docs/dexkit-2.3.4-b1-audit.json').read_text(encoding='utf-8'))
        cls.by_id = {kind + ':' + rule['id']: rule
                     for kind, section in (('class', 'classes'), ('method', 'methods'))
                     for rule in cls.rules[section]}

    def test_full_coverage_and_no_duplicate_ids(self):
        self.assertEqual((441, 899, 0), tuple(len(self.rules[k]) for k in ('classes', 'methods', 'fields')))
        for key in ('classes', 'methods'):
            self.assertEqual(len(self.rules[key]), len({rule['id'] for rule in self.rules[key]}))
        self.assertEqual(70, len(self.audit['items']))

    def test_failed_rules_keep_full_original_fingerprints(self):
        for item in self.audit['items']:
            with self.subTest(id=item['id']):
                rule = self.by_id[item['kind'] + ':' + item['id']]
                if item['kind'] == 'class':
                    self.assertTrue(rule['methodShapes'])
                    self.assertEqual(item['originalRelationSha256'], digest(rule['relation']))
                else:
                    self.assertEqual(item['originalOpcodesSha256'], digest(rule['opcodes']))
                    self.assertEqual(item['originalStringsSha256'], digest(rule['strings']))
                    self.assertEqual(item['verifiedSignature'], rule['signature'])
                    self.assertEqual(item['static'], rule['static'])
                    self.assertIn('types', rule)

    def test_payloads_are_known_and_not_removed(self):
        payload_methods = 0
        for rule in self.rules['methods']:
            opcodes = rule.get('opcodes', [])
            self.assertTrue(all(0 <= op <= 255 or op in (256, 512, 768) for op in opcodes))
            if any(op > 255 for op in opcodes):
                payload_methods += 1
        self.assertEqual(15, payload_methods)

    def test_ambiguous_methods_have_independent_constraints(self):
        expected = {
            'androidx.compose.foundation.layout.SizeKt#height-3ABfNKs': [1, 5],
            'app.zhendong.reamicro.ui.backup.components.DriveCardKt#Yun115NetDiskCard': [1026577780],
            'app.zhendong.reamicro.ui.setting.AboutScreenKt#AboutScreen': [-970017688],
        }
        for key, numbers in expected.items():
            self.assertEqual(numbers, self.by_id['method:' + key]['numbers'])
        caller = self.by_id['method:org.epub.FontProvider#builtInSong']['calledBy']
        self.assertEqual('app.zhendong.reamicro.ui.reader.ReaderViewModel', caller['owner'])
        self.assertEqual('<init>', caller['stable'])
        self.assertFalse(caller['static'])

    def test_setter_types_are_not_interchangeable(self):
        visual = self.by_id['method:org.epub.ui.EpubSelectionController#setDragHandleVisual']
        dragging = self.by_id['method:org.epub.ui.EpubSelectionController#setDraggingHandle']
        self.assertEqual('(Lp5a;)V', visual['signature'])
        self.assertEqual('org.epub.ui.SelectionHandleVisual', visual['types'][0])
        self.assertEqual('(Ldt2;)V', dragging['signature'])
        self.assertIn('0', dragging['parameterShapes'])


if __name__ == '__main__':
    unittest.main()
