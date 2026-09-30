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
# Hold the lock in this ash process, not in a flock wrapper inherited by timeout monitors.
# As with service.sh, Android mksh must not open the descriptor before entering BusyBox ash.
if [ "$1" != --reamicro-ash ]; then
  exec "$BUSYBOX" sh "$0" --reamicro-ash "$@"
fi
shift
exec 8>"$STATE/daemon.lock"
"$BUSYBOX" flock -n 8 || exit 0
printf '%s\n' "$$" >"$STATE/daemon.pid"
CHILD_PID=
PULSE_PID=
release_wake_lock() {
  if [ -w /sys/power/wake_unlock ]; then echo reamicro_tasks > /sys/power/wake_unlock; fi
}
cleanup() {
  # CHILD_PID is a child we spawned, and not an arbitrary PID read from disk.
  if [ -n "$CHILD_PID" ] && [ -r "/proc/$CHILD_PID/status" ]; then
    parent=$(sed -n 's/^PPid:[[:space:]]*//p' "/proc/$CHILD_PID/status")
    [ "$parent" = "$$" ] && kill "$CHILD_PID" 2>/dev/null
  fi
  [ -n "$PULSE_PID" ] && kill "$PULSE_PID" 2>/dev/null
  release_wake_lock
  rm -f "$STATE/runner.pid"
  if [ "$(cat "$STATE/daemon.pid" 2>/dev/null)" = "$$" ]; then
    rm -f "$STATE/daemon.pid" "$STATE/heartbeat"
  fi
}
trap cleanup EXIT
trap 'exit 0' HUP INT TERM
module_active() {
  [ -d "$MODDIR" ] && [ ! -e "$MODDIR/disable" ] && [ ! -e "$MODDIR/remove" ] && [ ! -e "$STATE/stop" ]
}
pause() {
  sleep "$1" 8>&- &
  CHILD_PID=$!
  wait "$CHILD_PID"
  CHILD_PID=
}
while [ "$(getprop sys.boot_completed)" != 1 ]; do
  module_active || exit 0
  pause 5
done
last_probe=0
while module_active; do
  now=$(date +%s)
  [ "$now" -lt "$last_probe" ] && last_probe=0
  printf '%s\n' "$now" >"$STATE/heartbeat"
  next=$(cat "$STATE/next-run-at" 2>/dev/null)
  case "$next" in ''|*[!0-9]*) next=0 ;; esac
  if [ -f "$STATE/state.json" ] && { { [ "$next" -gt 0 ] && [ "$next" -le "$now" ]; } || [ "$now" -ge "$((last_probe + 900))" ]; }; then
    last_probe=$now
    if [ -w /sys/power/wake_lock ]; then echo "reamicro_tasks 300000000000" > /sys/power/wake_lock; fi
    "$BUSYBOX" timeout -k 15 -s TERM 240 "$BUSYBOX" sh "$MODDIR/runner.sh" run 8>&- </dev/null >/dev/null 2>&1 &
    CHILD_PID=$!
    printf '%s\n' "$CHILD_PID" >"$STATE/runner.pid"
    # Keep heartbeat fresh while a network task takes minutes. sleep is interruptible and
    # does not hold a permanent wake lock. there is no app-side alarm fallback.
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
    release_wake_lock
    printf '%s runner_exit=%s\n' "$now" "$result" >>"$STATE/daemon.log"
    module_active || break
    if [ "$result" -ne 0 ]; then pause 60; fi
  fi
  size=$(wc -c <"$STATE/daemon.log" 2>/dev/null)
  if [ "${size:-0}" -gt 65536 ]; then
    tail -c 32768 "$STATE/daemon.log" >"$STATE/daemon.log.tmp" && mv -f "$STATE/daemon.log.tmp" "$STATE/daemon.log"
  fi
  pause 15
done
