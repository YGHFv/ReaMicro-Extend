#!/usr/bin/env python3
"""成员 overlay 的交换、唯一性与迁移持有者回归测试。"""

import unittest
from pathlib import Path

import retarget_profile as rp


def feature(fields=(), methods=(), parents=()):
    return rp.ClassFeature("", (), frozenset(), frozenset(), 0,
                           fields=[{"n": n, "d": d} for n, d in fields],
                           methods=[{"n": n} for n in methods], parents=parents)


class MemberOverlayTests(unittest.TestCase):
    def setUp(self):
        self.profile = {"classes": {"Reader": "old", "Facade": "oldFacade"},
                        "typeAliases": {}}
        self.target = {"new": feature((("i0", "Ljava/util/LinkedHashMap;"),
                                       ("f0", "Ljava/util/LinkedHashMap;"))),
                       "holder": feature(methods=("a", "b"))}
        self.classes = {"Reader": "new", "Facade": "holder"}
        self.fields = {"Reader#pages": "f0", "Reader#index": "i0"}
        self.methods = {"Facade#entry": "a"}
        self.aliases = {}
        self.owners = {}

    def apply(self, payload, oracle=None):
        return rp.apply_member_overlays(
            self.profile, self.target, self.classes, self.aliases, self.methods,
            self.fields, self.owners, [(Path("overlay.json"), payload)], oracle,
        )

    def test_field_swap_is_atomic(self):
        self.apply({"fields": {"Reader#pages": "i0", "Reader#index": "f0"}})
        self.assertEqual({"Reader#pages": "i0", "Reader#index": "f0"}, self.fields)

    def test_duplicate_target_rejected_without_mutation(self):
        before = dict(self.fields)
        with self.assertRaisesRegex(ValueError, "目标冲突"):
            self.apply({"fields": {"Reader#pages": "i0"}})
        self.assertEqual(before, self.fields)

    def test_missing_field_rejected(self):
        with self.assertRaisesRegex(ValueError, "成员不存在"):
            self.apply({"fields": {"Reader#pages": "missing"}})

    def test_target_descriptor_rejected(self):
        with self.assertRaisesRegex(ValueError, "描述符不匹配"):
            self.apply({"fields": {"Reader#pages": {"target": "f0", "descriptor": "I"}}})

    def test_method_owner_can_move_independently(self):
        self.target["split"] = feature(methods=("renamed",))
        self.apply({"methods": {"Facade#entry": "renamed"},
                    "methodOwners": {"Facade#entry": "split"}})
        self.assertEqual("renamed", self.methods["Facade#entry"])
        self.assertEqual("split", self.owners["Facade#entry"])

    def test_alias_requires_known_source_but_can_share_holder(self):
        self.apply({"typeAliases": {"SplitFacade": "holder"},
                    "methods": {"SplitFacade#helper": "b"}},
                   oracle={"SplitFacade": feature()})
        self.assertEqual("holder", self.aliases["SplitFacade"])
        with self.assertRaisesRegex(ValueError, "未知逻辑类"):
            self.apply({"typeAliases": {"Typo": "holder"}})

    def test_alias_cannot_hide_duplicate_method(self):
        with self.assertRaisesRegex(ValueError, "目标冲突"):
            self.apply({"typeAliases": {"SplitFacade": "holder"},
                        "methods": {"SplitFacade#entry": "a"}},
                       oracle={"SplitFacade": feature()})

    def test_orphan_owner_and_constructor_rejected(self):
        with self.assertRaisesRegex(ValueError, "悬空"):
            self.apply({"methodOwners": {"Facade#missing": "holder"}})
        with self.assertRaisesRegex(ValueError, "构造器"):
            self.apply({"methods": {"Facade#entry": "<init>"}})

    def test_member_only_payload_is_not_treated_as_classes(self):
        self.assertEqual({}, rp.overlay_classes({"fields": {"Reader#pages": "i0"}}))
        self.assertEqual({"Reader": "new"}, rp.overlay_classes({"Reader": "new"}))

    def test_inherited_alias_conflict_rejected(self):
        self.target["child"] = feature(parents=("holder",))
        self.classes["Child"] = "child"
        self.profile["classes"]["Child"] = "oldChild"
        with self.assertRaisesRegex(ValueError, "目标冲突"):
            self.apply({"methods": {"Child#entry": "a"}})


if __name__ == "__main__":
    unittest.main()
