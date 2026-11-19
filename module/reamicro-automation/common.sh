#!/system/bin/sh
REAMICRO_MODULE_VERSION=12

find_busybox() {
  module_dir=${MODDIR:-$MODPATH}
  busybox_path=
  if [ -n "$module_dir" ] && [ -f "$module_dir/busybox.path" ] &&
      [ ! -L "$module_dir/busybox.path" ]; then
    busybox_path=$(cat "$module_dir/busybox.path" 2>/dev/null)
  fi
  # Installers run in BusyBox ash. Remember that executable so APK-launched
  # Android sh can find the same runtime without any provider-specific paths.
  for candidate in "$(readlink "/proc/$$/exe" 2>/dev/null)" \
      "$busybox_path" "$(command -v busybox 2>/dev/null)"; do
    case "$candidate" in /*) ;; *) continue ;; esac
    [ -f "$candidate" ] && [ -x "$candidate" ] || continue
    applets=$("$candidate" --list 2>/dev/null) || continue
    for applet in sh flock timeout nohup; do
      printf '%s\n' "$applets" | grep -qx "$applet" || continue 2
    done
    printf '%s\n' "$candidate"
    return 0
  done
  return 1
}

reamicro_module_ready() {
  [ "$(sed -n 's/^id=//p' "$MODDIR/module.prop" 2>/dev/null)" = reamicro_automation ] || return 1
  declared=$(sed -n 's/^versionCode=//p' "$MODDIR/module.prop" 2>/dev/null)
  [ "$declared" = "$REAMICRO_MODULE_VERSION" ] && return 0
  [ -f "$MODDIR/update" ] && [ "$declared" -gt "$REAMICRO_MODULE_VERSION" ] 2>/dev/null || return 1
  staged="${MODDIR%/*/*}/modules_update/${MODDIR##*/}"
  [ ! -L "$staged" ] && [ -d "$staged" ] &&
    cmp -s "$MODDIR/module.prop" "$staged/module.prop" &&
    [ "$(sed -n 's/^REAMICRO_MODULE_VERSION=//p' "$staged/common.sh" 2>/dev/null)" = "$declared" ]
}

reamicro_daemon_alive() {
  pid=$(cat "$STATE/daemon.pid" 2>/dev/null)
  case "$pid" in ''|*[!0-9]*) return 1 ;; esac
  [ -r "/proc/$pid/cmdline" ] || return 1
  [ -r "/proc/$pid/status" ] || return 1
  grep -Eq '^Uid:[[:space:]]+0([[:space:]]|$)' "/proc/$pid/status" || return 1
  grep -Eq '^State:[[:space:]]+[XZ]' "/proc/$pid/status" && return 1
  tr '\000' '\n' <"/proc/$pid/cmdline" 2>/dev/null | grep -Fxq "$MODDIR/watchdog.sh"
}

reamicro_child_alive() {
  case "$1" in ''|*[!0-9]*|0|1) return 1 ;; esac
  [ -r "/proc/$1/status" ] || return 1
  parent=$(sed -n 's/^PPid:[[:space:]]*//p' "/proc/$1/status")
  [ "$parent" = "$$" ] || return 1
  grep -Eq '^State:[[:space:]]+[XZ]' "/proc/$1/status" && return 1
  kill -0 "$1" 2>/dev/null
}

reamicro_safe_mode() {
  [ "$(getprop persist.sys.safemode)" = 1 ] || [ "$(getprop ro.sys.safemode)" = 1 ]
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
  case "$next_value" in ''|*[!0-9]*|???????????????????*) printf '0\n'; return 1 ;; esac
  while [ "${next_value#0}" != "$next_value" ]; do next_value=${next_value#0}; done
  printf '%s\n' "${next_value:-0}"
}

# Arguments: now, next task, last recovery probe, retry-not-before, recovery interval.
# Called in a subshell so temporary variables cannot change watchdog state.
reamicro_wait_seconds() {
  deadline=$(($3 + $5))
  if [ "$2" -gt 0 ] && [ "$2" -lt "$deadline" ]; then deadline=$2; fi
  if [ "$4" -gt 0 ]; then deadline=$4; fi
  delay=$((deadline - $1))
  [ "$delay" -ge 1 ] || delay=1
  [ "$delay" -le "$5" ] || delay=$5
  printf '%s\n' "$delay"
}
