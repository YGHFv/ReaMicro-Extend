#!/system/bin/sh
MODDIR=${0%/*}
STATE=/data/adb/reamicro-automation
umask 077
mkdir -p "$STATE" || exit 1
chmod 700 "$STATE" || exit 1
. "$MODDIR/common.sh"
. "$MODDIR/lifecycle.sh"
export ASH_STANDALONE=1
BUSYBOX=$(find_busybox) || exit 1

if [ "$1" != --reamicro-ash ]; then
  exec "$BUSYBOX" sh "$0" --reamicro-ash "$@"
fi
shift
exec 9>"$STATE/lifecycle.lock"
reamicro_lock_lifecycle || exit 1
touch "$STATE/stop" || exit 1

stop_result=0
reamicro_stop daemon || stop_result=1
reamicro_stop wake || stop_result=1
reamicro_stop executor || stop_result=1
reamicro_release_wake_lock
if [ "$stop_result" -eq 0 ]; then
  rm -f "$STATE/daemon.pid" "$STATE/heartbeat" "$STATE/runner.pid" \
    "$STATE/wake.pid" "$STATE/wake-at" "$STATE/wake-mode" "$STATE/wake-error"
fi
exit "$stop_result"
