import dalvik.system.PathClassLoader;
import java.lang.ref.WeakReference;
import java.lang.reflect.*;
import java.util.*;

/** 独立进程使用真实宿主类型；网络替身只记录请求并返回预制信封，不连接服务器、不读写用户书库。 */
public final class Manual234SourceContract {
    static ClassLoader module, host, source, fixture;
    static Class<?> hookType;
    static Object hook, settings;
    static void check(boolean value,String message) { if(!value) throw new AssertionError(message); }
    static Object singleton(Class<?> type) throws Exception { return type.getField("INSTANCE").get(null); }
    static Method privateMethod(String name,int arity) { return Manual233SourceContract.method(hookType,name,arity); }
    static Object invoke(String name,Object... args) throws Exception { return privateMethod(name,args.length).invoke(hook,args); }
    static Object call(Object target,String name,Object... args) throws Exception {
        return module.loadClass("com.reamicro.fix.xposed.XposedHelpers")
            .getMethod("callMethod",Object.class,String.class,Object[].class).invoke(null,target,name,args);
    }
    static Field field(Class<?> type,String logical) throws Exception {
        return (Field)module.loadClass("com.reamicro.fix.core.HostReflectionKt")
            .getMethod("hostField",Class.class,String.class).invoke(null,type,logical);
    }
    static void put(Object target,String logical,Object value) throws Exception { field(target.getClass(),logical).set(target,value); }
    static Object get(Object target,String logical) throws Exception { return field(target.getClass(),logical).get(target); }
    static void hookField(String name,Object value) throws Exception {
        Field field=hookType.getDeclaredField(name); field.setAccessible(true); field.set(hook,value);
    }
    static void createHook(String path) throws Exception {
        source=new PathClassLoader(path,module);
        hookType=source.loadClass("com.reamicro.fix.hook.ManualAssociationUiHook");
        settings=module.loadClass("com.reamicro.fix.settings.ModuleSettingsSnapshot").getConstructor().newInstance();
        put(settings,"associationManualEditEnabled",true);
        Class<?> f0=module.loadClass("kotlin.jvm.functions.Function0"), f2=module.loadClass("kotlin.jvm.functions.Function2");
        Object empty=Proxy.newProxyInstance(module,new Class<?>[]{f0},(p,m,a)->null);
        Object config=Proxy.newProxyInstance(module,new Class<?>[]{f0},(p,m,a)->settings);
        Object noop=Proxy.newProxyInstance(module,new Class<?>[]{f2},(p,m,a)->null);
        hook=hookType.getConstructor(ClassLoader.class,f0,f0,f0,f2).newInstance(host,empty,config,empty,noop);
    }
    static Object draft() throws Exception {
        Class<?> origin=module.loadClass("com.reamicro.fix.association.model.BookSource");
        Object companion=origin.getField("Companion").get(null);
        Object qidian=companion.getClass().getMethod("getQiDian").invoke(companion);
        return module.loadClass("com.reamicro.fix.association.model.ManualAssociationDraft")
            .getConstructor(String.class,String.class,String.class,origin,String.class,String.class,String.class)
            .newInstance("RM-Manual234-QA","QA Author","Offline fixture",qidian,"120","完结","https://example.invalid/cover.png");
    }
    static Object nativeBook() throws Exception {
        System.out.println("MANUAL_STAGE_BOOK_ALLOCATE");
        Class<?> type=Rc3ProtocolProbe.nativeClass("app.zhendong.reamicro.data.db.entity.Book");
        Constructor<?> constructor=Arrays.stream(type.getDeclaredConstructors())
            .filter(c->c.getParameterTypes()[0]==long.class).findFirst().get();
        Class<?>[] types=constructor.getParameterTypes();
        Object[] values=new Object[types.length];
        for(int i=0;i<types.length;i++) {
            if(types[i]==String.class) values[i]="";
            else if(types[i]==long.class) values[i]=0L;
            else if(types[i]==int.class) values[i]=0;
            else if(types[i]==float.class) values[i]=0f;
            else if(types[i]==boolean.class) values[i]=false;
            else throw new AssertionError("Unknown book constructor slot "+types[i]);
        }
        Object book=constructor.newInstance(values);
        put(book,"id",77L); put(book,"title","RM-Manual234-QA"); put(book,"author","QA Author");
        put(book,"uuid","00000000-0000-4000-8000-000000000077");
        put(book,"progress",0.375f);
        System.out.println("MANUAL_STAGE_BOOK_READY");
        return book;
    }
    static void entries() throws Exception {
        Class<?> abiType=module.loadClass("com.reamicro.fix.core.HostManualEditAbi");
        Object abi=abiType.getMethod("forProfile",String.class).invoke(singleton(abiType),"2.3.4-b1");
        Class<?> screen=host.loadClass((String)call(abi,"getPublishScreen"));
        screen.getDeclaredMethod((String)call(abi,"getScreenMethod"),Rc3ProtocolProbe.nativeClass("app.zhendong.reamicro.NavGraphScope"),
            long.class,Rc3ProtocolProbe.nativeClass("androidx.compose.runtime.Composer"),int.class);
        Class<?> top=host.loadClass((String)call(abi,"getTopBarContent"));
        byte branch=((Number)call(abi,"getTopBarBranch")).byteValue();
        Object lambda=top.getDeclaredConstructor(byte.class).newInstance(branch);
        check(top.getDeclaredField("a").getByte(lambda)==11,"Wrong publish top-bar branch");
        top.getDeclaredMethod("C",Object.class,Object.class);
        Rc3ProtocolProbe.nativeClass("app.zhendong.reamicro.repository.BookRepository").getDeclaredMethod(
            (String)call(abi,"getRelateMethod"),Rc3ProtocolProbe.nativeClass("app.zhendong.reamicro.data.db.entity.Book"),
            Rc3ProtocolProbe.nativeClass("app.zhendong.reamicro.data.search.ThirdPartyBook"),
            Rc3ProtocolProbe.nativeClass("ReaMicroHost.SuspendContinuation"));
        Class<?> uiAbi=module.loadClass("com.reamicro.fix.core.HostNativeUiAbi");
        for(boolean copy:new boolean[]{false,true}) {
            Object icon=uiAbi.getMethod("metadataIcon",String.class,boolean.class).invoke(singleton(uiAbi),"2.3.4-b1",copy);
            Object vector=host.loadClass((String)call(icon,"getFirst")).getDeclaredMethod((String)call(icon,"getSecond")).invoke(null);
            check(host.loadClass("kt4").isInstance(vector),"Wrong metadata icon type");
        }
        System.out.println("MANUAL_234_ENTRY_ABI_PASS");
    }
    static void preview(Object book) throws Exception {
        System.out.println("MANUAL_STAGE_PREVIEW_GROUPS");
        hookField("currentDraft",draft());
        List<?> groups=(List<?>)invoke("buildManualGroups");
        check(groups.size()==1,"Manual draft not converted to a group");
        Class<?> stateType=Rc3ProtocolProbe.nativeClass("app.zhendong.reamicro.ui.book.BookPublishUIState");
        Class<?> recommendation=Rc3ProtocolProbe.nativeClass("app.zhendong.reamicro.data.res.book.SearchCloudBookRes");
        Object recommended=recommendation.getConstructor().newInstance();
        Class<?> factoryType=source.loadClass("com.reamicro.fix.hook.ReaMicroThirdPartyBookFactory");
        Object factory=factoryType.getConstructor(ClassLoader.class).newInstance(host);
        Object nativeGroup=factoryType.getMethod("createThirdPartyGroup",String.class,List.class)
            .invoke(factory,"Native results",Collections.emptyList());
        Object state=stateType.getConstructor(book.getClass(),recommendation,List.class,boolean.class,boolean.class,boolean.class)
            .newInstance(book,recommended,Collections.singletonList(nativeGroup),true,true,true);
        System.out.println("MANUAL_STAGE_PREVIEW_APPEND");
        Object next=invoke("appendManualGroups",state,groups);
        check(next!=state,"Preview construction silently fell back");
        check(get(next,"book")==book && get(next,"recommend")==recommended,"Preview lost book or recommendation");
        for(String flag:new String[]{"searching","relating","isRelateSuccess"}) check(Boolean.TRUE.equals(get(next,flag)),"Lost flag "+flag);
        List<?> results=(List<?>)get(next,"thirdPartyBooks");
        check(results.size()==2 && results.get(1)==nativeGroup,"Native results overwritten");
        Object again=invoke("appendManualGroups",next,groups);
        check(((List<?>)get(again,"thirdPartyBooks")).size()==2 && again.equals(next),"Manual group duplicated or unstable equality");
        check(((List<?>)get(state,"thirdPartyBooks")).size()==1,"Original state mutated");
        Object manualGroup=results.get(0);
        Object candidate=((List<?>)get(manualGroup,"books")).get(0);
        Object event=host.loadClass("nm0").getDeclaredConstructor(candidate.getClass()).newInstance(candidate);
        check(host.loadClass("nm0").getDeclaredMethod("a").invoke(event)==candidate,"Native submit event lost candidate");
        put(settings,"associationManualEditEnabled",false);
        check(((List<?>)invoke("buildManualGroups")).isEmpty(),"Disabled feature still creates preview");
        Object removed=invoke("appendManualGroups",next,Collections.emptyList());
        check(((List<?>)get(removed,"thirdPartyBooks")).equals(Collections.singletonList(nativeGroup)),"Disabling dropped native results");
        put(settings,"associationManualEditEnabled",true);

        System.out.println("MANUAL_STAGE_MVI_CREATE");
        Class<?> vmType=fixture.loadClass("Manual234FakeViewModel");
        vmType.getField("initialState").set(null,state);
        Object vm=vmType.getConstructor().newInstance();
        vmType.getField("initialState").set(null,null);
        put(vm,"bookId",77L);
        System.out.println("MANUAL_STAGE_MVI_INJECT");
        invoke("injectManualGroup",vm);
        System.out.println("MANUAL_STAGE_MVI_CHECK");
        check(((List<?>)get(call(vm,"getCurrentState"),"thirdPartyBooks")).size()==2,"Native MVI update failed");
        hookField("viewModelRef",new WeakReference<>(vm)); hookField("releaseScreenBookId",77L);
        check(Boolean.TRUE.equals(invoke("isCurrentManualTarget",vm)),"Active screen rejected");
        hookField("releaseScreenBookId",78L);
        check(Boolean.FALSE.equals(invoke("isCurrentManualTarget",vm)),"Stale screen accepted");
        hookField("releaseScreenBookId",77L);
        hookType.getMethod("releaseForHotReload").invoke(hook);
        check(Boolean.FALSE.equals(invoke("isCurrentManualTarget",vm)),"Retired source still accepts old dialog");
        System.out.println("MANUAL_234_PREVIEW_MVI_FLAGS_NATIVE_EVENT_PASS");
        System.out.println("MANUAL_234_STALE_SCREEN_RETIREMENT_PASS");
    }
    static void cover(Object book) throws Exception {
        Class<?> apiType=fixture.loadClass("Manual234FakeApi"), repoType=fixture.loadClass("Manual234FakeRepository");
        Object api=apiType.getConstructor().newInstance(), repo=repoType.getConstructor().newInstance();
        repoType.getField("api").set(repo,api);
        Class<?> resultType=host.loadClass("b58"), envelopeType=host.loadClass("o83");
        Object result=resultType.getConstructor(int.class,long.class,Long.class,String.class,String.class)
            .newInstance(15,23477L,null,"fixture","");
        Constructor<?> envelope=envelopeType.getConstructor(Object.class,int.class,int.class,String.class);
        apiType.getField("response").set(api,envelope.newInstance(result,7,200,"ok"));
        Object id=invoke("postAssociationCover",repo,book,"https://example.invalid/cover.png");
        check(Long.valueOf(23477L).equals(id),"Wrong successful envelope data");
        Object request=apiType.getField("request").get(api);
        check("RM-Manual234-QA".equals(get(request,"name")),"Wrong cover request title");
        check("QA Author".equals(get(request,"author")),"Wrong cover request author");
        check("00000000000040008000000000000077".equals(get(request,"md5")),"Wrong UUID normalization");
        check("https://example.invalid/cover.png".equals(get(request,"cover")),"Wrong cover URL");
        check("".equals(get(request,"coverMd5")),"Unexpected cover hash");
        for(Object invalid:new Object[]{envelope.newInstance(result,7,500,"refused"),envelope.newInstance(null,7,200,"missing")}) {
            apiType.getField("response").set(api,invalid);
            boolean failed=false;
            try { invoke("postAssociationCover",repo,book,"https://example.invalid/cover.png"); }
            catch(InvocationTargetException expected) { failed=true; }
            check(failed,"Invalid API envelope silently accepted");
        }
        check(apiType.getField("calls").getInt(api)==3,"Unexpected request count");
        check(Float.valueOf(0.375f).equals(get(book,"progress")),"Cover helper modified reading progress");
        System.out.println("MANUAL_234_COVER_REQUEST_SUCCESS_FAILURE_PASS");
    }
    public static void main(String[] args) throws Exception {
        Manual233SourceContract.main(args);
        module=Rc3ProtocolProbe.module; host=Rc3ProtocolProbe.host;
        fixture=new PathClassLoader(args[4],host);
        createHook(args[3]); entries(); Object book=nativeBook(); preview(book); cover(book);
        System.out.println("MANUAL_234_SOURCE_CONTRACT_PASS");
        System.exit(0);
    }
}
