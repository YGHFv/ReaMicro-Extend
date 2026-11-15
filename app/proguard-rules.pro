-dontwarn io.github.libxposed.annotation.**
-adaptresourcefilecontents META-INF/xposed/java_init.list
-keep class com.reamicro.fix.hook.ReaMicroLibXposedEntry extends io.github.libxposed.api.XposedModule {
    public <init>();
    public *;
}
-keep class com.reamicro.fix.xposed.** { *; }
-keep class com.reamicro.fix.association.** { *; }
-keep class com.reamicro.fix.settings.** { *; }
-keep class org.lsposed.hiddenapibypass.** { *; }
