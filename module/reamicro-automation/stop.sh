#!/system/bin/sh
MODDIR=${0%/*}
STATE=/data/adb/reamicro-automation
umask 077
mkdir -p "$STATE" || exit 1
chmod 700 "$STATE" || exit 1
. "$MODDIR/common.sh"
. "$MODDIR/lifecycle.sh"
BUSYBOX=$(find_busybox) || exit 1

if [ "$1" != --reamicro-ash ]; then
  exec "$BUSYBOX" sh "$0" --reamicro-ash "$@"
fi
shift
exec 9>"$STATE/lifecycle.lock"
reamicro_lock_lifecycle || exit 1
touch "$STATE/stop" || exit 1

reamicro_stop daemon || exit 1
rm -f "$STATE/daemon.pid" "$STATE/heartbeat"
