import java.lang.reflect.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** 独立进程只访问本次测试服务，不读取宿主账号或书架数据库。 */
public final class WebDavSyncContract {
    static ClassLoader module, host;
    static Object instance(Class<?> type) throws Exception { return type.getField("INSTANCE").get(null); }
    static void check(boolean value, String name) { if (!value) throw new AssertionError(name); }
    static Object call(Object object, String name, Object... args) throws Exception {
        List<Method> methods=new ArrayList<>();
        for(Class<?> type=object.getClass();type!=null;type=type.getSuperclass())
            methods.addAll(Arrays.asList(type.getDeclaredMethods()));
        for (Method m : methods) if (m.getName().equals(name) && m.getParameterCount() == args.length) {
            m.setAccessible(true);
            try { return m.invoke(object, args); } catch (InvocationTargetException e) { throw new RuntimeException(e.getCause()); }
        }
        throw new NoSuchMethodException(name);
    }
    static void abi() throws Exception {
        Class<?> iface = host.loadClass("bf9");
        long virtual = Arrays.stream(iface.getDeclaredMethods()).filter(m -> !Modifier.isStatic(m.getModifiers())).count();
        check(virtual == 8, "transport interface");
        Class<?> transport = host.loadClass("uk6");
        for (Method method : iface.getDeclaredMethods()) if (!Modifier.isStatic(method.getModifiers())) {
            transport.getDeclaredMethod(method.getName(), method.getParameterTypes());
        }
        check(host.loadClass("dd9").getConstructor(host.loadClass("he9"), String.class, String.class,
            String.class, String.class, String.class, String.class) != null, "config ctor");
        host.loadClass("zua").getDeclaredMethod("b", host.loadClass("gq3"), host.loadClass("ys3"), int.class);
        check(Arrays.stream(host.loadClass("z6b").getDeclaredMethods()).anyMatch(m -> m.getName().equals("b") && m.getParameterCount()==20), "text ABI");
        check(Arrays.stream(host.loadClass("p1e").getDeclaredMethods()).anyMatch(m -> m.getName().equals("a") && m.getParameterCount()==9), "card ABI");
        host.loadClass("j09").getDeclaredMethod("g", host.loadClass("j09"), String.class);
        host.loadClass("ad9").getDeclaredMethod("c", long.class, host.loadClass("dd9"), host.loadClass("ny1"));
        host.loadClass("dn7").getMethod("toFile");
        System.out.println("WEBDAV_SYNC_NATIVE_ABI_PASS");
    }
    static Object http() throws Exception {
        Class<?> scope = module.loadClass("com.reamicro.fix.cloud.webdav.CleartextScope");
        Object clear = Proxy.newProxyInstance(module, new Class<?>[]{scope}, (proxy,method,args) ->
            args == null ? null : call(args[0], "invoke"));
        Class<?> type = module.loadClass("com.reamicro.fix.cloud.webdav.B2HostHttp");
        Constructor<?> ctor = type.getDeclaredConstructor(ClassLoader.class, scope); ctor.setAccessible(true);
        Object http = ctor.newInstance(host, clear);
        // 流式桥由独立构造/写出断言验证；探测不伪造 ActivityThread Application。
        File file = new File("/data/local/tmp/reamicro-webdav-sync-contract/stream.bin");
        try (FileOutputStream output = new FileOutputStream(file)) { output.write(new byte[]{0,1,2,(byte)255}); }
        Object body = call(http, "fileBody", file, "application/octet-stream");
        Class<?> sink = Rc3ProtocolProbe.nativeClass("ReaMicroHost.HttpBufferedSink");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Object sinkProxy = Proxy.newProxyInstance(host, new Class<?>[]{sink}, (p,m,a)-> {
            if (m.getName().equals("write")) { output.write((byte[])a[0],(Integer)a[1],(Integer)a[2]); return p; }
            return null;
        });
        call(body, "e", sinkProxy);
        check(Arrays.equals(output.toByteArray(), new byte[]{0,1,2,(byte)255}), "native streaming bridge");
        Class<?> lazy = module.loadClass("kotlin.Lazy");
        Object ready = Proxy.newProxyInstance(module,new Class<?>[]{lazy},(p,m,a)->m.getName().equals("getValue")?true:null);
        Field verified = type.getDeclaredField("streamingVerified$delegate"); verified.setAccessible(true); verified.set(http, ready);
        return http;
    }
    static void remote(Object http, String url, String root) throws Exception {
        Class<?> remoteType = module.loadClass("com.reamicro.fix.cloud.webdav.WebDavSyncTransport");
        Class<?> fail = module.loadClass("kotlin.jvm.functions.Function1");
        Object error = Proxy.newProxyInstance(module, new Class<?>[]{fail}, (p,m,a)-> {
            if (a != null && a.length==1) throw new IllegalStateException("HTTP "+a[0]);
            return null;
        });
        Constructor<?> ctor = remoteType.getDeclaredConstructors()[0]; ctor.setAccessible(true);
        Object remote = ctor.newInstance(http, url, "qa", "qa-password", error);
        call(remote, "verify");
        System.out.println("WEBDAV_SYNC_CONDITIONS_VERIFY_PASS");
        byte[] data = {0,1,2,(byte)255,8};
        call(remote, "put", "reamicro/books/fixture.bin", data, null, "application/octet-stream", "*");
        Object response = call(remote, "get", "reamicro/books/fixture.bin");
        check(Arrays.equals((byte[])call(response,"getBodyBytes"), data), "binary roundtrip");
        Object head = call(remote, "head", "reamicro/books/fixture.bin");
        Map<?,?> headers = (Map<?,?>)call(head, "getHeaders");
        check(headers.get("Content-Length").equals("5"), "head size");
        File output = new File(root,"download.bin");
        call(remote, "download", "reamicro/books/fixture.bin", output);
        check(output.length()==5, "file download");
        call(remote, "delete", "reamicro/books/fixture.bin");
        check(call(remote,"get","reamicro/books/fixture.bin")==null,"missing object");
        System.out.println("WEBDAV_SYNC_BINARY_HEAD_FILE_DELETE_PASS");
    }
    static Object moduleFunction(String name, java.util.function.Supplier<Object> action) throws Exception {
        return Proxy.newProxyInstance(module,new Class<?>[]{module.loadClass(name)},(p,m,a)->
            m.getName().equals("invoke")?action.get():null);
    }
    static Object hook() throws Exception {
        Object none=moduleFunction("kotlin.jvm.functions.Function0",()->null);
        Object settings=moduleFunction("kotlin.jvm.functions.Function0",()->{
            try {
                for(Constructor<?> c:module.loadClass("com.reamicro.fix.settings.ModuleSettingsSnapshot").getConstructors())
                    if(c.getParameterCount()==0) return c.newInstance();
                throw new AssertionError("settings constructor");
            } catch(Exception e){throw new RuntimeException(e);}
        });
        Class<?> driveType=module.loadClass("com.reamicro.fix.hook.WebDavDriveHook");
        Object drive=driveType.getConstructor(ClassLoader.class,none.getClass().getInterfaces()[0],
            none.getClass().getInterfaces()[0],none.getClass().getInterfaces()[0]).newInstance(host,none,settings,none);
        Class<?> type=module.loadClass("com.reamicro.fix.hook.WebDavSyncHook");
        Constructor<?> ctor=Arrays.stream(type.getDeclaredConstructors()).filter(c->c.getParameterCount()==4).findFirst().get();
        ctor.setAccessible(true);
        return ctor.newInstance(host,none,settings,drive);
    }
    static void preferences(Object hook) throws Exception {
        Class<?> prefsType=host.loadClass("tn6");
        Object initial=prefsType.getConstructor(LinkedHashMap.class,boolean.class).newInstance(new LinkedHashMap<>(),false);
        AtomicReference<Object> prefs=new AtomicReference<>(initial);
        Class<?> pagingType=module.loadClass("com.reamicro.fix.core.B2PagingData");
        Object paging=pagingType.getConstructor(ClassLoader.class).newInstance(host);
        Class<?> dataStore=host.loadClass("j52");
        Object store=Proxy.newProxyInstance(host,new Class<?>[]{dataStore},(p,m,a)->{
            if(m.getName().equals("getData")) return call(paging,"singleFlow",prefs.get());
            if(m.getName().equals("a")){
                Object next=call(a[0],"C",prefs.get(),a[1]);
                prefs.set(next);
                return next;
            }
            throw new AssertionError(m.toString());
        });
        Object session=host.loadClass("w1a").getConstructor(dataStore).newInstance(store);
        Map<String,Object> values=new LinkedHashMap<>();
        values.put("s3_sync_provider","S3");
        values.put("s3_sync_endpoint","http://127.0.0.1:18791/dav");
        values.put("s3_sync_bucket","reamicro-webdav-sync-v1");
        values.put("s3_sync_region","auto");
        values.put("s3_sync_access_key","qa");
        values.put("s3_sync_secret_key","qa-password");
        values.put("s3_sync_prefix","reamicro");
        values.put("s3_sync_cloud_config_enabled",false);
        call(hook,"saveValues",session,values);
        values.put("s3_sync_baseline_cbor",null);
        check(call(hook,"nativeValues",session).equals(values),"native config readback");
        call(hook,"saveValues",session,Collections.singletonMap("s3_sync_baseline_cbor",new byte[]{1,2,3}));
        call(hook,"saveValues",session,Collections.singletonMap("s3_sync_endpoint","http://127.0.0.1:18791/other"));
        check(((Map<?,?>)call(hook,"nativeValues",session)).get("s3_sync_baseline_cbor")==null,"old baseline reset");
        Map<String,Object> cleared=new LinkedHashMap<>();
        for(String key:values.keySet())cleared.put(key,null);
        call(hook,"saveValues",session,cleared);
        check(call(hook,"nativeValues",session).equals(cleared),"native config restore");
        System.out.println("WEBDAV_SYNC_NATIVE_SETTINGS_ATOMIC_READBACK_PASS");
    }
    static void continuation(Object hook) throws Exception {
        CountDownLatch done=new CountDownLatch(1);
        AtomicReference<Object> returned=new AtomicReference<>();
        Class<?> continuation=Rc3ProtocolProbe.nativeClass("kotlin.coroutines.Continuation");
        Object context=Rc3ProtocolProbe.nativeClass("kotlin.coroutines.EmptyCoroutineContext").getField("a").get(null);
        Object completion=Proxy.newProxyInstance(host,new Class<?>[]{continuation},(p,m,a)->{
            if(m.getName().equals("x"))return context;
            if(m.getName().equals("i")){returned.set(a[0]);done.countDown();return null;}
            return null;
        });
        Class<?> param=module.loadClass("com.reamicro.fix.xposed.XC_MethodHook$MethodHookParam");
        Object invocation=param.getConstructor(Executable.class,Object.class,Object[].class)
            .newInstance(host.loadClass("uk6").getDeclaredMethod("c",String.class,host.loadClass("ny1")),
                null,new Object[]{"fixture",completion});
        Object action=moduleFunction("kotlin.jvm.functions.Function0",()->"native-resume-fixture");
        call(hook,"suspendOperation",invocation,action);
        check(call(invocation,"getResult").toString().equals("COROUTINE_SUSPENDED"),"suspend marker");
        check(done.await(20,TimeUnit.SECONDS),"native continuation timeout");
        check("native-resume-fixture".equals(returned.get()),"native resume result");
        System.out.println("WEBDAV_SYNC_NATIVE_ASYNC_CONTINUATION_PASS");
    }
    static Object suspend(Object target,String name,Object... args) throws Exception {
        Method nativeMethod=Arrays.stream(target.getClass().getDeclaredMethods())
            .filter(m->m.getName().equals(name)&&m.getParameterCount()==args.length+1).findFirst().get();
        nativeMethod.setAccessible(true);
        Class<?> coroutines=module.loadClass("com.reamicro.fix.core.HostCoroutines");
        return coroutines.getMethod("invokeTimed",ClassLoader.class,Method.class,Object.class,Long.class,Object[].class)
            .invoke(instance(coroutines),host,nativeMethod,target,20_000L,args);
    }
    static void manifest(Object http,String url) throws Exception {
        Class<?> remoteType=module.loadClass("com.reamicro.fix.cloud.webdav.WebDavSyncTransport");
        Class<?> fail=module.loadClass("kotlin.jvm.functions.Function1");
        Object error=Proxy.newProxyInstance(module,new Class<?>[]{fail},(p,m,a)->{
            if(a!=null&&a.length==1)throw (Throwable)host.loadClass("za9").getConstructor(int.class,String.class)
                .newInstance(a[0],"fixture status");
            return null;
        });
        Constructor<?> ctor=remoteType.getDeclaredConstructors()[0];ctor.setAccessible(true);
        Object remote=ctor.newInstance(http,url,"qa","qa-password",error);
        Class<?> storage=host.loadClass("bf9");
        Object nativeStore=Proxy.newProxyInstance(host,new Class<?>[]{storage},(p,m,a)->{
            String key=a[0].toString();
            switch(m.getName()){
                case "c":{
                    Object response=call(remote,"get",key);
                    if(response==null)return null;
                    Map<?,?> headers=(Map<?,?>)call(response,"getHeaders");
                    return host.loadClass("gb9").getConstructor(String.class,byte[].class)
                        .newInstance(headers.get("ETag"),call(response,"getBodyBytes"));
                }
                case "e":case "g":
                    call(remote,"put",key,a[1],null,a[2],a[3]);return host.loadClass("ysb").getField("a").get(null);
                case "i":{
                    Object response=call(remote,"get",key);
                    return call(response,"getBodyBytes");
                }
                default:throw new AssertionError(m.toString());
            }
        });
        String prefix="fixture-manifest-"+UUID.randomUUID();
        Object store=host.loadClass("jp9").getConstructor(storage,String.class,long.class,host.loadClass("dz6"))
            .newInstance(nativeStore,prefix,1L,null);
        Object snapshot=suspend(store,"V");
        Field root=host.loadClass("tb9").getField("a");
        Object index=root.get(snapshot);
        Object empty=host.loadClass("ysb").getField("a").get(null);
        Class<?> codec=host.loadClass("vc9");
        Object cbor=codec.getField("b").get(null);
        Object companion=host.loadClass("vd9").getField("Companion").get(null);
        Object serializer=call(companion,"serializer");
        byte[] body=(byte[])call(cbor,"b",serializer,index);
        call(remote,"put",prefix+"/manifest.cbor",body,null,"application/cbor","*");
        Object read=suspend(store,"V");
        check(root.get(read).equals(index),"native CBOR manifest roundtrip");
        String etag=(String)host.loadClass("tb9").getField("b").get(read);
        check(etag!=null&&etag.startsWith("\""),"native CBOR etag");
        call(remote,"delete",prefix+"/manifest.cbor");
        call(remote,"delete",prefix);
        System.out.println("WEBDAV_SYNC_NATIVE_MANIFEST_CBOR_ROUNDTRIP_PASS");
    }
    public static void main(String[] args) throws Exception {
        android.os.Looper.prepareMainLooper();
        Rc3ProtocolProbe.setup(args);
        module=Rc3ProtocolProbe.module; host=Rc3ProtocolProbe.host;
        abi();
        Object http=http();
        remote(http,args[3],args[2]);
        Object hook=hook();
        preferences(hook);
        continuation(hook);
        manifest(http,args[3]);
        System.out.println("WEBDAV_SYNC_FAILURES=0");
        System.exit(0);
    }
}
