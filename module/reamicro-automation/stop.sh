#!/system/bin/sh
MODDIR=${0%/*}
STATE=/data/adb/reamicro-automation
[ ! -L "$STATE" ] && [ ! -L "$MODDIR" ] || exit 1
[ ! -e "$STATE" ] || [ -d "$STATE" ] || exit 1
umask 077
if [ -d "$STATE" ]; then
  chmod 700 "$STATE" && touch "$STATE/stop" || exit 1
fi
. "$MODDIR/common.sh"
. "$MODDIR/lifecycle.sh"
export ASH_STANDALONE=1
BUSYBOX=$(find_busybox) || { [ ! -e "$STATE" ] || exit 1; }

if [ -n "$BUSYBOX" ] && [ "$1" != --reamicro-ash ]; then
  exec "$BUSYBOX" sh "$0" --reamicro-ash "$@"
fi
if [ -d "$STATE" ]; then
  exec 9>"$STATE/lifecycle.lock"
  reamicro_lock_lifecycle && touch "$STATE/stop" || exit 1
fi

stop_result=0
reamicro_stop daemon || stop_result=1
reamicro_stop wake || stop_result=1
reamicro_stop executor || stop_result=1
reamicro_release_wake_lock
if [ "$stop_result" -eq 0 ]; then
  reamicro_clear_runtime || exit 1
fi
exit "$stop_result"
