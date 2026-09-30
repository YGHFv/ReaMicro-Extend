#!/system/bin/sh
# Open the stable exported Activity, never the switchable launcher aliases.
# Works independently of task execution mode and does not enable/unhide any component.
PACKAGE=com.reamicro.fix
ACTIVITY=com.reamicro.fix.ui.ModuleMainActivity
USER_ID=$(am get-current-user 2>/dev/null)
case "$USER_ID" in ''|*[!0-9]*) USER_ID=0 ;; esac
APK=$(pm path --user "$USER_ID" "$PACKAGE" 2>/dev/null)
case "$APK" in
  package:*) ;;
  *) echo "请先在当前用户安装阅微补全计划 APK"; exit 1 ;;
esac
result=$(am start --user "$USER_ID" -a android.intent.action.MAIN \
  -c de.robv.android.xposed.category.MODULE_SETTINGS \
  -f 0x34000000 -n "$PACKAGE/$ACTIVITY" 2>&1)
status=$?
printf '%s\n' "$result"
[ "$status" -eq 0 ] || exit "$status"
case "$result" in
  *Error*|*Exception*) echo "无法打开主界面，请检查 APK 是否启用"; exit 1 ;;
esac
