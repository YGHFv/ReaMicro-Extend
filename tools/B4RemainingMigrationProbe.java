import android.graphics.Bitmap;
import android.graphics.Typeface;
import dalvik.system.PathClassLoader;
import java.io.File;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;

/** 在独立 app_process 中只操作专用 fixture，不加载 Application 或写入用户书库。 */
public final class B4RemainingMigrationProbe {
    private static Object invoke(Class<?> type, String name, Object receiver, Class<?>[] args, Object... values)
            throws Exception {
        Method method = type.getDeclaredMethod(name, args);
        method.setAccessible(true);
        return method.invoke(receiver, values);
    }

    private static Object field(Class<?> type, String name, Object receiver) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(receiver);
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new IllegalStateException(message);
    }

    private static byte[] read(InputStream stream) throws Exception {
        try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
            return output.toByteArray();
        }
    }

    private static void metrics(ClassLoader host, ClassLoader module) throws Exception {
        Class<?> typography = host.loadClass("dnb");
        Constructor<?> constructor = Arrays.stream(typography.getDeclaredConstructors())
                .filter(value -> value.getParameterCount() == 11).findFirst().get();
        Object[] args = new Object[11];
        args[10] = 32767;
        Object nativeTypography = constructor.newInstance(args);
        Object density = host.loadClass("sc2").getConstructor(float.class, float.class).newInstance(3f, 1.2f);
        Class<?> reader = module.loadClass("com.reamicro.fix.core.HostTypographyMetrics");
        Object nativeReader = reader.getConstructor(ClassLoader.class, Object.class, Object.class)
                .newInstance(host, nativeTypography, density);
        Object body = invoke(reader, "style", nativeReader, new Class<?>[]{String.class}, "BodyLarge");
        Class<?> style = body.getClass();
        Object size = invoke(style, "getFontSize", body, new Class<?>[0]);
        float sizeValue = ((Number) invoke(size.getClass(), "getValue", size, new Class<?>[0])).floatValue();
        float pixels = ((Number) invoke(style, "getFontSizePx", body, new Class<?>[0])).floatValue();
        check(sizeValue > 0 && pixels > 0, "Native typography produced no dimensions");
        Object label = invoke(reader, "style", nativeReader, new Class<?>[]{String.class}, "LabelMedium");
        check(invoke(label.getClass(), "getFontWeight", label, new Class<?>[0]) != null, "Native weight missing");
        // 独立进程没有宿主 Application，只设置本进程缩放已初始化状态，验证读取而非默认回退。
        Class<?> manager = host.loadClass("ucd");
        manager.getDeclaredField("b").setFloat(null, .8f);
        manager.getDeclaredField("c").setBoolean(null, true);
        Class<?> scale = module.loadClass("com.reamicro.fix.hook.StructureBookDetailDividerStyle");
        float factor = ((Number) invoke(scale, "scale", field(scale, "INSTANCE", null),
                new Class<?>[]{ClassLoader.class}, host)).floatValue();
        check(Math.abs(factor - .8f) < .001f, "Native UI scale ignored");
        System.out.println("METRICS_PASS bodySp=" + sizeValue + " bodyPx=" + pixels + " scale=" + factor);
    }

    private static void css(ClassLoader host, ClassLoader module, File root) throws Exception {
        check(root.isDirectory(), "Fixture root unavailable");
        Class<?> css = module.loadClass("com.reamicro.fix.epub.editor.HostStyleSheetPreviewKt");
        File first = new File(root, "first.css");
        File second = new File(root, "second.css");
        Object rows = invoke(css, "hostStyleSheetPreview", null,
                new Class<?>[]{ClassLoader.class, File.class}, host, first);
        check(rows.toString().contains("color"), "Native CSS preview has no color property");
        invoke(css, "hostStyleSheetPreview", null, new Class<?>[]{ClassLoader.class, File.class}, host, second);
        Object lazy = field(host.loadClass("zna"), "a", null);
        Object cache = invoke(lazy.getClass(), "getValue", lazy, new Class<?>[0]);
        Object holder = field(cache.getClass(), "d", cache);
        @SuppressWarnings("unchecked")
        Map<Object, Object> entries = (Map<Object, Object>) field(holder.getClass(), "a", holder);
        // attach 未必写入缓存，因此显式加入两个专用键，证明实际 native 缓存单键和队列移除。
        invoke(cache.getClass(), "e", cache, new Class<?>[]{Object.class, Object.class}, first.getAbsolutePath(), "FIRST");
        invoke(cache.getClass(), "e", cache, new Class<?>[]{Object.class, Object.class}, second.getAbsolutePath(), "SECOND");
        Object firstEntry = entries.get(first.getAbsolutePath());
        Object secondEntry = entries.get(second.getAbsolutePath());
        check(firstEntry != null && secondEntry != null, "Native cache setup failed");
        invoke(css, "invalidateHostCssPreview", null, new Class<?>[]{ClassLoader.class, File.class}, host, first);
        check(!entries.containsKey(first.getAbsolutePath()) && entries.get(second.getAbsolutePath()) == secondEntry,
                "Native CSS invalidation cleared unrelated entry");
        for (String queue : new String[]{"h", "i"}) {
            Object values = field(cache.getClass(), queue, cache);
            if (values != null) {
                check(!(Boolean) invoke(values.getClass(), "contains", values, new Class<?>[]{Object.class}, firstEntry),
                        "Native CSS invalidation left queue entry");
            }
        }
        System.out.println("CSS_PASS rows=" + rows);
    }

    private static void svg(ClassLoader host, ClassLoader module, File root) throws Exception {
        if (Typeface.DEFAULT == null) {
            // app_process 不经 Zygote 字体预加载，只初始化本测试进程的系统字体映射。
            invoke(Typeface.class, "loadPreinstalledSystemFontMap", null, new Class<?>[0]);
        }
        System.out.println("SVG_FIXTURE_TYPEFACE initialized=" + (Typeface.DEFAULT != null));
        Class<?> svg = module.loadClass("com.reamicro.fix.epub.editor.HostSvgPreviewKt");
        Bitmap image = (Bitmap) invoke(svg, "hostSvgPreview", null,
                new Class<?>[]{ClassLoader.class, File.class, int.class}, host, new File(root, "sample.svg"), 96);
        check(image.getWidth() == 96 && image.getHeight() == 48, "SVG aspect ratio changed");
        int color = image.getPixel(48, 24);
        check((color >>> 24) != 0 && ((color >>> 16) & 255) > 200, "SVG renderer did not paint the fixture");
        System.out.println("SVG_PASS width=" + image.getWidth() + " height=" + image.getHeight()
                + " center=" + Integer.toHexString(color));
        image.recycle();
    }

    private interface Test { void run() throws Exception; }

    private static void run(String name, List<String> failures, Test test) {
        try {
            test.run();
        } catch (Throwable error) {
            failures.add(name);
            System.out.println(name + "_FAIL " + error);
            error.printStackTrace(System.out);
        }
    }

    public static void main(String[] args) throws Exception {
        check(args.length == 3, "Expected host APK, module APK and isolated fixture root");
        File root = new File(args[2]).getCanonicalFile();
        check(root.getPath().startsWith("/data/local/tmp/reamicro-remaining-"), "Unsafe fixture root");
        ClassLoader parent = B4RemainingMigrationProbe.class.getClassLoader();
        ClassLoader host = new PathClassLoader(args[0], parent);
        ClassLoader module = new PathClassLoader(args[1], parent);
        String profile;
        try (ZipFile zip = new ZipFile(args[1])) {
            profile = new String(read(zip.getInputStream(zip.getEntry("assets/host-profiles/2.3.3-b4.profile"))),
                    StandardCharsets.UTF_8);
        }
        Class<?> parser = module.loadClass("com.reamicro.fix.core.HostProfile");
        Object parsed = invoke(parser, "parse", field(parser, "INSTANCE", null),
                new Class<?>[]{String.class, String.class}, profile, "isolated runtime probe");
        Class<?> symbols = module.loadClass("com.reamicro.fix.core.HostSymbols");
        Field active = symbols.getDeclaredField("active");
        active.setAccessible(true);
        active.set(null, parsed);
        List<String> failures = new ArrayList<>();
        run("METRICS", failures, () -> metrics(host, module));
        run("CSS", failures, () -> css(host, module, root));
        run("SVG", failures, () -> svg(host, module, root));
        check(failures.isEmpty(), "Native fixture tests failed: " + failures);
        System.out.println("REMAINING_NATIVE_PROBE_PASS");
    }
}
