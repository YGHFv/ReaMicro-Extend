import unittest

from b4_migration_audit import ancestors


class MigrationAuditTest(unittest.TestCase):
    def test_uses_raw_dex_parent_instead_of_normalized_shape(self):
        host = {
            "Child": {"sup": "*", "rawSup": "LParent;"},
            "Parent": {"sup": "*", "rawSup": "Ljava/lang/Object;"},
            "java.lang.Object": {"sup": ""},
        }
        self.assertEqual(["Child", "Parent", "java.lang.Object"], list(ancestors(host, "Child")))

    def test_missing_class_is_not_invented(self):
        self.assertEqual([], list(ancestors({}, "Absent")))

    def test_parent_cycle_is_bounded(self):
        host = {"A": {"rawSup": "LB;"}, "B": {"rawSup": "LA;"}}
        self.assertEqual(["A", "B"], list(ancestors(host, "A")))


if __name__ == "__main__":
    unittest.main()
