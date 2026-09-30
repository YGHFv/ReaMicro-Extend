"""Sandbox lifecycle tests: no device Root, /data/adb writes, flashing or real app processes."""
from pathlib import Path
import os
import shlex
import shutil
import subprocess
import tempfile
import time
import unittest

SOURCE = Path(__file__).resolve().parents[1] / "module" / "reamicro-automation"

class RootModuleLifecycleTest(unittest.TestCase):
    def setUp(self):
        self.workspace = tempfile.TemporaryDirectory(prefix="reamicro-root-test-")
        self.base = Path(self.workspace.name)
        self.module = self.base / "modules" / "reamicro_automation"
        self.state = self.base / "private"
        self.legacy = self.base / "service.d" / "reamicro-watchdog.sh"
        self.bin = self.base / "bin"
        self.module.mkdir(parents=True)
        self.bin.mkdir()
        self.env = os.environ.copy()
        self.env["PATH"] = str(self.bin) + os.pathsep + self.env["PATH"]
        self.env["ASH_STANDALONE"] = "0"
        self.busybox = self.bin / "busybox"
        self.executable(self.busybox, """#!/bin/sh
if [ "$1" = --list ]; then printf 'flock\\ntimeout\\nnohup\\nsh\\n'; exit 0; fi
# Fail on GNU-only flock options, just like the supported BusyBox applet.
if [ "$1" = flock ] && [ "$2" = -w ]; then exit 90; fi
exec "$@"
""")
        self.executable(self.bin / "getprop", "#!/bin/sh\nprintf '1\\n'\n")
        real_busybox = os.environ.get("REAMICRO_TEST_BUSYBOX")
        if real_busybox:
            binary = Path(real_busybox).resolve(strict=True)
            self.busybox.unlink()
            shutil.copyfile(binary, self.busybox)
            self.busybox.chmod(0o700)
        for source in SOURCE.glob("*.sh"):
            text = source.read_text()
            text = text.replace("/data/adb/reamicro-automation", str(self.state))
            text = text.replace("/data/adb/service.d/reamicro-watchdog.sh", str(self.legacy))
            text = text.replace("/system/bin/sh", "/bin/sh")
            text = text.replace("/sys/power/", str(self.base / "unused-sys") + "/")
            # Never match an actual ReaMicro process in the host /proc.
            text = text.replace("com.reamicro.fix.cloud.root.RootTaskMain", self.base.name + ".FakeRootMain")
            self.executable(self.module / source.name, text)
        common = self.module / "common.sh"
        common.write_text(common.read_text() +
            "\nfind_busybox() { printf '%s\\n' " + shlex.quote(str(self.busybox)) + "; }\n")
        shutil.copy2(SOURCE / "module.prop", self.module / "module.prop")
        self.executable(self.module / "runner.sh",
            "#!/bin/sh\nprintf 'run\\n' >>" + shlex.quote(str(self.base / "runs")) + "\nexit 0\n")

    @staticmethod
    def executable(path, text):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)
        path.chmod(0o755)

    def run_script(self, name, *args, check=True):
        result = subprocess.run(["sh", str(self.module / name), *args],
            env=self.env, text=True, capture_output=True, timeout=40)
        if check:
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        return result

    def configure(self):
        self.state.mkdir(exist_ok=True)
        (self.state / "state.json").write_text('{"enabled":true,"token":"sandbox-only"}')
        (self.state / "next-run-at").write_text(str(int(time.time()) + 3600))

    def tearDown(self):
        # All executable paths and class names have sandbox-only identities.
        try:
            (self.module / "remove").touch()
            self.run_script("uninstall.sh", check=False)
        finally:
            self.workspace.cleanup()

    def test_shell_syntax_and_busybox_portability(self):
        for script in SOURCE.glob("*.sh"):
            result = subprocess.run(["sh", "-n", str(script)], capture_output=True, text=True)
            self.assertEqual(0, result.returncode, str(script) + result.stderr)
            self.assertNotIn("flock -w", script.read_text())

    def test_first_install_does_not_start_without_opt_in(self):
        self.run_script("service.sh")
        self.assertFalse(self.state.exists())
        self.assertFalse((self.base / "runs").exists())

    def test_repeated_start_is_single_instance_and_stop_is_persistent(self):
        self.configure()
        self.run_script("service.sh", "start")
        first = (self.state / "daemon.pid").read_text()
        self.run_script("service.sh", "start")
        self.assertEqual(first, (self.state / "daemon.pid").read_text())
        self.run_script("stop.sh")
        self.assertTrue((self.state / "stop").exists())
        self.assertFalse((self.state / "daemon.pid").exists())
        self.assertFalse((self.state / "heartbeat").exists())
        self.run_script("service.sh")
        self.assertFalse((self.state / "daemon.pid").exists())
        self.run_script("service.sh", "start")
        self.assertNotEqual(first, (self.state / "daemon.pid").read_text())

    def test_concurrent_start_does_not_spawn_duplicate_schedulers(self):
        self.configure()
        processes = [subprocess.Popen(["sh", str(self.module / "service.sh"), "start"],
            env=self.env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True) for _ in range(3)]
        for process in processes:
            out, err = process.communicate(timeout=40)
            self.assertEqual(0, process.returncode, out + err)
        pid = (self.state / "daemon.pid").read_text().strip()
        self.assertTrue(pid.isdigit())
        time.sleep(0.3)
        self.assertEqual(["run"], (self.base / "runs").read_text().splitlines())

    def test_uninstall_without_apk_cleans_only_own_state(self):
        self.configure()
        self.legacy.parent.mkdir()
        self.legacy.write_text("legacy script")
        unrelated = self.base / "unrelated-module-data"
        unrelated.write_text("keep")
        self.run_script("service.sh", "start")
        (self.module / "remove").touch()
        self.run_script("uninstall.sh")
        self.assertFalse(self.state.exists())
        self.assertFalse(self.legacy.exists())
        self.assertEqual("keep", unrelated.read_text())
        self.assertTrue(self.module.is_dir(), "manager owns removal of module directory")

    def test_uninstall_can_clean_credentials_when_busybox_is_missing(self):
        self.configure()
        (self.module / "common.sh").write_text("find_busybox() { return 1; }\n")
        self.run_script("uninstall.sh")
        self.assertFalse(self.state.exists())

    def test_stale_pid_never_counts_as_a_running_daemon(self):
        self.configure()
        (self.state / "daemon.pid").write_text(str(os.getpid()))
        self.run_script("service.sh", "start")
        self.assertNotEqual(str(os.getpid()), (self.state / "daemon.pid").read_text().strip())
        self.assertTrue((self.state / "heartbeat").exists())
    def test_heartbeat_keeps_advancing_during_a_slow_runner(self):
        self.configure()
        self.executable(self.module / "runner.sh", "#!/bin/sh\nsleep 12\n")
        self.run_script("service.sh", "start")
        deadline = time.time() + 5
        while not (self.state / "runner.pid").exists() and time.time() < deadline:
            time.sleep(0.1)
        self.assertTrue((self.state / "runner.pid").exists())
        initial = int((self.state / "heartbeat").read_text().strip() or "0")
        time.sleep(3.5)
        current = int((self.state / "heartbeat").read_text().strip() or "0")
        self.assertGreater(current, initial)
        self.assertTrue((self.state / "runner.pid").exists())
    def test_android_shell_enters_busybox_before_descriptor_lock(self):
        for name in ("service.sh", "stop.sh", "uninstall.sh"):
            text = (SOURCE / name).read_text()
            self.assertIn('exec "$BUSYBOX" sh "$0" --reamicro-ash "$@"', text)
            self.assertLess(text.index('--reamicro-ash'), text.index('exec 9>'))

    def test_script_version_stamp_matches_module_metadata(self):
        prop = (SOURCE / "module.prop").read_text()
        version = next(line.split("=", 1)[1] for line in prop.splitlines() if line.startswith("versionCode="))
        self.assertIn("REAMICRO_MODULE_VERSION=" + version, (SOURCE / "common.sh").read_text())

    def test_magisk_nested_busybox_path_is_discovered(self):
        nested = self.base / "magisktmp/.magisk/busybox/busybox"
        nested.parent.mkdir(parents=True)
        shutil.copyfile(self.busybox, nested)
        nested.chmod(0o700)
        # Exercise production discovery, not the lifecycle fixture's find_busybox override.
        common = (SOURCE / "common.sh").read_text()
        for prefix in ("/data/adb", "/debug_ramdisk", "/sbin"):
            common = common.replace(prefix, str(self.base / "absent") + prefix)
        probe = self.base / "production-common.sh"
        probe.write_text(common)
        env = self.env.copy()
        env["MAGISKTMP"] = str(self.base / "magisktmp")
        result = subprocess.run(["sh", "-c", '. "$1"; find_busybox', "_", str(probe)],
                                env=env, capture_output=True, text=True, timeout=10)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(str(nested), result.stdout.strip())

    def test_runner_cannot_inherit_daemon_lock(self):
        self.configure()
        captured = self.base / "runner-fds"
        self.executable(self.module / "runner.sh",
            "#!/bin/sh\nfor fd in /proc/$$/fd/*; do readlink \"$fd\"; done >" +
            shlex.quote(str(captured)) + "\nexit 0\n")
        self.run_script("service.sh", "start")
        deadline = time.time() + 5
        while not captured.exists() and time.time() < deadline:
            time.sleep(0.05)
        self.assertTrue(captured.exists())
        self.assertNotIn(str(self.state / "daemon.lock"), captured.read_text().splitlines())

    def test_boot_wait_can_be_stopped_and_restarted(self):
        self.configure()
        self.executable(self.bin / "getprop", "#!/bin/sh\nprintf '0\\n'\n")
        self.run_script("service.sh", "start")
        self.run_script("stop.sh")
        self.assertFalse((self.state / "daemon.pid").exists())
        self.executable(self.bin / "getprop", "#!/bin/sh\nprintf '1\\n'\n")
        self.run_script("service.sh", "start")
        self.assertTrue((self.state / "daemon.pid").exists())

    def test_manager_disable_is_observed_during_running_task(self):
        self.configure()
        self.executable(self.module / "runner.sh",
            "#!/bin/sh\ntrap 'exit 0' TERM INT\nwhile :; do sleep 0.2; done\n")
        self.run_script("service.sh", "start")
        deadline = time.time() + 5
        while not (self.state / "runner.pid").exists() and time.time() < deadline:
            time.sleep(0.05)
        self.assertTrue((self.state / "runner.pid").exists())
        (self.module / "disable").touch()
        deadline = time.time() + 10
        while (self.state / "daemon.pid").exists() and time.time() < deadline:
            time.sleep(0.1)
        self.assertFalse((self.state / "daemon.pid").exists())
        self.assertFalse((self.state / "runner.pid").exists())
        self.run_script("service.sh", "start")
        self.assertFalse((self.state / "daemon.pid").exists())

    def test_each_manager_installs_without_erasing_existing_state(self):
        self.configure()
        before = (self.state / "state.json").read_text()
        for provider in ("KSU", "APATCH", "MAGISK_VER_CODE"):
            env = self.env.copy()
            env.update({"KSU": "", "APATCH": "", "MAGISK_VER_CODE": "",
                        "BOOTMODE": "true", "MODPATH": str(self.module)})
            env[provider] = "true" if provider != "MAGISK_VER_CODE" else "30000"
            wrapper = """
ui_print() { printf '%s\\n' "$*"; }
abort() { printf '%s\\n' "$*" >&2; exit 1; }
set_perm_recursive() { :; }
set_perm() { :; }
. "$1"
"""
            result = subprocess.run(["sh", "-c", wrapper, "_", str(self.module / "customize.sh")],
                env=env, text=True, capture_output=True, timeout=10)
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertEqual(before, (self.state / "state.json").read_text())
            self.assertTrue((self.module / "skip_mount").exists())

if __name__ == "__main__":
    unittest.main()
