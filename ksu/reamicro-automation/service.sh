#!/system/bin/sh
MODDIR=${0%/*}
STATE=/data/adb/reamicro-automation
[ -e "$MODDIR/disable" ] || [ -e "$MODDIR/remove" ] && exit 0
umask 077
mkdir -p "$STATE" || exit 1
chmod 700 "$STATE"
/data/adb/ksu/bin/busybox nohup /system/bin/sh "$MODDIR/watchdog.sh" </dev/null >>"$STATE/daemon.log" 2>&1 &
