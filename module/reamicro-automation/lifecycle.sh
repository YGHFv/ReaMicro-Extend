#!/system/bin/sh

reamicro_pids() {
  target=$1
  for cmdline in /proc/[0-9]*/cmdline; do
    [ -r "$cmdline" ] || continue
    IFS= read -r comm <"${cmdline%/cmdline}/comm" 2>/dev/null || continue
    case "$comm" in sh|ash|bash|busybox|timeout|app_process|app_process32|app_process64) ;; *) continue ;; esac
    args=$(tr '\000' '\n' <"$cmdline" 2>/dev/null)
    case "$target" in
      daemon) printf '%s\n' "$args" | grep -Fxq "$MODDIR/watchdog.sh" || continue ;;
      executor)
        if ! printf '%s\n' "$args" | grep -Fxq 'com.reamicro.fix.cloud.root.RootTaskMain'; then
          printf '%s\n' "$args" | grep -Fxq "$MODDIR/runner.sh" || continue
        fi
        ;;
      legacy) printf '%s\n' "$args" | grep -Fxq /data/adb/service.d/reamicro-watchdog.sh || continue ;;
      *) return 1 ;;
    esac
    pid=${cmdline#/proc/}
    pid=${pid%/cmdline}
    [ "$pid" = "$$" ] || printf '%s\n' "$pid"
  done
}

reamicro_stop() {
  stop_target=$1
  for signal in TERM KILL; do
    for pid in $(reamicro_pids "$stop_target"); do
      kill -"$signal" "$pid" 2>/dev/null || :
    done
    tries=0
    while [ -n "$(reamicro_pids "$stop_target")" ] && [ "$tries" -lt 10 ]; do
      sleep 0.1
      tries=$((tries + 1))
    done
    [ -n "$(reamicro_pids "$stop_target")" ] || return 0
  done
  echo "ReaMicro: could not stop $stop_target" >&2
  return 1
}

reamicro_cleanup_legacy() {
  rm -f /data/adb/service.d/reamicro-watchdog.sh || return 1
  reamicro_stop legacy
}

reamicro_lock_lifecycle() {
  lock_tries=0
  while ! "$BUSYBOX" flock -n 9; do
    [ "$lock_tries" -lt 50 ] || return 1
    sleep 0.1
    lock_tries=$((lock_tries + 1))
  done
}
