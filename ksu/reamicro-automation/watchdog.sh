#!/system/bin/sh
MODDIR=${0%/*}
STATE=/data/adb/reamicro-automation
BUSYBOX=/data/adb/ksu/bin/busybox
umask 077
if [ "$1" != locked ]; then
  exec "$BUSYBOX" flock -n "$STATE/daemon.lock" /system/bin/sh "$0" locked
fi
while [ "$(getprop sys.boot_completed)" != 1 ]; do sleep 5; done
last_probe=0
while [ -d "$MODDIR" ] && [ ! -e "$MODDIR/disable" ] && [ ! -e "$MODDIR/remove" ]; do
  now=$(date +%s)
  [ "$now" -lt "$last_probe" ] && last_probe=0
  printf "%s\n" "$now" >"$STATE/heartbeat"
  next=$(cat "$STATE/next-run-at" 2>/dev/null)
  case "$next" in ''|*[!0-9]*) next=0 ;; esac
  if [ -f "$STATE/state.json" ] && { { [ "$next" -gt 0 ] && [ "$next" -le "$now" ]; } || [ "$now" -ge "$((last_probe + 900))" ]; }; then
    last_probe=$now
    if [ -w /sys/power/wake_lock ]; then echo "reamicro_tasks 300000000000" > /sys/power/wake_lock; fi
    "$BUSYBOX" timeout -s TERM 240 /system/bin/sh "$MODDIR/runner.sh" run </dev/null >/dev/null 2>&1
    result=$?
    if [ -w /sys/power/wake_unlock ]; then echo reamicro_tasks > /sys/power/wake_unlock; fi
    printf "%s runner_exit=%s\n" "$now" "$result" >>"$STATE/daemon.log"
    if [ "$result" -ne 0 ]; then sleep 60; fi
  fi
  size=$(wc -c <"$STATE/daemon.log" 2>/dev/null)
  if [ "${size:-0}" -gt 65536 ]; then
    tail -c 32768 "$STATE/daemon.log" >"$STATE/daemon.log.tmp"
    mv -f "$STATE/daemon.log.tmp" "$STATE/daemon.log"
  fi
  sleep 15
done
rm -f "$STATE/heartbeat"
