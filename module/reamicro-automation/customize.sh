#!/system/bin/sh

[ "$BOOTMODE" = true ] || abort "请在已启动的 Android 系统中通过 Root 管理器安装，不支持 Recovery 安装"
ui_print "阅微本地任务 Root 独立执行器（实验性）"
if [ "$KSU" = true ] || [ "$KSU" = 1 ]; then
  ui_print "- KernelSU"
elif [ "$APATCH" = true ] || [ "$APATCH" = 1 ]; then
  ui_print "- APatch"
elif [ -n "$MAGISK_VER_CODE" ]; then
  ui_print "- Magisk"
else
  abort "仅支持 KernelSU / Magisk / APatch 模块安装器"
fi
for required in lifecycle.sh stop.sh common.sh service.sh watchdog.sh runner.sh uninstall.sh action.sh; do
  [ -f "$MODPATH/$required" ] || abort "模块包不完整：$required"
done
[ "$(sed -n 's/^id=//p' "$MODPATH/module.prop")" = reamicro_automation ] || abort "模块 ID 不匹配"
. "$MODPATH/common.sh"
[ "$(sed -n 's/^versionCode=//p' "$MODPATH/module.prop")" = "$REAMICRO_MODULE_VERSION" ] || abort "脚本与模块版本不匹配"
BUSYBOX=$(find_busybox) || abort "未找到包含 flock / timeout / nohup 的 BusyBox，不能安全运行任务"
ui_print "- BusyBox: $BUSYBOX"
set_perm_recursive "$MODPATH" 0 0 0755 0644
for script in "$MODPATH"/*.sh; do
  set_perm "$script" 0 0 0755
done
touch "$MODPATH/skip_mount" || abort "无法设置纯脚本模块标记"
ui_print "- 纯脚本模块，不挂载 system，不需要 Metamodule"
ui_print "- 安装/更新由管理器替换同 ID 文件，不卸载旧版或清空任务配置"
ui_print "- 请按管理器提示重启；重启后在应用中选择 Root 独立执行"
ui_print "- 首次安装默认不接管任务；已启用的任务更新后保留"
ui_print "- Action 按钮打开主界面；卸载前请先在应用内关闭 Root增强"
