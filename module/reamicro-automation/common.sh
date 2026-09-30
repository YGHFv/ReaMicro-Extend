#!/system/bin/sh
REAMICRO_MODULE_VERSION=9
# Shared by install, service and watchdog. Do not fall back to an unlocked daemon.
find_busybox() {
  magisk_tmp="$MAGISKTMP"
  if [ -z "$magisk_tmp" ] && command -v magisk >/dev/null 2>&1; then
    magisk_tmp=$(magisk --path 2>/dev/null)
  fi
  for candidate in /data/adb/ksu/bin/busybox /data/adb/ap/bin/busybox \
      "${magisk_tmp:+$magisk_tmp/.magisk/busybox/busybox}" \
      "${magisk_tmp:+$magisk_tmp/.magisk/busybox}" "${magisk_tmp:+$magisk_tmp/busybox}" \
      /data/adb/magisk/busybox /debug_ramdisk/.magisk/busybox/busybox /sbin/.magisk/busybox/busybox \
      /debug_ramdisk/.magisk/busybox /sbin/.magisk/busybox \
      "$(command -v busybox 2>/dev/null)"; do
    [ -n "$candidate" ] && [ -x "$candidate" ] || continue
    applets=$("$candidate" --list 2>/dev/null) || continue
    printf '%s\n' "$applets" | grep -qx flock || continue
    printf '%s\n' "$applets" | grep -qx timeout || continue
    printf '%s\n' "$applets" | grep -qx nohup || continue
    printf '%s\n' "$candidate"
    return 0
  done
  return 1
}

# Fast PID-file verification; never trust a bare/stale PID and never kill based on this alone.
reamicro_daemon_alive() {
  pid=$(cat "$STATE/daemon.pid" 2>/dev/null)
  case "$pid" in ''|*[!0-9]*) return 1 ;; esac
  [ -r "/proc/$pid/cmdline" ] || return 1
  tr '\000' '\n' <"/proc/$pid/cmdline" 2>/dev/null | grep -Fxq "$MODDIR/watchdog.sh"
}
