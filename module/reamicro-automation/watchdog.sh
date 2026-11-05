#!/system/bin/sh
MODDIR=${0%/*}
STATE=/data/adb/reamicro-automation
umask 077
if [ -e "$MODDIR/disable" ] || [ -e "$MODDIR/remove" ] || [ -e "$STATE/stop" ]; then exit 0; fi
[ -f "$STATE/state.json" ] || exit 0
chmod 700 "$STATE" || exit 1
. "$MODDIR/common.sh"
BUSYBOX=$(find_busybox) || exit 1
export ASH_STANDALONE=1

if [ "$1" != --reamicro-ash ]; then
  exec "$BUSYBOX" sh "$0" --reamicro-ash "$@"
fi
shift
exec 8>"$STATE/daemon.lock"
"$BUSYBOX" flock -n 8 || exit 0
printf '%s\n' "$$" >"$STATE/daemon.pid"
CHILD_PID=
PULSE_PID=
child_alive() {
  [ -n "$CHILD_PID" ] && [ -r "/proc/$CHILD_PID/status" ] || return 1
  parent=$(sed -n 's/^PPid:[[:space:]]*//p' "/proc/$CHILD_PID/status")
  [ "$parent" = "$$" ] || return 1
  grep -Eq '^State:[[:space:]]+[XZ]' "/proc/$CHILD_PID/status" && return 1
  kill -0 "$CHILD_PID" 2>/dev/null
}
cleanup() {

  if child_alive; then
    kill "$CHILD_PID" 2>/dev/null
    cleanup_tries=0
    while child_alive && [ "$cleanup_tries" -lt 5 ]; do
      sleep 0.1 8>&-
      cleanup_tries=$((cleanup_tries + 1))
    done
    child_alive && kill -KILL "$CHILD_PID" 2>/dev/null
    # 不等待仍可能卡在内核里的子进程；停止路径继续清理执行器和唤醒锁。
    child_alive || wait "$CHILD_PID" 2>/dev/null
  fi
  [ -n "$PULSE_PID" ] && kill "$PULSE_PID" 2>/dev/null
  reamicro_release_wake_lock
  rm -f "$STATE/runner.pid" "$STATE/wake.pid" "$STATE/wake-at" "$STATE/wake-mode"
  if [ "$(cat "$STATE/daemon.pid" 2>/dev/null)" = "$$" ]; then
    rm -f "$STATE/daemon.pid" "$STATE/heartbeat"
  fi
}
trap cleanup EXIT
trap 'exit 0' HUP INT TERM
module_active() {
  [ -d "$MODDIR" ] && [ -f "$STATE/state.json" ] && [ ! -e "$MODDIR/disable" ] && [ ! -e "$MODDIR/remove" ] && [ ! -e "$STATE/stop" ]
}
pause() {
  sleep "$1" 8>&- &
  CHILD_PID=$!
  wait "$CHILD_PID"
  CHILD_PID=
}
wake_pause() {
  previous_mode=$(cat "$STATE/wake-mode" 2>/dev/null)
  # The helper watches/rechecks config and next-run-at before releasing the lock.
  "$MODDIR/bin/wake-timer" "$1" "$STATE" "$MODDIR" "$2" "$$" "$3" 8>&- 2>"$STATE/wake-error" &
  CHILD_PID=$!
  wait "$CHILD_PID"
  wake_result=$?
  CHILD_PID=
  rm -f "$STATE/wake.pid" "$STATE/wake-at"
  if [ "$wake_result" -ne 0 ]; then
    printf 'unavailable\n' >"$STATE/wake-mode"
    if [ "$previous_mode" != unavailable ]; then
      printf '%s wake_unavailable exit=%s\n' "$(date +%s)" "$wake_result" >>"$STATE/daemon.log"
      cat "$STATE/wake-error" >>"$STATE/daemon.log"
    fi
    # Keep the compatibility polling visible; it cannot wake a suspended CPU.
    reamicro_release_wake_lock
    module_active && pause 15
  fi
}
while [ "$(getprop sys.boot_completed)" != 1 ]; do
  module_active || exit 0
  pause 5
done
last_probe=0
last_now=0
retry_after=0
while module_active; do
  reamicro_acquire_wake_lock || :
  now=$(date +%s)
  if [ "$now" -lt "$last_now" ]; then last_probe=0; retry_after=0; fi
  last_now=$now
  printf '%s\n' "$now" >"$STATE/heartbeat"
  next=$(reamicro_read_next)
  if [ -f "$STATE/state.json" ] && [ "$now" -ge "$retry_after" ] &&
      { { [ "$next" -gt 0 ] && [ "$next" -le "$now" ]; } || [ "$now" -ge "$((last_probe + 900))" ]; }; then
    last_probe=$now
    "$BUSYBOX" timeout -k 15 -s TERM 240 "$BUSYBOX" sh "$MODDIR/runner.sh" run 8>&- </dev/null >/dev/null 2>&1 &
    CHILD_PID=$!
    printf '%s\n' "$CHILD_PID" >"$STATE/runner.pid"

    while kill -0 "$CHILD_PID" 2>/dev/null; do
      printf '%s\n' "$(date +%s)" >"$STATE/heartbeat"
      module_active || { kill "$CHILD_PID" 2>/dev/null; break; }
      sleep 2 8>&- &
      PULSE_PID=$!
      wait "$PULSE_PID"
      PULSE_PID=
    done
    wait "$CHILD_PID"
    result=$?
    CHILD_PID=
    rm -f "$STATE/runner.pid"
    printf '%s runner_exit=%s\n' "$now" "$result" >>"$STATE/daemon.log"
    module_active || break
    retry_after=0
    if [ "$result" -ne 0 ]; then retry_after=$(($(date +%s) + 60)); fi
  fi
  size=$(wc -c <"$STATE/daemon.log" 2>/dev/null)
  if [ "${size:-0}" -gt 65536 ]; then
    tail -c 32768 "$STATE/daemon.log" >"$STATE/daemon.log.tmp" && mv -f "$STATE/daemon.log.tmp" "$STATE/daemon.log"
  fi
  # Execution publishes next-run-at atomically; read it again before arming.
  now=$(date +%s)
  next=$(reamicro_read_next)
  delay=$(reamicro_wait_seconds "$now" "$next" "$last_probe" "$retry_after")
  wake_pause "$delay" "$next" "$((now + delay))"
done
