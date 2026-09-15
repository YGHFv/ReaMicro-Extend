from pathlib import Path
import runpy
import tempfile
import unittest
import zipfile


build_module = runpy.run_path(str(Path(__file__).with_name("build-ksu-module.py")))["build_module"]
EXPECTED_FILES = {"module.prop", "customize.sh", "service.sh", "watchdog.sh", "runner.sh", "uninstall.sh"}


class KsuModulePackagingTest(unittest.TestCase):
    def setUp(self):
        workspace = tempfile.TemporaryDirectory()
        self.addCleanup(workspace.cleanup)
        self.source = Path(workspace.name) / "module"
        self.output = Path(workspace.name) / "output"
        self.source.mkdir()
        for name in EXPECTED_FILES:
            content = "id=reamicro_automation\nversion=0.1.0\n" if name == "module.prop" else "#!/system/bin/sh\nexit 0\n"
            (self.source / name).write_text(content, encoding="utf-8", newline="\n")

    def test_filename_uses_module_version(self):
        (self.source / "module.prop").write_text(
            "id=reamicro_automation\nversion=0.2.1-beta.1\n", encoding="utf-8", newline="\n"
        )
        archive = build_module(self.source, self.output)
        self.assertEqual("ReaMicro-Automation-KSU-0.2.1-beta.1.zip", archive.name)

    def test_only_module_files_are_packaged(self):
        (self.source / "state.json").write_text('{"token":"must-not-be-packaged"}', encoding="utf-8")
        with zipfile.ZipFile(build_module(self.source, self.output)) as archive:
            self.assertEqual(EXPECTED_FILES, set(archive.namelist()))
            self.assertIsNone(archive.testzip())

    def test_encoding_and_unix_permissions(self):
        (self.source / "runner.sh").write_bytes("\ufeff#!/system/bin/sh\r\necho '测试'\r\n".encode("utf-8"))
        with zipfile.ZipFile(build_module(self.source, self.output)) as archive:
            for entry in archive.infolist():
                expected_mode = 0o100755 if entry.filename.endswith(".sh") else 0o100644
                self.assertEqual(3, entry.create_system)
                self.assertEqual(expected_mode, entry.external_attr >> 16)
                self.assertNotIn(b"\r", archive.read(entry))
                self.assertFalse(archive.read(entry).startswith(b"\xef\xbb\xbf"))
            self.assertIn("测试", archive.read("runner.sh").decode("utf-8"))

    def test_archive_is_reproducible(self):
        original = build_module(self.source, self.output).read_bytes()
        (self.source / "service.sh").touch()
        self.assertEqual(original, build_module(self.source, self.output).read_bytes())

    def test_missing_script_does_not_create_archive(self):
        (self.source / "service.sh").unlink()
        with self.assertRaises(FileNotFoundError):
            build_module(self.source, self.output)
        self.assertFalse(self.output.exists())

    def test_invalid_versions_are_rejected(self):
        for version in ("", "../escape", "0.1.0/bad", "test version"):
            with self.subTest(version=version):
                (self.source / "module.prop").write_text(
                    f"id=reamicro_automation\nversion={version}\n", encoding="utf-8", newline="\n"
                )
                with self.assertRaises(ValueError):
                    build_module(self.source, self.output)
                self.assertFalse(self.output.exists())

    def test_wrong_module_id_is_rejected(self):
        (self.source / "module.prop").write_text("id=other\nversion=0.1.0\n", encoding="utf-8", newline="\n")
        with self.assertRaises(ValueError):
            build_module(self.source, self.output)
        self.assertFalse(self.output.exists())

    def test_repository_module_builds(self):
        with zipfile.ZipFile(build_module(output_directory=self.output)) as archive:
            self.assertEqual(EXPECTED_FILES, set(archive.namelist()))
            self.assertIn(b"id=reamicro_automation\n", archive.read("module.prop"))


if __name__ == "__main__":
    unittest.main()
