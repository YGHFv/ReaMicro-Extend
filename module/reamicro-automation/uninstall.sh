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
reamicro_stop daemon || exit 1
reamicro_stop executor || exit 1
reamicro_cleanup_legacy || exit 1

rm -rf "$STATE" || exit 1
echo "ReaMicro: scheduler, legacy wake script and private credentials removed"
