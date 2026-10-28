#!/usr/bin/env python3
"""符号工具的描述符与继承查找回归测试。"""

import unittest

import hostsym as hs
from check_profile import ancestors


class DescriptorTests(unittest.TestCase):
    def test_printed_spaces_do_not_create_parameter_slots(self):
        self.assertEqual(["J", "Ljr3;", "I"], hs.parse_params("(J Ljr3; I)V"))
        self.assertEqual(3, hs.shape("(J Ljr3; I)V")[0])

    def test_arrays_retain_rank_and_primitive_kind(self):
        self.assertEqual(["[I", "[[J", "[Ljava/lang/String;"],
                         hs.parse_params("([I [[J [Ljava/lang/String;)V"))
        self.assertNotEqual(hs.parse_params("([I)V"), hs.parse_params("(I)V"))

    def test_primitive_slots_are_not_collapsed(self):
        self.assertNotEqual(hs.shape("(I Ljr3;)V"), hs.shape("(J Ljr3;)V"))
        self.assertNotEqual(hs.shape("(Z Ljr3;)V"), hs.shape("(I Ljr3;)V"))

    def test_reference_and_obfuscated_ancestors_share_rules(self):
        features = {
            "child": {"rawSup": "Lparent;", "rawIfc": ("Liface;",)},
            "parent": {"rawSup": "Ljava/lang/Object;", "rawIfc": ()},
            "iface": {"rawSup": "Ljava/lang/Object;", "rawIfc": ("Lchild;",)},
        }
        self.assertEqual(["child", "parent", "iface"], ancestors(features, "child"))


if __name__ == "__main__":
    unittest.main()
