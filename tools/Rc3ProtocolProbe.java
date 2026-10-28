import android.content.pm.ApplicationInfo;
import dalvik.system.PathClassLoader;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipFile;

/** 独立进程验收协程、分页和 HTTP 请求构造，不连接用户网盘、不写用户数据。 */
public final class Rc3ProtocolProbe {
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
             InputStream input = zip.getInputStream(zip.getEntry("assets/host-profiles/2.3.3-rc3.profile"));
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
        Object http = abi.getMethod("http",String.class).invoke(singleton(abi),"2.3.3-rc3");
        byte branch = (Byte)call(http,"getBuilderBranch",new Class<?>[0]);
        Object builder = nativeClass("ReaMicroHost.HttpRequestBuilder").getDeclaredConstructor(byte.class).newInstance(branch);
        check("GET".equals(get(builder,"c")),"Request builder branch mismatch");
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
    public static void main(String[] args) throws Exception {
        setup(args);
        int failures=0;
        try {coroutine();}catch(Throwable e){failures++;e.printStackTrace();}
        try {paging();}catch(Throwable e){failures++;e.printStackTrace();}
        try {http(args[2]);}catch(Throwable e){failures++;e.printStackTrace();}
        System.out.println("PROTOCOL_FAILURES="+failures);
        System.exit(failures==0?0:1);
    }
}
