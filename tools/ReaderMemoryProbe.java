import android.os.Handler;
import android.os.Looper;
import java.io.File;
import java.lang.ref.Reference;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** 独立 app_process 验证缓存和真实 Handler，不安装 Hook、不读写用户书籍。 */
public final class ReaderMemoryProbe {
    static ClassLoader module;
    static Class<?> function;
    static Object factory(java.util.concurrent.Callable<Object> block) {
        return Proxy.newProxyInstance(module, new Class<?>[]{function}, (proxy, method, args) -> {
            switch (method.getName()) {
                case "invoke": return block.call();
                case "hashCode": return System.identityHashCode(proxy);
                case "equals": return proxy == args[0];
                case "toString": return "ReaderMemoryProbeFactory";
                default: throw new UnsupportedOperationException(method.toString());
            }
        });
    }
    static Object call(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        return Rc3ProtocolProbe.call(target, name, types, args);
    }
    static void check(boolean value, String message) { Rc3ProtocolProbe.check(value, message); }
    static void font() throws Exception {
        Object settings = module.loadClass("com.reamicro.fix.settings.XposedModuleSettings").getConstructor().newInstance();
        Class<?> hookClass = module.loadClass("com.reamicro.fix.hook.ReaderFontCompletionHook");
        Object hook = hookClass.getConstructor(ClassLoader.class, function, settings.getClass())
            .newInstance(Rc3ProtocolProbe.host, factory(() -> null), settings);
        File font = Arrays.stream(Objects.requireNonNull(new File("/system/fonts").listFiles()))
            .filter(f -> f.getName().endsWith(".ttf")).findFirst().get();
        Object family = call(hook, "resolveFontFamily", new Class<?>[]{String.class}, font.getAbsolutePath());
        check(family != null, "Native font family missing");
        check(call(hook, "resolveFontFamily", new Class<?>[]{String.class}, font.getAbsolutePath()) == family,
            "Font cache miss");
        Object cache = Rc3ProtocolProbe.get(hook, "fontFamilyCache");
        Method load = cache.getClass().getDeclaredMethod("getOrLoad", String.class, function);
        load.setAccessible(true);
        for (int i = 0; i < 10000; i++) load.invoke(cache, "version-" + i, factory(Object::new));
        check((Integer) call(cache, "size", new Class<?>[0]) == 8, "Unbounded font cache");
        call(hook, "releaseMemory", new Class<?>[]{String.class}, "isolated memory probe");
        check((Integer) call(cache, "size", new Class<?>[0]) == 0, "Font cleanup missed cache");
        System.out.println("MEMORY_NATIVE_FONT_CACHE_PASS");
    }
    static void draw() throws Exception {
        Class<?> type = module.loadClass("com.reamicro.fix.hook.ReaderTextDrawCache");
        Object cache = type.getConstructor().newInstance();
        Class<?>[] args = {Object.class, Object.class, String.class, function};
        Object node = new Object(), layout = new Object();
        AtomicInteger scans = new AtomicInteger();
        Object empty = factory(() -> { scans.incrementAndGet(); return null; });
        for (int i = 0; i < 10000; i++) {
            check(call(cache, "getOrBuild", args, node, layout, "empty", empty) == null, "Empty draw allocated state");
        }
        check(scans.get() == 1, "Empty draw rescanned each frame");
        Class<?> state = module.loadClass("com.reamicro.fix.hook.ReaderDialogueHighlightHook$NinePatchDrawState");
        for (Field field : state.getDeclaredFields()) {
            check(!field.getName().toLowerCase(Locale.ROOT).contains("layout"), "Draw state retains layout");
        }
        Constructor<?> constructor = state.getDeclaredConstructor(List.class, String.class);
        constructor.setAccessible(true);
        Object painted = constructor.newInstance(Collections.emptyList(), "positive");
        check(call(cache, "getOrBuild", args, node, layout, "positive", factory(() -> painted)) == painted,
            "Token change failed to rebuild");
        call(cache, "clear", new Class<?>[0]);
        check(((List<?>) call(cache, "snapshot", new Class<?>[0])).isEmpty(), "Draw cache cleanup failed");
        System.out.println("MEMORY_TEXT_DRAW_CACHE_PASS");
    }
    static void handlers() throws Exception {
        Class<?> type = module.loadClass("com.reamicro.fix.reload.WeakResourceRegistry");
        Object registry = type.getConstructor().newInstance();
        for (int i = 0; i < 10000; i++) {
            Object value = new Object();
            Reference<?> reference = (Reference<?>) call(registry, "add", new Class<?>[]{Object.class}, value);
            reference.clear(); reference.enqueue();
        }
        check((Integer) call(registry, "size", new Class<?>[0]) == 0, "Dead registrations retained");
        Class<?> resources = module.loadClass("com.reamicro.fix.reload.ModuleResources");
        Object instance = Rc3ProtocolProbe.singleton(resources);
        resources.getMethod("enable").invoke(instance);
        Runnable callback = () -> { throw new AssertionError("Retired callback ran"); };
        List<Handler> live = new ArrayList<>();
        for (int i = 0; i < 256; i++) {
            Handler handler = (Handler) resources.getMethod("handler", Looper.class).invoke(instance, Looper.getMainLooper());
            handler.postDelayed(callback, 60000);
            live.add(handler);
        }
        Method begin = Arrays.stream(resources.getDeclaredMethods()).filter(m -> m.getName().startsWith("begin$")).findFirst().get();
        Method retire = Arrays.stream(resources.getDeclaredMethods()).filter(m -> m.getName().startsWith("retire$")).findFirst().get();
        begin.setAccessible(true); retire.setAccessible(true);
        check(begin.invoke(instance) == null, "Unexpected active resource blocker");
        retire.invoke(instance);
        for (Handler handler : live) check(!handler.hasCallbacks(callback), "Live Handler was not cleaned");
        System.out.println("MEMORY_HANDLER_RETIRE_PASS");
    }
    public static void main(String[] args) throws Exception {
        Looper.prepareMainLooper();
        Rc3ProtocolProbe.setup(args);
        module = Rc3ProtocolProbe.module;
        function = module.loadClass("kotlin.jvm.functions.Function0");
        int failures = 0;
        try { font(); } catch (Throwable error) { failures++; error.printStackTrace(); }
        try { draw(); } catch (Throwable error) { failures++; error.printStackTrace(); }
        try { handlers(); } catch (Throwable error) { failures++; error.printStackTrace(); }
        System.out.println("MEMORY_FAILURES=" + failures);
        System.exit(failures == 0 ? 0 : 1);
    }
}
