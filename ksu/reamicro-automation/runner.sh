#!/system/bin/sh
MODDIR=${0%/*}
case "$1" in
  disable|snapshot|ack) ;;
  *) [ -e "$MODDIR/disable" ] || [ -e "$MODDIR/remove" ] && exit 1 ;;
esac
APK=$(pm path --user 0 com.reamicro.fix 2>/dev/null | head -n 1)
APK=${APK#package:}
[ -f "$APK" ] || exit 1
export CLASSPATH="$APK"
exec /system/bin/app_process /system/bin com.reamicro.fix.cloud.ksu.KsuTaskMain "$1"
