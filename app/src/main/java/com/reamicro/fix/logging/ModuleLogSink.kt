package com.reamicro.fix.logging

/**
 * 模块日志出口。
 *
 * 存在的意义是把「往 libxposed 框架打日志」这件事和 [com.reamicro.fix.xposed.XposedBridge] 解耦：
 * `libxposed` 是 `compileOnly` 依赖，只存在于被注入的宿主进程里，模块**自己的进程**（接收器、
 * 闹钟唤醒、主界面）加载不到 `XposedInterface`。只要 `XposedBridge.log` 的字节码里出现对该类的
 * 引用，模块进程一执行日志就 `NoClassDefFoundError` 崩溃（实测：打开阅微时模块进程崩溃）。
 *
 * 所以 `XposedBridge` 只持有这个**自有类型**的出口；真正引用 `XposedInterface` 的实现类只在
 * 被注入的进程里被实例化、被加载。
 */
internal interface ModuleLogSink {
    fun log(priority: Int, tag: String, text: String, throwable: Throwable?)
}
