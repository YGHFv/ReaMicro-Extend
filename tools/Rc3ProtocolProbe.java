import android.content.pm.ApplicationInfo;
import dalvik.system.PathClassLoader;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipFile;

/** 独立进程验收协程、分页和 HTTP 请求构造，不连接用户网盘、不写用户数据。 */
public final class Rc3ProtocolProbe {
    static final String PROFILE = System.getProperty("reamicro.hostProfile", "2.3.3-rc3");
    static ClassLoader host, module;
    static Class<?> symbols;
    static Object singleton(Class<?> cls) throws Exception { return cls.getField("INSTANCE").get(null); }
    static Class<?> nativeClass(String logical) throws Exception {
        String actual = (String) symbols.getMethod("resolveClass", String.class).invoke(null, logical);
        return host.loadClass(actual);
    }
    static Object call(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(target, args);
    }
    static Object get(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
    static void check(boolean value, String message) {
        if (!value) throw new IllegalStateException(message);
    }
    static void setup(String[] args) throws Exception {
        ClassLoader parent = Rc3ProtocolProbe.class.getClassLoader();
        host = new PathClassLoader(args[0], parent);
        module = new PathClassLoader(args[1], parent);
        Class<?> context = module.loadClass("com.reamicro.fix.core.InjectedModuleContext");
        ApplicationInfo info = new ApplicationInfo();
        info.sourceDir = args[1]; info.publicSourceDir = args[1];
        context.getMethod("configure", ApplicationInfo.class).invoke(singleton(context), info);
        String profile;
        try (ZipFile zip = new ZipFile(args[1]);
             InputStream input = zip.getInputStream(zip.getEntry("assets/host-profiles/" + PROFILE + ".profile"));
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] bytes = new byte[8192]; int count;
            while ((count=input.read(bytes))>=0) output.write(bytes,0,count);
            profile = new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
        Class<?> parser = module.loadClass("com.reamicro.fix.core.HostProfile");
        Object parsed = parser.getMethod("parse",String.class,String.class).invoke(singleton(parser),profile,"protocol");
        symbols = module.loadClass("com.reamicro.fix.core.HostSymbols");
        Field active = symbols.getDeclaredField("active"); active.setAccessible(true); active.set(null,parsed);
    }
    static void coroutine() throws Exception {
        Class<?> protocol = module.loadClass("com.reamicro.fix.core.HostCoroutines");
        protocol.getMethod("verifyProtocol",ClassLoader.class).invoke(singleton(protocol),host);
        System.out.println("COROUTINE_PASS");
    }
    static void paging() throws Exception {
        Class<?> protocol = module.loadClass("com.reamicro.fix.core.B2PagingData");
        Object instance = protocol.getConstructor(ClassLoader.class).newInstance(host);
        Object value = call(instance,"from",new Class<?>[]{List.class},Arrays.asList("one","two"));
        check(nativeClass("ReaMicroHost.PagingData").isInstance(value),"PagingData mismatch");
        Object flow = call(instance,"singleFlow",new Class<?>[]{Object.class},"marker");
        Method collect = Arrays.stream(flow.getClass().getDeclaredMethods()).filter(m->m.getParameterCount()==2).findFirst().get();
        Class<?> collector = collect.getParameterTypes()[0];
        List<Object> values = new ArrayList<>();
        Object sink = Proxy.newProxyInstance(host,new Class<?>[]{collector},(proxy,method,args)->{
            if (args != null && args.length==2) {
                values.add(args[0]);
                return nativeClass("kotlin.Unit").getDeclaredField("a").get(null);
            }
            return null;
        });
        Class<?> coroutines = module.loadClass("com.reamicro.fix.core.HostCoroutines");
        coroutines.getMethod("invoke",ClassLoader.class,Method.class,Object.class,Object[].class)
            .invoke(singleton(coroutines),host,collect,flow,new Object[]{sink});
        check(values.equals(Collections.singletonList("marker")),"SingleFlow wrong branch "+values);
        System.out.println("PAGING_PASS");
    }
    static void http(String root) throws Exception {
        Class<?> scope = module.loadClass("com.reamicro.fix.cloud.webdav.CleartextScope");
        Object clear = Proxy.newProxyInstance(module,new Class<?>[]{scope},(proxy,method,args)->null);
        Class<?> protocol = module.loadClass("com.reamicro.fix.cloud.webdav.B2HostHttp");
        Constructor<?> ctor = protocol.getDeclaredConstructor(ClassLoader.class,scope);ctor.setAccessible(true);
        Object instance = ctor.newInstance(host,clear);
        Object body = call(instance,"stringBody",new Class<?>[]{String.class,String.class},"marker","text/plain");
        check(nativeClass("ReaMicroHost.HttpRequestBody").isInstance(body),"String body mismatch");
        Class<?> abi = module.loadClass("com.reamicro.fix.core.HostNativeAbi");
        Object http = abi.getMethod("http",String.class).invoke(singleton(abi),PROFILE);
        byte branch = (Byte)call(http,"getBuilderBranch",new Class<?>[0]);
        Object builder = nativeClass("ReaMicroHost.HttpRequestBuilder").getDeclaredConstructor(byte.class).newInstance(branch);
        Class<?> rawAbi = module.loadClass("com.reamicro.fix.core.HostRawAbi");
        Field verb = (Field) rawAbi.getMethod("field",Class.class,String.class,boolean.class)
            .invoke(singleton(rawAbi),builder.getClass(),"c",false);
        check("GET".equals(verb.get(builder)),"Request builder branch mismatch");
        Field headersField = (Field) rawAbi.getMethod("field",Class.class,String.class,boolean.class)
            .invoke(singleton(rawAbi),builder.getClass(),"d",false);
        check(nativeClass("ReaMicroHost.HttpHeadersBuilder").isInstance(headersField.get(builder)),
            "Request headers receiver mismatch");
        String url = (String)call(http,"getUrl",new Class<?>[0]);
        call(builder,url,new Class<?>[]{String.class},"https://example.invalid/probe");
        String header = (String)call(http,"getHeader",new Class<?>[0]);
        call(builder,header,new Class<?>[]{String.class,String.class},"X-Probe","RC3");
        String method = (String)call(http,"getMethod",new Class<?>[0]);
        call(builder,method,new Class<?>[]{String.class,nativeClass("ReaMicroHost.HttpRequestBody")},"POST",body);
        nativeClass("ReaMicroHost.HttpRequest").getDeclaredConstructor(builder.getClass()).newInstance(builder);
        File fixture = new File(root,"stream.bin");
        try (FileOutputStream out = new FileOutputStream(fixture)) { out.write("stream-probe".getBytes(StandardCharsets.UTF_8)); }
        Object fileBody = call(instance,"fileBody",new Class<?>[]{File.class,String.class},fixture,"application/octet-stream");
        ByteArrayOutputStream written = new ByteArrayOutputStream();
        Class<?> sink = nativeClass("ReaMicroHost.HttpBufferedSink");
        Object output = Proxy.newProxyInstance(host,new Class<?>[]{sink},(proxy,m,a)->{
            if (m.getName().equals("write")) { written.write((byte[])a[0],(Integer)a[1],(Integer)a[2]); return proxy; }
            return null;
        });
        call(fileBody,"e",new Class<?>[]{sink},output);
        check(written.toString("UTF-8").equals("stream-probe"),"HTTP stream mismatch");
        System.out.println("HTTP_CONSTRUCTION_STREAM_PASS");
    }
    static void uiPrimitives() throws Exception {
        Class<?> ui = module.loadClass("com.reamicro.fix.core.B2NativeUi");
        Object instance = ui.getConstructor(ClassLoader.class).newInstance(host);
        Object modifier = call(instance, "modifier", new Class<?>[0]);
        for (String method : new String[]{"top", "start", "center", "centerHorizontally", "centerVertically"}) {
            check(call(instance, method, new Class<?>[0]) != null, "Missing UI primitive " + method);
        }
        check(call(instance, "dp", new Class<?>[]{int.class}, 16) instanceof Float, "UI dp mismatch");
        check(nativeClass("ReaMicroHost.SpacedArrangement").isInstance(
            call(instance, "spacedBy", new Class<?>[]{float.class}, 16f)), "UI spacing mismatch");
        call(instance, "fillWidth", new Class<?>[]{Object.class}, modifier);
        call(instance, "fillSize", new Class<?>[]{Object.class}, modifier);
        call(instance, "height", new Class<?>[]{Object.class, float.class}, modifier, 24f);
        call(instance, "width", new Class<?>[]{Object.class, float.class}, modifier, 24f);
        call(instance, "padding", new Class<?>[]{Object.class, float.class, float.class, float.class, float.class},
            modifier, 1f, 2f, 3f, 4f);
        call(instance, "background", new Class<?>[]{Object.class, long.class}, modifier, 0xFFFFFFFF00000000L);
        System.out.println("UI_PRIMITIVES_PASS");
    }
    static void searchAndDiscoverContracts() throws Exception {
        Class<?> abi = module.loadClass("com.reamicro.fix.core.HostNativeAbi");
        byte branch = (Byte) abi.getMethod("homeSearchListBranch", String.class)
            .invoke(singleton(abi), PROFILE);
        Class<?> callbackType = nativeClass("ReaMicroHost.HomeSearchList");
        Map<Integer, Object> results = new LinkedHashMap<>();
        results.put(1, Collections.singletonList("native"));
        results.put(8, Collections.singletonList("cloud"));
        results.put(9, Collections.singletonList("local"));
        results.put(10, Collections.singletonList("source"));
        Object callback = callbackType.getDeclaredConstructor(Object.class, Object.class, Object.class, Object.class, byte.class)
            .newInstance(Collections.emptyList(), new Object(), results, new Object(), branch);
        Class<?> copier = module.loadClass("com.reamicro.fix.core.HostHomeSearchList");
        Object copy = copier.getConstructor(Class.class, byte.class).newInstance(callbackType, branch);
        Object filtered = call(copy, "nativeOnly", new Class<?>[]{Object.class, Set.class}, callback,
            new HashSet<>(Arrays.asList(8, 9, 10)));
        check(get(callback, "d") == results && results.size() == 4, "Search restart snapshot mutated");
        check(((Map<?, ?>) get(filtered, "d")).keySet().equals(Collections.singleton(1)), "Extension results not filtered");
        check(get(filtered, "a").equals(branch), "Search callback branch mismatch");
        Class<?> androidViews = module.loadClass("com.reamicro.fix.core.HostAndroidViews");
        Method render = (Method) call(androidViews.getConstructor(ClassLoader.class).newInstance(host), "getRender", new Class<?>[0]);
        check(render.getParameterCount() == 8, "AndroidView reusable signature mismatch");
        Class<?> reflection = module.loadClass("com.reamicro.fix.core.HostReflectionKt");
        List<?> community = (List<?>) reflection.getMethod("hostMethodsNamed", Class.class, String.class)
            .invoke(null, nativeClass("app.zhendong.reamicro.ui.profile.components.CommunityKt"), "Community");
        check(community.size() == 1, "Community method ambiguous");
        List<?> backgrounds = (List<?>) reflection.getMethod("hostMethodsNamed", Class.class, String.class)
            .invoke(null, nativeClass("androidx.compose.foundation.BackgroundKt"), "background-bw27NRU");
        check(backgrounds.size() == 1 && ((Method) backgrounds.get(0)).getParameterCount() == 3,
            "Discover divider background method ambiguous");
        if (PROFILE.equals("2.3.4-b1")) {
            check(branch == 9, "Search branch must match oyd.i construction");
            check(render.getDeclaringClass().getName().equals("ii5"), "AndroidView owner not relocated");
            check(((Method) community.get(0)).getDeclaringClass().getName().equals("uce"), "Community owner not relocated");
        }
        System.out.println("SEARCH_DISCOVER_CONTRACTS_PASS");
    }
    static void storageContracts() throws Exception {
        Class<?> raw = module.loadClass("com.reamicro.fix.core.HostRawAbi");
        Method resolve = raw.getMethod("method", Class.class, String.class, Class[].class, boolean.class);
        Class<?> repository = nativeClass("app.zhendong.reamicro.repository.storage.CloudStorageRepository");
        Class<?> continuation = nativeClass("ReaMicroHost.SuspendContinuation");
        Class<?> string = String.class;
        String[] names = {"g", "h", "k", "l", "m", "n", "o", "p", "q"};
        Class<?>[][] types = {
            {int.class, continuation, string, string}, {int.class, string, continuation},
            {int.class, string, continuation}, {int.class, string, continuation},
            {int.class, continuation, string, string}, {int.class, continuation, string, string},
            {int.class, nativeClass("app.zhendong.reamicro.data.storage.CloudBook"), string, continuation},
            {int.class, string, continuation}, {int.class, List.class, continuation},
        };
        for (int i = 0; i < names.length; i++) {
            Method method = (Method) resolve.invoke(singleton(raw), repository, names[i], types[i], false);
            check(method.getReturnType() == Object.class, "Storage return mismatch " + names[i]);
        }
        System.out.println("STORAGE_REFLECTION_CONTRACTS_PASS");
    }
    static void svg() throws Exception {
        if (android.graphics.Typeface.DEFAULT == null) {
            Method loadFonts = android.graphics.Typeface.class.getDeclaredMethod("loadPreinstalledSystemFontMap");
            loadFonts.setAccessible(true);
            loadFonts.invoke(null);
        }
        Class<?> preview = module.loadClass("com.reamicro.fix.epub.editor.B4HostSvgPreview");
        Object instance = preview.getConstructor(ClassLoader.class).newInstance(host);
        Object document = call(instance,"parse",new Class<?>[]{String.class},
            "<svg xmlns='http://www.w3.org/2000/svg' width='120' height='60' viewBox='0 0 120 60'><rect width='120' height='60' fill='red'/></svg>");
        check(Float.valueOf(120f).equals(call(document,"getWidth",new Class<?>[0])),"SVG width mismatch");
        check(Float.valueOf(60f).equals(call(document,"getHeight",new Class<?>[0])),"SVG height mismatch");
        Object nativeDocument = call(document,"getNative",new Class<?>[0]);
        check(call(instance,"picture",new Class<?>[]{Object.class,int.class,int.class},nativeDocument,120,60) != null,
            "SVG render failed");
        System.out.println("SVG_PARSE_RENDER_PASS");
    }
    public static void main(String[] args) throws Exception {
        setup(args);
        int failures=0;
        try {coroutine();}catch(Throwable e){failures++;e.printStackTrace();}
        try {paging();}catch(Throwable e){failures++;e.printStackTrace();}
        try {http(args[2]);}catch(Throwable e){failures++;e.printStackTrace();}
        if (PROFILE.equals("2.3.4-b1")) {
            try {svg();}catch(Throwable error){failures++;error.printStackTrace();}
            try {storageContracts();}catch(Throwable error){failures++;error.printStackTrace();}
            try {uiPrimitives();}catch(Throwable error){failures++;error.printStackTrace();}
            try {searchAndDiscoverContracts();}catch(Throwable error){failures++;error.printStackTrace();}
        }
        System.out.println("PROTOCOL_FAILURES="+failures);
        System.exit(failures==0?0:1);
    }
}
