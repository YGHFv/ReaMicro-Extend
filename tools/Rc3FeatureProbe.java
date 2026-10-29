import android.os.Looper;
import android.app.Application;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** 使用真实宿主类和独立内存设置验证生产适配，不访问用户账号、书库或网盘。 */
public final class Rc3FeatureProbe {
    static ClassLoader host, module;
    static Class<?> symbols;
    static Object account, paging, preferences;
    static final List<String> failures = new ArrayList<>();
    static Class<?> cls(String logical) throws Exception { return Rc3ProtocolProbe.nativeClass(logical); }
    static Object obj(Class<?> c) throws Exception { return c.getField("INSTANCE").get(null); }
    static Object invoke(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        Method m = target.getClass().getDeclaredMethod(name, types);
        m.setAccessible(true);
        return m.invoke(target, args);
    }
    static Object suspend(Method m, Object target, Object... args) throws Exception {
        Class<?> c = module.loadClass("com.reamicro.fix.core.HostCoroutines");
        return c.getMethod("invoke", ClassLoader.class, Method.class, Object.class, Object[].class)
            .invoke(obj(c), host, m, target, args);
    }
    static Field mappedField(Class<?> c, String old) throws Exception {
        Class<?> a = module.loadClass("com.reamicro.fix.core.HostRawAbi");
        return (Field)a.getMethod("field",Class.class,String.class).invoke(obj(a),c,old);
    }
    static void check(boolean ok, String message) { if (!ok) throw new IllegalStateException(message); }
    static void test(String label, Task task) {
        try { task.run(); System.out.println(label+"_PASS"); }
        catch (Throwable error) {
            failures.add(label);
            System.out.println(label+"_FAIL");
            error.printStackTrace();
        }
    }
    interface Task { void run() throws Exception; }
    public static final class FixtureApplication extends Application {
        private final File cache;
        FixtureApplication(File cache) { this.cache=cache; }
        @Override public File getCacheDir() { return cache; }
    }
    static Object flow(Object value) throws Exception {
        return invoke(paging,"singleFlow",new Class<?>[]{Object.class},value);
    }
    static Object preferenceKey(String name) throws Exception {
        return cls("ReaMicroHost.PreferenceKey").getConstructor(String.class).newInstance(name);
    }
    static Object setting(String key) throws Exception {
        return preferences.getClass().getMethod("c",cls("ReaMicroHost.PreferenceKey"))
            .invoke(preferences,preferenceKey(key));
    }
    static void accounts() throws Exception {
        Class<?> key = cls("ReaMicroHost.PreferenceKey");
        Class<?> prefs = host.loadClass("tn6");
        LinkedHashMap<Object,Object> entries = new LinkedHashMap<>();
        entries.put(preferenceKey("token"),"fixture-token-never-sent");
        entries.put(preferenceKey("padding"),17);
        entries.put(preferenceKey("text_size"),22f);
        preferences = prefs.getConstructor(LinkedHashMap.class,boolean.class).newInstance(entries,false);
        AtomicReference<Object> current = new AtomicReference<>(preferences);
        Class<?> store = host.loadClass("j52");
        Object dataStore = Proxy.newProxyInstance(host,new Class<?>[]{store},(proxy,m,a)->{
            if (m.getName().equals("getData")) return flow(current.get());
            if (m.getName().equals("a")) {
                Object transform = a[0];
                Method call = Arrays.stream(transform.getClass().getMethods())
                    .filter(x->x.getParameterCount()==2 && x.getName().equals("C")).findFirst().get();
                Object result = call.invoke(transform,current.get(),a[1]);
                current.set(result); preferences=result; return result;
            }
            if (m.getName().equals("toString")) return "FixtureDataStore";
            if (m.getName().equals("hashCode")) return System.identityHashCode(proxy);
            if (m.getName().equals("equals")) return proxy==a[0];
            throw new UnsupportedOperationException(m.toString());
        });
        Class<?> sessionClass = cls("app.zhendong.reamicro.repository.core.Session");
        Object session = sessionClass.getConstructor(store).newInstance(dataStore);
        test("ACCOUNT_CAPTURE",()->{
            Object snapshot=invoke(account,"captureSessionSnapshot",new Class<?>[]{Object.class},session);
            check(invoke(snapshot,"getToken",new Class<?>[0]).equals("fixture-token-never-sent"),"Token mismatch");
            check(invoke(snapshot,"getTextSize",new Class<?>[0]).equals(22f),"Size mismatch");
        });
        test("ACCOUNT_UPDATE",()->{
            invoke(account,"updatePreference",new Class<?>[]{Object.class,Object.class,Object.class},
                session,preferenceKey("full_audit_marker"),"changed");
            check("changed".equals(setting("full_audit_marker")),"Preference update not applied");
        });
        test("ACCOUNT_MARGINS",()->{
            Class<?> snapshot=module.loadClass("com.reamicro.fix.hook.ReaderMarginsSnapshot");
            Object margins=snapshot.getConstructor(int.class,int.class,int.class,int.class,int.class,int.class)
                .newInstance(11,12,13,14,15,16);
            invoke(account,"restoreReaderMargins",new Class<?>[]{Object.class,snapshot},session,margins);
            String[] keys={"padding_top","padding_bottom","padding_left","padding_right","padding_top_outer","padding_bottom_outer"};
            for(int i=0;i<keys.length;i++) check(Integer.valueOf(11+i).equals(setting(keys[i])),"Margin "+keys[i]);
        });
        test("ACCOUNT_REPOSITORY_FIELDS",()->{
            Class<?> shelf=cls("app.zhendong.reamicro.repository.BookshelfRepository");
            check(mappedField(shelf,"k").getType()==sessionClass,"Session field type");
            check(mappedField(shelf,"l").getType()==cls("app.zhendong.reamicro.arch.fs.UserStorage"),"Storage field type");
            Class<?> repo=cls("app.zhendong.reamicro.repository.UserRepository");
            check(mappedField(repo,"g").getType()==host.loadClass("dwb"),"UserDao field type");
        });
    }
    static void records() throws Exception {
        Object copier=module.loadClass("com.reamicro.fix.core.B2BookAssociation").getConstructor(ClassLoader.class).newInstance(host);
        Class<?> book=cls("app.zhendong.reamicro.data.db.entity.Book");
        Constructor<?> ctor=Arrays.stream(book.getConstructors()).filter(c->c.getParameterCount()==27).findFirst().get();
        Object[] args=new Object[27]; Class<?>[] types=ctor.getParameterTypes();
        for(int i=0;i<27;i++) {
            if(types[i]==String.class) args[i]="fixture-"+i;
            else if(types[i]==long.class) args[i]=100L+i;
            else if(types[i]==int.class) args[i]=20+i;
            else if(types[i]==float.class) args[i]=.125f;
            else if(types[i]==double.class) args[i]=.125;
            else if(types[i]==boolean.class) args[i]=true;
            else throw new IllegalStateException("Unexpected book field "+types[i]);
        }
        Object original=ctor.newInstance(args);
        test("ONDEMAND_CHAPTER_ACCESSORS",()->{
            Class<?> chapter=cls("app.zhendong.reamicro.data.db.entity.BookChapter");
            Object value=chapter.getConstructor(int.class,int.class,long.class,long.class,
                String.class,String.class,String.class,String.class)
                .newInstance(6,0,12L,34L,"Chapter Seven","Text/chapter_0007.xhtml","p0","epubcfi(/6/14/4/0)");
            Class<?> helpers=module.loadClass("com.reamicro.fix.xposed.XposedHelpers");
            Method getter=helpers.getMethod("callMethod",Object.class,String.class,Object[].class);
            check("Text/chapter_0007.xhtml".equals(getter.invoke(null,value,"getHref",new Object[0])),
                "Catalog interception cannot read href");
            check("Chapter Seven".equals(getter.invoke(null,value,"getTitle",new Object[0])),
                "Catalog title missing");
            check(getter.invoke(null,value,"getCfi",new Object[0])!=null,"Chapter CFI missing");
        });
        test("SELECTION_POSITION_ABI",()->{
            Class<?> abi=module.loadClass("com.reamicro.fix.core.HostRawAbi");
            Method lookup=abi.getMethod("method",Class.class,String.class,Class[].class);
            Method first=(Method)lookup.invoke(obj(abi),cls("ReaMicroHost.FlowFirst"),"e",
                new Class<?>[]{cls("kotlinx.coroutines.flow.Flow"),cls("kotlin.coroutines.Continuation")});
            check("position-state".equals(suspend(first,null,flow("position-state"))),
                "Selection state Flow not collected");
            Method save=(Method)lookup.invoke(obj(abi),cls("app.zhendong.reamicro.repository.ReadRepository"),"K",
                new Class<?>[]{book,boolean.class,Long.class,cls("kotlin.coroutines.jvm.internal.ContinuationImpl")});
            check(save.getName().equals("J") && save.getParameterCount()==4,
                "Reading position write signature differs");
        });
        test("SYNC_AUTH_CALLBACK_CAPTURE",()->{
            Class<?> callback=cls("ReaMicroHost.SyncAuthContent");
            Class<?> state=host.loadClass("eo6");
            Class<?> f1=cls("kotlin.jvm.functions.Function1");
            Class<?> f2=cls("kotlin.jvm.functions.Function2");
            List<String> calls=new ArrayList<>();
            Object authorize=Proxy.newProxyInstance(host,new Class<?>[]{f1},(p,m,a)->{
                calls.add("authorize");return cls("kotlin.Unit").getField("a").get(null);
            });
            Object setup=Proxy.newProxyInstance(host,new Class<?>[]{f1},(p,m,a)->{
                calls.add("setup:"+a[0]);return cls("kotlin.Unit").getField("a").get(null);
            });
            Object pick=Proxy.newProxyInstance(host,new Class<?>[]{f2},(p,m,a)->{
                calls.add("pick:"+a[0]+":"+a[1]);return cls("kotlin.Unit").getField("a").get(null);
            });
            Object value=callback.getConstructor(Integer.class,state,state,state,f1,f2,f1)
                .newInstance(null,null,null,null,authorize,pick,setup);
            check(callback.getField("a").getByte(value)==0,"Wrong callback branch");
            check(callback.getField("g").get(value)==setup,"Setup callback changed");
            check(callback.getField("h").get(value)==pick,"Upload callback changed");
            f1.getMethod("invoke",Object.class).invoke(callback.getField("g").get(value),9);
            Method two=Arrays.stream(f2.getMethods()).filter(m->m.getParameterCount()==2).findFirst().get();
            two.invoke(callback.getField("h").get(value),9,"/Backup");
            check(calls.equals(Arrays.asList("setup:9","pick:9:/Backup")),"Callback forwarding "+calls);
        });
        test("ASSOCIATION_COPY",()->{
            Map<?,?> before=(Map<?,?>)invoke(copier,"snapshot",new Class<?>[]{Object.class},original);
            Object copy=invoke(copier,"withoutAssociation",new Class<?>[]{Object.class},original);
            Map<?,?> after=(Map<?,?>)invoke(copier,"snapshot",new Class<?>[]{Object.class},copy);
            for(Object key:before.keySet()) check(Objects.equals(after.get(key),
                key.equals("cloudId")?0L:key.equals("publisher")?"":before.get(key)),"Changed unrelated field "+key);
        });
        test("METADATA_VERIFY",()->{
            Class<?> sync=module.loadClass("com.reamicro.fix.hook.ReaMicroBookMetadataSync");
            Class<?> patch=module.loadClass("com.reamicro.fix.hook.BookMetadataPatch");
            Object value=patch.getConstructor(String.class,String.class,String.class,String.class,Long.class,String.class)
                .newInstance(args[3],args[4],args[5],args[6],args[7],args[25]);
            Method verify=Arrays.stream(sync.getDeclaredMethods())
                .filter(m->m.getName().startsWith("verifyPatch") && m.getParameterCount()==3
                    && !Modifier.isStatic(m.getModifiers())).findFirst().get();
            verify.setAccessible(true);
            check(Boolean.TRUE.equals(verify.invoke(obj(sync),original,original,value)),"Unchanged record rejected");
        });
    }
    static void imports(String root) throws Exception {
        Class<?> f0=module.loadClass("kotlin.jvm.functions.Function0");
        Object activity=Proxy.newProxyInstance(module,new Class<?>[]{f0},(p,m,a)->null);
        Object settings=Proxy.newProxyInstance(module,new Class<?>[]{f0},(p,m,a)->
            module.loadClass("com.reamicro.fix.settings.ModuleSettingsSnapshot").getConstructor().newInstance());
        Object hook=module.loadClass("com.reamicro.fix.hook.ReaderImportOverwriteHook")
            .getConstructor(ClassLoader.class,f0,f0).newInstance(host,activity,settings);
        Object path=host.loadClass("q64").getMethod("z0",String.class,boolean.class).invoke(null,root+"/epub",false);
        Object opf=host.loadClass("btd").getMethod("m",path.getClass()).invoke(null,path);
        check(opf!=null,"Native fixture OPF missing");
        test("IMPORT_TITLE_UUID",()->{
            check("RC3 Audit Fixture".equals(invoke(hook,"resolveImportTitle",new Class<?>[]{Object.class,Object.class},opf,null)),
                "Import title not resolved");
            Object uuid=invoke(hook,"resolveImportUuid",new Class<?>[]{Object.class},opf);
            Object metadata=opf.getClass().getField("d").get(opf);
            Object identifier=metadata.getClass().getField("b").get(metadata);
            Object expected=identifier.getClass().getField("c").get(identifier);
            check(uuid!=null && uuid.equals(expected),"Import UUID differs from host normalization");
        });
        test("IMPORT_OBTAIN_OPF",()->check(
            invoke(hook,"obtainOpf",new Class<?>[]{Object.class},path)!=null,"Import obtain returned null"));
        test("IMPORT_METADATA",()->{
            Class<?> sync=module.loadClass("com.reamicro.fix.hook.ReaMicroBookMetadataSync");
            Object patch=sync.getMethod("metadataFromOpf",Object.class,Object.class).invoke(obj(sync),opf,path);
            check("RC3 Audit Fixture".equals(invoke(patch,"getTitle",new Class<?>[0])),"Metadata title missing");
            check("Fixture Author".equals(invoke(patch,"getAuthor",new Class<?>[0])),"Metadata author missing");
            check("Fixture Publisher".equals(invoke(patch,"getPublisher",new Class<?>[0])),"Metadata publisher missing");
        });
        test("ONLINE_IMPORT_DIRECTORY",()->{
            Object drive=module.loadClass("com.reamicro.fix.hook.WebDavDriveHook")
                .getConstructor(ClassLoader.class,f0,f0,f0).newInstance(host,activity,settings,activity);
            File destination=new File(root,"online-books");
            check(destination.mkdir(),"Fixture destination already exists");
            Object books=host.loadClass("q64").getMethod("z0",String.class,boolean.class)
                .invoke(null,destination.getAbsolutePath(),false);
            File importRoot=new File(root,"online-source");
            check(importRoot.mkdir(),"Fixture source already exists");
            for(String directory:new String[]{"META-INF","OEBPS"}) {
                File child=new File(importRoot,directory);
                check(child.mkdir(),"Fixture child already exists");
                for(File file:new File(root+"/epub/"+directory).listFiles()) {
                    java.nio.file.Files.copy(file.toPath(),new File(child,file.getName()).toPath());
                }
            }
            Object sourcePath=host.loadClass("q64").getMethod("z0",String.class,boolean.class)
                .invoke(null,importRoot.getAbsolutePath(),false);
            Class<?> entry=module.loadClass("com.reamicro.fix.hook.WebDavDriveHook_OnlineDownloadKt");
            Method importDirectory=Arrays.stream(entry.getDeclaredMethods())
                .filter(m->m.getName().equals("importOnlineCompletionEpubDirectory")).findFirst().get();
            importDirectory.setAccessible(true);
            Object pair=importDirectory.invoke(null,drive,sourcePath,books);
            Object directory=pair.getClass().getMethod("getFirst").invoke(pair);
            check(new File(directory.toString(),"META-INF/container.xml").isFile(),
                "Online import returned no EPUB container");
            check(pair.getClass().getMethod("getSecond").invoke(pair)!=null,"Online import returned no OPF");
        });
        test("IMPORT_REWRITE_UUID",()->{
            Object updated=invoke(hook,"withUuid",new Class<?>[]{Object.class,String.class},opf,"full-audit-independent");
            check("full-audit-independent".equals(invoke(hook,"resolveImportUuid",new Class<?>[]{Object.class},updated)),
                "Independent import retained original UUID");
            invoke(hook,"overwriteToRoot",new Class<?>[]{Object.class,Object.class},updated,path);
            Object reloaded=invoke(hook,"obtainOpf",new Class<?>[]{Object.class},path);
            check("full-audit-independent".equals(invoke(hook,"resolveImportUuid",new Class<?>[]{Object.class},reloaded)),
                "Independent import UUID not persisted");
        });
    }
    static void http(String root) throws Exception {
        Class<?> activityThread=Class.forName("android.app.ActivityThread");
        Constructor<?> ctor=activityThread.getDeclaredConstructor(); ctor.setAccessible(true);
        Object thread=ctor.newInstance();
        Field current=activityThread.getDeclaredField("sCurrentActivityThread"); current.setAccessible(true); current.set(null,thread);
        Field app=activityThread.getDeclaredField("mInitialApplication"); app.setAccessible(true);
        app.set(thread,new FixtureApplication(new File(root)));
        Class<?> scope=module.loadClass("com.reamicro.fix.cloud.webdav.CleartextScope");
        Object clear=Proxy.newProxyInstance(module,new Class<?>[]{scope},(p,m,a)->
            module.loadClass("kotlin.jvm.functions.Function0").getMethod("invoke").invoke(a[0]));
        Class<?> client=module.loadClass("com.reamicro.fix.cloud.webdav.B2HostHttp");
        Object http=client.getConstructor(ClassLoader.class,scope).newInstance(host,clear);
        Class<?> progress=module.loadClass("kotlin.jvm.functions.Function1");
        Class<?>[] signature={String.class,String.class,Map.class,Object.class,File.class,progress};
        String base="http://127.0.0.1:18781";
        Map<String,String> headers=Collections.singletonMap("Authorization","Basic cmVhbWljcm8tcWE6YjItZml4dHVyZS1vbmx5");
        test("HTTP_LIST",()->{
            Object response=invoke(http,"request",signature,base+"/Books/","PROPFIND",headers,null,null,null);
            check(Integer.valueOf(207).equals(invoke(response,"getCode",new Class<?>[0])),"PROPFIND status");
            check(invoke(response,"getBodyString",new Class<?>[0]).toString().contains("RC3-Audit-Network"),"Listing body");
        });
        test("HTTP_DOWNLOAD",()->{
            File dest=new File(root,"download.txt");
            List<Integer> updates=new ArrayList<>();
            Object callback=Proxy.newProxyInstance(module,new Class<?>[]{progress},(p,m,a)->{
                if(m.getName().equals("invoke")) updates.add((Integer)a[0]);
                return module.loadClass("kotlin.Unit").getField("INSTANCE").get(null);
            });
            Object response=invoke(http,"request",signature,base+"/Books/RC3-Audit-Network.txt","GET",headers,null,dest,callback);
            check(Integer.valueOf(200).equals(invoke(response,"getCode",new Class<?>[0])),"GET status");
            check(new String(java.nio.file.Files.readAllBytes(dest.toPath()),StandardCharsets.UTF_8).contains("FullAuditNeedle"),"Download bytes");
            check(updates.contains(100),"No final progress");
        });
        test("HTTP_UPLOAD",()->{
            File source=new File(root,"download.txt");
            Object body=invoke(http,"fileBody",new Class<?>[]{File.class,String.class},source,"text/plain");
            Object response=invoke(http,"request",signature,base+"/Backup/RC3-Audit-Upload.txt","PUT",headers,body,null,null);
            check(Integer.valueOf(201).equals(invoke(response,"getCode",new Class<?>[0])),"PUT status");
            Object back=invoke(http,"request",signature,base+"/Backup/RC3-Audit-Upload.txt","GET",headers,null,null,null);
            check(invoke(back,"getBodyString",new Class<?>[0]).equals(
                new String(java.nio.file.Files.readAllBytes(source.toPath()),StandardCharsets.UTF_8)),"Upload roundtrip");
        });
    }
    static void fixtureState() throws Exception {
        try(android.database.sqlite.SQLiteDatabase db=android.database.sqlite.SQLiteDatabase.openDatabase(
            "/data/user/0/app.zhendong.reamicro/databases/app.db",null,
            android.database.sqlite.SQLiteDatabase.OPEN_READONLY);
            android.database.Cursor rows=db.rawQuery(
                "SELECT id,uuid,uid,title,author,publisher,cover,created,updated,size FROM books WHERE title=? ORDER BY id",
                new String[]{"RC3 Audit Fixture"})) {
            org.json.JSONArray result=new org.json.JSONArray();
            while(rows.moveToNext()) {
                org.json.JSONObject row=new org.json.JSONObject();
                for(int i=0;i<rows.getColumnCount();i++) row.put(rows.getColumnName(i),
                    rows.getType(i)==android.database.Cursor.FIELD_TYPE_INTEGER?rows.getLong(i):rows.getString(i));
                String root="/data/user/0/app.zhendong.reamicro/files/"+row.getLong("uid")+"/books/"+row.getString("uuid");
                row.put("containerExists",new File(root+"/META-INF/container.xml").isFile());
                result.put(row);
            }
            System.out.println(result.toString());
        }
    }
    public static void main(String[] args) throws Exception {
        if(args.length==1 && args[0].equals("--fixture-state")) {fixtureState();return;}
        if(Looper.getMainLooper()==null) Looper.prepareMainLooper();
        Rc3ProtocolProbe.setup(args);
        host=Rc3ProtocolProbe.host; module=Rc3ProtocolProbe.module; symbols=Rc3ProtocolProbe.symbols;
        Class<?> f0=module.loadClass("kotlin.jvm.functions.Function0");
        Object provider=Proxy.newProxyInstance(module,new Class<?>[]{f0},(p,m,a)->null);
        account=module.loadClass("com.reamicro.fix.hook.AccountCompletionController")
            .getConstructor(ClassLoader.class,f0).newInstance(host,provider);
        paging=module.loadClass("com.reamicro.fix.core.B2PagingData").getConstructor(ClassLoader.class).newInstance(host);
        test("ACCOUNT_SETUP",Rc3FeatureProbe::accounts);
        test("RECORD_SETUP",Rc3FeatureProbe::records);
        test("IMPORT_SETUP",()->imports(args[2]));
        if(args.length>3 && args[3].equals("http")) test("HTTP_SETUP",()->http(args[2]));
        System.out.println("FEATURE_FAILURES="+failures);
        System.exit(failures.isEmpty()?0:1);
    }
}
