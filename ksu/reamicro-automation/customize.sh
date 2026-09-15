#!/system/bin/sh
ui_print "ReaMicro 本地自动任务独立执行器（实验性）"
[ "$KSU" = "true" ] || [ "$KSU" = "1" ] || abort "请通过 KernelSU 管理器安装"
[ -x /data/adb/ksu/bin/busybox ] || abort "未找到 KernelSU BusyBox"
set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/service.sh" 0 0 0755
set_perm "$MODPATH/watchdog.sh" 0 0 0755
set_perm "$MODPATH/runner.sh" 0 0 0755
set_perm "$MODPATH/uninstall.sh" 0 0 0755
ui_print "重启后，在模块配置中授权 root 并选择 KSU 执行。"
ui_print "默认不接管任务；卸载前请先切回 Android 模块执行。"
