# ReaMicro Extend — R8 规则
#
# 这是一个 Xposed 模块，有三类「按名字访问」的入口，改名即坏，必须 keep：
# 1. libxposed 框架按 resources/META-INF/xposed/java_init.list 里的类名反射创建入口；
# 2. 外置代码包 .rmsource（assets/reamicro_sources/*.rmsource，独立 dex）在运行时按
#    类名解析模块的 association / settings / xposed shim 类 —— rmsource 用 kotlinc
#    对模块源码编译，dex 里只留符号引用，R8 若改名模块侧类，外置功能全部 ClassNotFound
#    （E 级日志 external feature install failed）；
# 3. AndroidManifest 声明的组件（Activity/Receiver/Provider）由 AGP 自动 keep，无需手写。

# 1) Xposed 入口
-keep class com.reamicro.fix.hook.ReaMicroLibXposedEntry { *; }

# 2) 外置代码包按名引用的模块类（名字空间整包 keep，防止漏掉新增引用点）
-keep class com.reamicro.fix.xposed.** { *; }
-keep class com.reamicro.fix.association.** { *; }
-keep class com.reamicro.fix.settings.** { *; }

# hiddenapibypass 用隐藏 API 反射，防止 R8 误删其成员
-keep class org.lsposed.hiddenapibypass.** { *; }
