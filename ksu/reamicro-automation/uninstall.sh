#!/system/bin/sh
MODDIR=${0%/*}
STATE=/data/adb/reamicro-automation
/system/bin/sh "$MODDIR/runner.sh" disable </dev/null >/dev/null 2>&1
if [ "$?" -ne 0 ]; then
  echo "ReaMicro: stop execution before removing private state" >&2
  exit 1
fi
rm -f "$STATE/state.json" "$STATE/.state.json.tmp" "$STATE/next-run-at" "$STATE/.next-run-at.tmp"
rm -f "$STATE/heartbeat" "$STATE/daemon.log" "$STATE/daemon.log.tmp"
