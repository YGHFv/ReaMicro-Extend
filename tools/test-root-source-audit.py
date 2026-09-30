"""Execute the actual inspector shell in a sandbox; other checks are source contracts.
No root authorization, active module changes, APK build or real account tasks.
"""
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "app/src/main/java/com/reamicro/fix/cloud/root"
MODULE = ROOT / "module/reamicro-automation"
ANDROID = "{http://schemas.android.com/apk/res/android}"


class RootSourceAuditTest(unittest.TestCase):
    def inspector(self, extra_args):
        with tempfile.TemporaryDirectory(prefix="reamicro-inspector-test-") as temp:
            base = Path(temp)
            module = base / "modules/reamicro_automation"
            shutil.copytree(MODULE, module)
            state = base / "private"
            state.mkdir()
            source = (JAVA / "RootModuleInspector.kt").read_text()
            script = source.split('val script: String = """', 1)[1].split('""".trimIndent()', 1)[0]
            script = script.replace("${'$'}", "$")
            script = script.replace("${RootTaskRepository.MODULE_DIRECTORY}", str(module))
            script = script.replace("${RootTaskRepository.STATE_DIRECTORY}", str(state))
            script = script.replace("${RootModuleManager.UPDATE_DIRECTORY}", str(base / "staged"))
            script = script.replace("/data/adb", str(base / "adb"))
            (base / "adb").mkdir()
            # A harmless child has only argv markers. It never invokes Java or RootTaskMain.
            process = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(20)", *extra_args])
            try:
                (state / "runner.pid").write_text(str(process.pid))
                result = subprocess.run(["sh", "-c", script], text=True, capture_output=True, timeout=5)
                self.assertEqual(0, result.returncode, result.stderr)
                return result.stdout
            finally:
                process.terminate()
                process.wait(timeout=5)

    def test_exec_replaced_runner_still_shows_running(self):
        output = self.inspector(["com.reamicro.fix.cloud.root.RootTaskMain", "run"])
        self.assertIn("running=1", output)
        self.assertIn("probe=complete", output)

    def test_nonexecution_java_command_is_not_a_running_task(self):
        output = self.inspector(["com.reamicro.fix.cloud.root.RootTaskMain", "configure"])
        self.assertNotIn("running=1", output)

    def test_unrelated_pid_is_not_a_running_task(self):
        self.assertNotIn("running=1", self.inspector(["unrelated", "run"]))

    def test_daemon_lock_is_owned_by_ash_and_closed_in_background_jobs(self):
        script = (MODULE / "watchdog.sh").read_text()
        self.assertLess(script.index("--reamicro-ash"), script.index('exec 8>'))
        self.assertIn('"$BUSYBOX" flock -n 8', script)
        self.assertNotIn('flock -n "$STATE/daemon.lock"', script)
        self.assertIn('run 8>&- </dev/null', script)
        self.assertIn('sleep "$1" 8>&- &', script)
        self.assertIn('sleep 2 8>&- &', script)

    def test_sync_receiver_rejects_unprivileged_senders(self):
        manifest = ET.parse(ROOT / "app/src/main/AndroidManifest.xml")
        receiver = next(e for e in manifest.iter("receiver")
                        if e.get(ANDROID + "name") == ".cloud.root.RootTaskSyncReceiver")
        self.assertEqual("android.permission.DUMP", receiver.get(ANDROID + "permission"))
        self.assertEqual("true", receiver.get(ANDROID + "exported"))

    def test_input_budget_checked_before_append(self):
        source = (JAVA / "RootTaskMain.kt").read_text()
        section = source.split("private fun readInput()", 1)[1].split("private fun selfTest()", 1)[0]
        self.assertIn("CharArray(4096)", section)
        self.assertLess(section.index("require(result.length + count"), section.index("result.append(buffer"))
        self.assertNotIn("it.readText()", section)

    def test_manager_commands_remain_provider_specific(self):
        source = (JAVA / "RootModuleManager.kt").read_text()
        for token in ("--install-module", "module install", "module undo-uninstall",
                      "module enable", "module disable", "module uninstall"):
            self.assertIn(token, source)
        self.assertIn("count", source)
        self.assertIn('exit 3', source)

    def test_root_only_warning_and_version_agree(self):
        self.assertNotIn("切回免 Root", (MODULE / "customize.sh").read_text())
        props = dict(line.split("=", 1) for line in (MODULE / "module.prop").read_text().splitlines() if "=" in line)
        self.assertIn("REAMICRO_MODULE_VERSION=" + props["versionCode"], (MODULE / "common.sh").read_text())
        for lang in ("values", "values-en"):
            resources = ET.parse(ROOT / f"app/src/main/res/{lang}/strings.xml")
            warning = next(e.text for e in resources.iter("string") if e.get("name") == "root_confirm_enable")
            self.assertRegex(warning, r"深睡|深度休眠" if lang == "values" else r"deep sleep")


if __name__ == "__main__":
    unittest.main()
