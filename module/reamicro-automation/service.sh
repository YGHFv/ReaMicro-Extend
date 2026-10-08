#!/system/bin/sh
MODDIR=${0%/*}
STATE=/data/adb/reamicro-automation
[ -e "$MODDIR/disable" ] || [ -e "$MODDIR/remove" ] && exit 0

[ -f "$STATE/state.json" ] || exit 0
umask 077
chmod 700 "$STATE" || exit 1
. "$MODDIR/common.sh"
. "$MODDIR/lifecycle.sh"
BUSYBOX=$(find_busybox) || {
  echo "ReaMicro: compatible BusyBox unavailable" >&2
  exit 1
}
export ASH_STANDALONE=1

if [ "$1" != --reamicro-ash ]; then
  exec "$BUSYBOX" sh "$0" --reamicro-ash "$@"
fi
shift
exec 9>"$STATE/lifecycle.lock"
reamicro_lock_lifecycle || exit 1
[ -e "$MODDIR/disable" ] || [ -e "$MODDIR/remove" ] && exit 0

if [ "$1" = start ]; then rm -f "$STATE/stop" || exit 1; fi
[ -e "$STATE/stop" ] && exit 0

reamicro_daemon_alive && exit 0

"$BUSYBOX" nohup "$BUSYBOX" sh "$MODDIR/watchdog.sh" 9>&- </dev/null >>"$STATE/daemon.log" 2>&1 &
tries=0
while [ "$tries" -lt 30 ]; do
  reamicro_daemon_alive && exit 0
  sleep 0.2
  tries=$((tries + 1))
done
echo "ReaMicro: scheduler did not acknowledge startup" >&2
exit 1
