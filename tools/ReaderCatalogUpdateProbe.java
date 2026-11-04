import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.*;

/** 独立 app_process 的内存数据库验收，不读取用户书库、不安装 Hook。 */
public final class ReaderCatalogUpdateProbe {
    static ClassLoader host, module;
    static Object runtime, sql, sync;
    static Class<?> sqlType, tableType;
    static Object refsTable, chapterTable;
    static Object invoke(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        Method m = target.getClass().getDeclaredMethod(name, types);
        m.setAccessible(true);
        try { return m.invoke(target, args); }
        catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception) throw (Exception)e.getCause();
            throw e;
        }
    }
    static void check(boolean value, String message) { Rc3ProtocolProbe.check(value, message); }
    static void exec(String text, Object... args) throws Exception {
        invoke(sql, "execute", new Class<?>[]{String.class, List.class}, text, Arrays.asList(args));
    }
    @SuppressWarnings("unchecked") static List<List<Object>> query(String text, Integer... numbers) throws Exception {
        return (List<List<Object>>) invoke(sql, "query", new Class<?>[]{String.class, List.class, Set.class},
            text, Collections.emptyList(), new HashSet<>(Arrays.asList(numbers)));
    }
    static int sync(Object table, List<List<Object>> rows) throws Exception {
        return (Integer)invoke(sync, "sync", new Class<?>[]{sqlType, tableType, long.class, List.class}, sql, table, 7L, rows);
    }
    static List<Object> ref(long index, String text) {
        return Arrays.asList(7L, index, index / 2, "chapter-" + index, text, "", 100L);
    }
    static List<Object> chapter(long index, String title) {
        return Arrays.asList(7L, index, title, "Text/" + index + ".xhtml", "", "cfi:" + index, 0L);
    }
    static void catalog() throws Exception {
        exec("CREATE TABLE book_item_ref (id INTEGER PRIMARY KEY AUTOINCREMENT, book_id INTEGER NOT NULL, `index` INTEGER NOT NULL," +
            "spine_index INTEGER NOT NULL,id_ref TEXT NOT NULL,href TEXT NOT NULL,properties TEXT NOT NULL,size INTEGER NOT NULL)");
        exec("CREATE TABLE book_chapter (id INTEGER PRIMARY KEY AUTOINCREMENT,book_id INTEGER NOT NULL,play_order INTEGER NOT NULL," +
            "title TEXT NOT NULL,href TEXT NOT NULL,anchor TEXT NOT NULL,epubcfi TEXT NOT NULL,parent_order INTEGER NOT NULL)");
        check(sync(refsTable, Collections.singletonList(ref(2, "Text/1.xhtml"))) == 1, "Initial ref insert");
        check(sync(chapterTable, Collections.singletonList(chapter(1, "one"))) == 1, "Initial chapter insert");
        List<List<Object>> original = query("SELECT id,play_order,title FROM book_chapter", 0, 1);
        for (int i = 0; i < 100; i++) {
            check(sync(chapterTable, Collections.singletonList(chapter(1, "one"))) == 0, "No-op catalog wrote rows");
        }
        exec("INSERT INTO book_chapter (book_id,play_order,title,href,anchor,epubcfi,parent_order) VALUES (8,1,'other','other','','other',0)");
        List<List<Object>> other = query("SELECT id,title FROM book_chapter WHERE book_id=8", 0);
        check(sync(chapterTable, Arrays.asList(chapter(1, "one"), chapter(2, "two"))) == 1, "Append should only insert");
        check(query("SELECT id,play_order,title FROM book_chapter WHERE book_id=7 AND play_order=1", 0, 1).equals(original), "Append replaced existing id");
        check(sync(chapterTable, Arrays.asList(chapter(1, "renamed"), chapter(2, "two"))) == 1, "Rename should update one row");
        check(query("SELECT id FROM book_chapter WHERE book_id=7 AND play_order=1", 0).get(0).get(0).equals(original.get(0).get(0)), "Rename replaced primary key");
        check(sync(chapterTable, Collections.singletonList(chapter(1, "renamed"))) == 1, "Prune should delete one row");
        check(query("SELECT id,title FROM book_chapter WHERE book_id=8", 0).equals(other), "Cross-book mutation");
        System.out.println("CATALOG_SQL_DELTA_PASS");
    }
    static void metadata() throws Exception {
        exec("CREATE TABLE books (id INTEGER PRIMARY KEY,uid INTEGER,uuid TEXT,cover TEXT,size INTEGER,updated INTEGER,backup_type INTEGER," +
            "backup_id TEXT,backup_code TEXT,epubcfi TEXT,chapter TEXT,progress TEXT,total INTEGER,listening_total INTEGER,pinned_at INTEGER,publisher TEXT)");
        exec("CREATE TABLE book_sync_state (book_id INTEGER PRIMARY KEY,metadata_updated_at INTEGER,epub_dirty INTEGER,cover_dirty INTEGER," +
            "bookmarks_dirty INTEGER,epub_revision INTEGER,cover_revision INTEGER,bookmarks_revision INTEGER,epub_sha256 TEXT,cover_source_sha256 TEXT,bookmarks_sha256 TEXT)");
        exec("INSERT INTO books VALUES (7,1,'uuid','manual-cover',100,1,10,'source','code','latest-cfi','latest-chapter','0.65',3000,150,123,'manual-publisher')");
        exec("INSERT INTO book_sync_state VALUES (7,1,1,0,1,11,22,33,'epub-hash','cover-hash','marks-hash')");
        String reading = "SELECT epubcfi,chapter,progress,total,listening_total,pinned_at,publisher FROM books WHERE id=7";
        List<List<Object>> progress = query(reading, 3, 4, 5);
        Class<?> type = module.loadClass("com.reamicro.fix.core.OnlineBookContentPatch");
        Object patch = type.getConstructor(long.class,long.class,String.class,long.class,long.class,String.class,String.class,String.class)
            .newInstance(7L,1L,"uuid",500L,123456L,"fallback-source","fallback-code","fallback-cover");
        invoke(patch, "apply", new Class<?>[]{sqlType}, sql);
        check(query(reading, 3, 4, 5).equals(progress), "Content update overwrote live reading progress");
        check(query("SELECT cover,size,updated,backup_id,backup_code FROM books").get(0).equals(
            Arrays.asList("manual-cover","500","123456","source","code")), "Content metadata/fallback mismatch");
        check(query("SELECT metadata_updated_at,epub_dirty,cover_dirty,bookmarks_dirty,epub_revision,cover_revision,bookmarks_revision FROM book_sync_state",0,1,2,3,4,5,6)
            .get(0).equals(Arrays.asList(123456L,1L,0L,1L,11L,22L,33L)), "Native sync flags regressed");
        exec("UPDATE books SET cover='' WHERE id=7");
        invoke(patch, "apply", new Class<?>[]{sqlType}, sql);
        check(query("SELECT cover_dirty,cover_revision FROM book_sync_state",0,1).get(0).equals(Arrays.asList(1L,123456L)), "Cover revision not marked");
        exec("DELETE FROM books WHERE id=7"); exec("DELETE FROM book_sync_state WHERE book_id=7");
        invoke(patch, "apply", new Class<?>[]{sqlType}, sql);
        check(query("SELECT id FROM books",0).isEmpty() && query("SELECT book_id FROM book_sync_state",0).isEmpty(), "Late update resurrected deleted book");
        System.out.println("CONTENT_PATCH_PRESERVES_PROGRESS_PASS");
    }
    static void collector() throws Exception {
        Class<?> guard = module.loadClass("com.reamicro.fix.hook.ReaderCatalogFlowGuard");
        Object instance = Rc3ProtocolProbe.singleton(guard);
        Class<?> type = host.loadClass("pl3");
        Method emit = Arrays.stream(type.getMethods()).filter(m -> m.getParameterCount() == 2).findFirst().get();
        List<Object> seen = new ArrayList<>();
        Object unit = host.loadClass("e7c").getField("a").get(null);
        Object suspended = host.loadClass("o32").getField("a").get(null);
        Object original = Proxy.newProxyInstance(host,new Class<?>[]{type},(p,m,a) -> {
            seen.add(a[0]); return seen.size() == 1 ? suspended : unit;
        });
        Object wrapped = guard.getMethod("collector",ClassLoader.class,Object.class).invoke(instance,host,original);
        check(emit.invoke(wrapped,Arrays.asList("one"),null) == suspended, "Native suspension marker lost");
        for (int i=0;i<1000;i++) check(emit.invoke(wrapped,new ArrayList<>(Arrays.asList("one")),null)==unit, "Repeated invalidation not filtered");
        emit.invoke(wrapped,Arrays.asList("one","two"),null);
        check(seen.size()==2,"Current-book mutation was filtered");
        Object second = guard.getMethod("collector",ClassLoader.class,Object.class).invoke(instance,host,original);
        emit.invoke(second,Arrays.asList("one","two"),null);
        check(seen.size()==3,"Collector state leaked across subscriptions");
        CancellationException cancelled = new CancellationException("probe");
        Object failure = Proxy.newProxyInstance(host,new Class<?>[]{type},(p,m,a) -> {throw cancelled;});
        Object failed = guard.getMethod("collector",ClassLoader.class,Object.class).invoke(instance,host,failure);
        try { emit.invoke(failed,Collections.emptyList(),null); throw new AssertionError("Cancellation swallowed"); }
        catch (InvocationTargetException e) { check(e.getCause()==cancelled,"Cancellation identity changed"); }
        Constructor<?> nativeChapter=host.loadClass("hi0").getConstructor(int.class,int.class,long.class,long.class,
            String.class,String.class,String.class,String.class);
        Object nativeFirst=nativeChapter.newInstance(1,0,10L,7L,"one","href","","cfi");
        Object nativeEqual=nativeChapter.newInstance(1,0,10L,7L,"one","href","","cfi");
        check(nativeFirst!=nativeEqual && nativeFirst.equals(nativeEqual),"Native chapter structural equality changed");
        Object nativeWrapper=guard.getMethod("collector",ClassLoader.class,Object.class).invoke(instance,host,original);
        int before=seen.size();
        emit.invoke(nativeWrapper,Collections.singletonList(nativeFirst),null);
        emit.invoke(nativeWrapper,Collections.singletonList(nativeEqual),null);
        check(seen.size()==before+1,"Native entities not deduplicated");
        System.out.println("CATALOG_NATIVE_COLLECTOR_PASS");
    }
    interface SqlWork { void run() throws Exception; }
    static void transaction(Object dao, SqlWork work) throws Exception {
        Class<?> function=module.loadClass("kotlin.jvm.functions.Function1");
        Object callback=Proxy.newProxyInstance(module,new Class<?>[]{function},(p,m,a)->{
            sql=a[0]; work.run(); return null;
        });
        invoke(runtime,"transaction",new Class<?>[]{Object.class,function},dao,callback);
    }
    static void set(Object target, Class<?> declaring, String name, Object value) throws Exception {
        Field field=declaring.getDeclaredField(name); field.setAccessible(true); field.set(target,value);
    }
    static void room(String directory) throws Exception {
        // 只创建宿主同款内存 Room，不绑定 Application、Koin 或用户数据库路径。
        android.content.Context context=new android.content.ContextWrapper(null) {
            @Override public android.content.Context getApplicationContext() { return this; }
            @Override public File getDatabasePath(String name) { return new File(directory,name); }
            @Override public Object getSystemService(String name) { return null; }
        };
        Class<?> base=host.loadClass("cf9");
        Object db=host.loadClass("app.zhendong.reamicro.data.db.AppDatabase_Impl").getConstructor().newInstance();
        Object driver=host.loadClass("zw0").getConstructor(byte.class).newInstance((byte)0);
        Object migrations=host.loadClass("zw0").getConstructor(byte.class).newInstance((byte)2);
        Object dispatcher=host.loadClass("od2").getField("c").get(null);
        java.util.concurrent.Executor executor=Runnable::run;
        Object config=host.loadClass("t92").getDeclaredConstructors()[0].newInstance(
            context,null,null,migrations,Collections.emptyList(),false,host.loadClass("bf9").getField("a").get(null),
            executor,executor,null,true,false,Collections.emptySet(),null,null,null,
            Collections.emptyList(),Collections.emptyList(),false,driver,dispatcher);
        Object delegate=invoke(db,"c",new Class<?>[0]);
        Object compat=host.loadClass("bp3").getConstructor(int.class,Object.class,Class.class,String.class,String.class,int.class,byte.class)
            .newInstance(2,db,host.loadClass("ff9"),"compatTransactionCoroutineExecute",
                "compatTransactionCoroutineExecute(Landroidx/room/RoomDatabase;Lkotlin/jvm/functions/Function1;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;",1,(byte)1);
        Object manager=host.loadClass("xi6").getConstructor(host.loadClass("t92"),host.loadClass("if9"),host.loadClass("bp3"))
            .newInstance(config,delegate,compat);
        set(db,base,"e",manager);
        set(db,base,"f",invoke(db,"b",new Class<?>[0]));
        Object scope=host.loadClass("rt0").getMethod("a",host.loadClass("e32")).invoke(null,dispatcher);
        set(db,base,"a",scope); set(db,base,"b",dispatcher); set(db,base,"c",executor);
        set(db,base,"d",host.loadClass("qwb").getConstructor(java.util.concurrent.Executor.class).newInstance(executor));
        Object dao=invoke(db,"m",new Class<?>[0]);
        transaction(dao,()->{
            exec("INSERT INTO books (id,uuid,uid,title,subtitle,author,cover,size,uri,`group`,created,cfi_version,embedded_fonts,epubcfi,chapter,progress,total,listening_total,finished,updated,pinned_at,cloud_id,backup_type,backup_id,backup_code,publisher,content_downloaded) " +
                "VALUES (7,'qa',1,'qa','','','',0,'',0,1,1,0,'','',0,0,0,0,1,0,0,10,'','','',1)");
            sync(refsTable,Collections.singletonList(ref(2,"original")));
            sync(chapterTable,Collections.singletonList(chapter(1,"original")));
        });
        try {
            transaction(dao,()->{
                sync(refsTable,Arrays.asList(ref(2,"changed"),ref(4,"new")));
                sync(chapterTable,Collections.emptyList());
            });
            throw new AssertionError("Invalid catalog transaction succeeded");
        } catch (IllegalArgumentException expected) { }
        transaction(dao,()->{
            check(query("SELECT href FROM book_item_ref WHERE book_id=7").equals(Collections.singletonList(Collections.singletonList("original"))),"Room did not roll back first table");
            check(query("SELECT title FROM book_chapter WHERE book_id=7").equals(Collections.singletonList(Collections.singletonList("original"))),"Room changed second table on failure");
            sync(refsTable,Arrays.asList(ref(2,"original"),ref(4,"new")));
            sync(chapterTable,Arrays.asList(chapter(1,"original"),chapter(2,"new")));
        });
        transaction(dao,()->{
            check(query("SELECT COUNT(*) FROM book_item_ref WHERE book_id=7",0).get(0).get(0).equals(2L),"Room ref commit missing");
            check(query("SELECT COUNT(*) FROM book_chapter WHERE book_id=7",0).get(0).get(0).equals(2L),"Room chapter commit missing");
        });
        System.out.println("CATALOG_NATIVE_ROOM_ATOMICITY_PASS");
        Class<?> function0=module.loadClass("kotlin.jvm.functions.Function0");
        Object emptyProvider=Proxy.newProxyInstance(module,new Class<?>[]{function0},(p,m,a)->null);
        Class<?> driveClass=module.loadClass("com.reamicro.fix.hook.WebDavDriveHook");
        Object drive=driveClass.getConstructor(ClassLoader.class,function0,function0,function0)
            .newInstance(host,emptyProvider,emptyProvider,emptyProvider);
        Object nativeRef=host.loadClass("ck0").getConstructor(int.class,int.class,long.class,long.class,long.class,String.class,String.class,String.class)
            .newInstance(2,1,0L,7L,100L,"chapter-2","original","");
        Object nativeChapter=host.loadClass("hi0").getConstructor(int.class,int.class,long.class,long.class,String.class,String.class,String.class,String.class)
            .newInstance(1,0,0L,7L,"original","Text/1.xhtml","","cfi:1");
        Method synchronize=module.loadClass("com.reamicro.fix.hook.WebDavDriveHook_CatalogUpdateKt")
            .getMethod("syncOnlineCatalogAtomically",driveClass,Object.class,Object.class,long.class,List.class,List.class);
        Object chapterDao=invoke(db,"k",new Class<?>[0]);
        synchronize.invoke(null,drive,dao,chapterDao,7L,Collections.singletonList(nativeRef),Collections.singletonList(nativeChapter));
        synchronize.invoke(null,drive,dao,chapterDao,7L,Collections.singletonList(nativeRef),Collections.singletonList(nativeChapter));
        transaction(dao,()->{
            check(query("SELECT href FROM book_item_ref WHERE book_id=7").equals(Collections.singletonList(Collections.singletonList("original"))),"Native ref projection failed");
            check(query("SELECT epubcfi FROM book_chapter WHERE book_id=7").equals(Collections.singletonList(Collections.singletonList("cfi:1"))),"Native chapter projection failed");
        });
        System.out.println("CATALOG_NATIVE_UPDATE_BRIDGE_PASS");
        observeOtherBook(db,dao);
    }
    static void observeOtherBook(Object db,Object dao) throws Exception {
        transaction(dao,()->{
            exec("INSERT INTO books (id,uuid,uid,title,subtitle,author,cover,size,uri,`group`,created,cfi_version,embedded_fonts,epubcfi,chapter,progress,total,listening_total,finished,updated,pinned_at,cloud_id,backup_type,backup_id,backup_code,publisher,content_downloaded) " +
                "VALUES (8,'qa-other',1,'qa','','','',0,'',0,1,1,0,'','',0,0,0,0,1,0,0,10,'','','',1)");
            exec("INSERT INTO book_chapter (book_id,play_order,title,href,anchor,epubcfi,parent_order) VALUES (8,1,'other','other','','other',0)");
        });
        AtomicInteger queries=new AtomicInteger(), deliveries=new AtomicInteger();
        AtomicReference<Throwable> failure=new AtomicReference<>();
        CountDownLatch initial=new CountDownLatch(1), changed=new CountDownLatch(1), invalidated=new CountDownLatch(1);
        Class<?> function=host.loadClass("kotlin.jvm.functions.Function1");
        Object read=Proxy.newProxyInstance(host,new Class<?>[]{function},(p,m,a)->{
            Object connection=invoke(runtime,"connection",new Class<?>[]{Object.class},a[0]);
            Object rows=invoke(connection,"query",new Class<?>[]{String.class,List.class,Set.class},
                "SELECT id,title FROM book_chapter WHERE book_id=7 ORDER BY play_order",Collections.emptyList(),Collections.singleton(0));
            if(queries.incrementAndGet()>1) invalidated.countDown();
            return rows;
        });
        Object flow=host.loadClass("t71").getMethod("w",host.loadClass("cf9"),String[].class,function)
            .invoke(null,db,new String[]{"book_chapter"},read);
        Class<?> collectorType=host.loadClass("pl3");
        Object sink=Proxy.newProxyInstance(host,new Class<?>[]{collectorType},(p,m,a)->{
            if(deliveries.incrementAndGet()==1) initial.countDown(); else changed.countDown();
            return host.loadClass("e7c").getField("a").get(null);
        });
        Class<?> guard=module.loadClass("com.reamicro.fix.hook.ReaderCatalogFlowGuard");
        Object filtered=guard.getMethod("collector",ClassLoader.class,Object.class).invoke(Rc3ProtocolProbe.singleton(guard),host,sink);
        Method collect=flow.getClass().getMethod("b",collectorType,host.loadClass("n12"));
        Class<?> coroutines=module.loadClass("com.reamicro.fix.core.HostCoroutines");
        Thread worker=new Thread(()->{
            try {
                coroutines.getMethod("invokeTimed",ClassLoader.class,Method.class,Object.class,Long.class,Object[].class)
                    .invoke(Rc3ProtocolProbe.singleton(coroutines),host,collect,flow,5000L,new Object[]{filtered});
            } catch(Throwable error) {
                Throwable cause=error instanceof InvocationTargetException ? error.getCause():error;
                if(!(cause instanceof CancellationException)) failure.set(cause);
            }
        },"CatalogObserverProbe");
        worker.start();
        check(initial.await(2,TimeUnit.SECONDS),"Room initial emission missing: "+failure.get());
        transaction(dao,()->exec("UPDATE book_chapter SET title='other-updated' WHERE book_id=8"));
        check(invalidated.await(2,TimeUnit.SECONDS),"Other-book write did not invalidate native query");
        Thread.sleep(150);
        check(deliveries.get()==1,"Other book rebuilt current reader catalog");
        transaction(dao,()->exec("UPDATE book_chapter SET title='current-updated' WHERE book_id=7 AND play_order=1"));
        check(changed.await(2,TimeUnit.SECONDS),"Current-book update was suppressed");
        worker.join(6500);
        check(!worker.isAlive() && failure.get()==null,"Room observer cancellation failed: "+failure.get());
        check(deliveries.get()==2,"Unexpected catalog delivery count "+deliveries.get());
        System.out.println("CATALOG_OTHER_BOOK_INVALIDATION_PASS queries="+queries.get()+" deliveries="+deliveries.get());
    }
    public static void main(String[] args) throws Exception {
        Rc3ProtocolProbe.setup(args); host=Rc3ProtocolProbe.host; module=Rc3ProtocolProbe.module;
        File lib = new File(args[2], "libsqliteJni.so");
        try (ZipFile zip=new ZipFile(args[0]); InputStream in=zip.getInputStream(zip.getEntry("lib/arm64-v8a/libsqliteJni.so")); FileOutputStream out=new FileOutputStream(lib)) {
            byte[] buffer=new byte[65536]; int n; while((n=in.read(buffer))>=0) out.write(buffer,0,n);
        }
        System.setProperty("androidx.sqlite.driver.bundled.path",lib.getParent());
        System.setProperty("androidx.sqlite.driver.bundled.name",lib.getName());
        runtime=module.loadClass("com.reamicro.fix.core.Host234CatalogRuntime").getConstructor(ClassLoader.class).newInstance(host);
        sqlType=module.loadClass("com.reamicro.fix.core.CatalogSql");
        sync=Rc3ProtocolProbe.singleton(module.loadClass("com.reamicro.fix.core.CatalogTableSync"));
        tableType=module.loadClass("com.reamicro.fix.core.CatalogTableSync$Table");
        refsTable=tableType.getField("ITEM_REF").get(null); chapterTable=tableType.getField("CHAPTER").get(null);
        Object driver=host.loadClass("zw0").getConstructor(byte.class).newInstance((byte)0);
        Object connection=invoke(driver,"k",new Class<?>[]{String.class},":memory:");
        try {
            sql=invoke(runtime,"connection",new Class<?>[]{Object.class},connection);
            catalog(); metadata(); collector();
        } finally { ((AutoCloseable)connection).close(); }
        room(args[2]);
        System.out.println("CATALOG_UPDATE_PROBE_PASS");
        System.exit(0);
    }
}
