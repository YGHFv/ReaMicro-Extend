#!/system/bin/sh
REAMICRO_MODULE_VERSION=10

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

reamicro_daemon_alive() {
  pid=$(cat "$STATE/daemon.pid" 2>/dev/null)
  case "$pid" in ''|*[!0-9]*) return 1 ;; esac
  [ -r "/proc/$pid/cmdline" ] || return 1
  tr '\000' '\n' <"/proc/$pid/cmdline" 2>/dev/null | grep -Fxq "$MODDIR/watchdog.sh"
}

# Only scheduling and execution hold this bounded lock; the alarm wait releases it.
reamicro_acquire_wake_lock() {
  { printf 'reamicro_tasks 300000000000\n' >/sys/power/wake_lock; } 2>/dev/null
}

reamicro_release_wake_lock() {
  { printf 'reamicro_tasks\n' >/sys/power/wake_unlock; } 2>/dev/null || :
}

reamicro_read_next() {
  next_value=$(cat "$STATE/next-run-at" 2>/dev/null)
  case "$next_value" in ''|*[!0-9]*|???????????????????*) next_value=0 ;; esac
  while [ "${next_value#0}" != "$next_value" ]; do next_value=${next_value#0}; done
  printf '%s\n' "${next_value:-0}"
}

# Arguments: now, next task, last recovery probe, retry-not-before.
# Called in a subshell so temporary variables cannot change watchdog state.
reamicro_wait_seconds() {
  deadline=$(($3 + 900))
  if [ "$2" -gt 0 ] && [ "$2" -lt "$deadline" ]; then deadline=$2; fi
  if [ "$4" -gt "$1" ] && [ "$deadline" -lt "$4" ]; then deadline=$4; fi
  delay=$((deadline - $1))
  [ "$delay" -ge 1 ] || delay=1
  [ "$delay" -le 900 ] || delay=900
  printf '%s\n' "$delay"
}
