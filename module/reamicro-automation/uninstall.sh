#!/system/bin/sh
MODDIR=${0%/*}
STATE=/data/adb/reamicro-automation
umask 077
. "$MODDIR/common.sh"
. "$MODDIR/lifecycle.sh"

if [ -d "$STATE" ]; then
  chmod 700 "$STATE" || exit 1
  touch "$STATE/stop" || exit 1
  BUSYBOX=$(find_busybox)
  if [ -n "$BUSYBOX" ]; then
    if [ "$1" != --reamicro-ash ]; then
      exec "$BUSYBOX" sh "$0" --reamicro-ash "$@"
    fi
    shift
    exec 9>"$STATE/lifecycle.lock"
    reamicro_lock_lifecycle || exit 1
  fi
fi
stop_result=0
reamicro_stop daemon || stop_result=1
reamicro_stop wake || stop_result=1
reamicro_stop executor || stop_result=1
reamicro_cleanup_legacy || stop_result=1
reamicro_release_wake_lock

[ "$stop_result" -eq 0 ] || exit "$stop_result"
rm -rf "$STATE" || exit 1
echo "ReaMicro: scheduler, legacy wake script and private credentials removed"
