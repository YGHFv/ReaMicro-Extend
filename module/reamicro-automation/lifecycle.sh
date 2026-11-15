#!/system/bin/sh

reamicro_pid_matches() {
    case "$1" in ''|*[!0-9]*|0|1) return 1 ;; esac
    [ "$1" != "$$" ] || return 1
    [ -r "/proc/$1/cmdline" ] && [ -r "/proc/$1/status" ] || return 1
    IFS= read -r comm <"/proc/$1/comm" 2>/dev/null || return 1
    case "$comm" in sh|ash|bash|busybox|timeout|app_process|app_process32|app_process64|wake-timer) ;; *) return 1 ;; esac
    grep -Eq '^State:[[:space:]]+[XZ]' "/proc/$1/status" && return 1
    args=$(tr '\000' '\n' <"/proc/$1/cmdline" 2>/dev/null)
    case "$2" in
      daemon) printf '%s\n' "$args" | grep -Fxq "$MODDIR/watchdog.sh" ;;
      wake)
        # Match the exact module helper and private-state argument, not just comm.
        printf '%s\n' "$args" | grep -Fxq "$MODDIR/bin/wake-timer" &&
          printf '%s\n' "$args" | grep -Fxq "$STATE"
        ;;
      executor)
        if ! printf '%s\n' "$args" | grep -Fxq 'com.reamicro.fix.cloud.root.RootTaskMain'; then
          printf '%s\n' "$args" | grep -Fxq "$MODDIR/runner.sh"
        fi
        ;;
      legacy) printf '%s\n' "$args" | grep -Fxq /data/adb/service.d/reamicro-watchdog.sh ;;
      *) return 1 ;;
    esac
}

reamicro_pids() {
  for cmdline in /proc/[0-9]*/cmdline; do
    pid=${cmdline#/proc/}
    pid=${pid%/cmdline}
    reamicro_pid_matches "$pid" "$1" && printf '%s\n' "$pid"
  done
}

reamicro_survivors() {
  for pid in $stop_pids; do
    reamicro_pid_matches "$pid" "$stop_target" && printf '%s\n' "$pid"
  done
}

reamicro_stop() {
  stop_target=$1
  stop_pids=$(reamicro_pids "$stop_target")
  [ -n "$stop_pids" ] || return 0
  for signal in TERM KILL; do
    for pid in $stop_pids; do
      # Recheck identity immediately before signalling; PID files alone are not ownership.
      reamicro_pid_matches "$pid" "$stop_target" && kill -"$signal" "$pid" 2>/dev/null
    done
    tries=0
    while [ -n "$stop_pids" ] && [ "$tries" -lt 10 ]; do
      stop_pids=$(reamicro_survivors)
      [ -n "$stop_pids" ] || break
      sleep 0.1
      tries=$((tries + 1))
    done
    # A final sweep catches a child forked while its parent was stopping, without
    # repeatedly scanning every Android process during the bounded grace period.
    stop_pids=$(reamicro_pids "$stop_target")
    [ -n "$stop_pids" ] || return 0
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
