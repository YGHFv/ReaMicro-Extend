import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import org.json.*;

/** 独立进程跑全量 DexKit；缓存只写到显式测试目录，不替换宿主运行中的扫描结果。 */
public final class AutomaticScanProbe {
    static Object get(Object value,String method) throws Exception {
        return value.getClass().getMethod(method).invoke(value);
    }
    static void save(File file,Object value) throws Exception {
        Files.write(file.toPath(),value.toString().getBytes(StandardCharsets.UTF_8));
    }
    static void selectionContracts(Map<?,?> classes,Map<?,?> methods) throws Exception {
        String owner="org.epub.ui.EpubSelectionController";
        Class<?> controller=Rc3ProtocolProbe.host.loadClass((String)classes.get(owner));
        Method getter=controller.getDeclaredMethod((String)methods.get(owner+"#getDraggingHandle"));
        Class<?> visual=Rc3ProtocolProbe.host.loadClass((String)classes.get("org.epub.ui.SelectionHandleVisual"));
        Method drag=controller.getDeclaredMethod((String)methods.get(owner+"#setDraggingHandle"),getter.getReturnType());
        Method paint=controller.getDeclaredMethod((String)methods.get(owner+"#setDragHandleVisual"),visual);
        controller.getDeclaredMethod((String)methods.get(owner+"#startHandleDrag"),getter.getReturnType(),visual);
        Rc3ProtocolProbe.check(drag.getReturnType()==void.class && paint.getReturnType()==void.class,
            "Selection setters must return void");
        Rc3ProtocolProbe.check(getter.getReturnType()!=visual,"Dragging enum must not be visual state");
        System.out.println("SELECTION_SETTER_CONTRACTS_PASS");
    }
    public static void main(String[] args) throws Exception {
        Rc3ProtocolProbe.setup(args);
        ClassLoader module=Rc3ProtocolProbe.module;
        File root=new File(args[2]).getCanonicalFile();
        if(!root.getPath().startsWith("/data/local/tmp/reamicro-scan70-")) throw new IllegalArgumentException("Unsafe probe cache directory");
        JSONArray targets=new JSONArray(new String(Files.readAllBytes(new File(args[3]).toPath()),StandardCharsets.UTF_8));
        Class<?> scanner=module.loadClass("com.reamicro.fix.core.HostAutoScanner");
        Object report=scanner.getMethod("scan",String.class,String.class,boolean.class)
            .invoke(Rc3ProtocolProbe.singleton(scanner),args[0],root.getPath(),true);
        if(report==null) throw new AssertionError("No scan report");
        Rc3ProtocolProbe.check(targets.length()==(Integer)get(report,"getTotal"),"Incomplete expected-target coverage");
        System.out.println(get(report,"summary"));
        List<?> failures=(List<?>)get(report,"getFailures");
        save(new File(root,"failures.json"),new JSONArray(failures));
        Object parsed=get(report,"getProfile");
        if(parsed==null) throw new AssertionError("No generated profile: "+failures);
        Map<?,?> classes=(Map<?,?>)get(parsed,"getClasses"), aliases=(Map<?,?>)get(parsed,"getTypeAliases");
        Map<?,?> methods=(Map<?,?>)get(parsed,"getMethods"), owners=(Map<?,?>)get(parsed,"getMethodOwners");
        JSONArray mapping=new JSONArray(), mismatches=new JSONArray();
        for(int i=0;i<targets.length();i++) {
            JSONObject target=targets.getJSONObject(i);
            String key=target.getString("id");
            boolean isClass=target.getString("kind").equals("class");
            Object actual=isClass?(classes.containsKey(key)?classes.get(key):aliases.get(key)):
                (owners.get(key)==null || methods.get(key)==null?null:owners.get(key)+"#"+methods.get(key));
            String expected=isClass?target.getString("owner"):target.getString("owner")+"#"+target.getString("name");
            JSONObject item=new JSONObject().put("id",key).put("expected",expected).put("actual",actual==null?JSONObject.NULL:actual);
            mapping.put(item);
            if(actual!=null && !expected.equals(actual)) mismatches.put(item);
        }
        save(new File(root,"mappings.json"),mapping); save(new File(root,"mismatches.json"),mismatches);
        System.out.println("SCAN_MISLOCATIONS="+mismatches.length());
        if(failures.isEmpty() && Rc3ProtocolProbe.PROFILE.equals("2.3.4-b1")) selectionContracts(classes,methods);
        if(args.length>4 && args[4].equals("audit")) {
            Class<?> kit=module.loadClass("com.reamicro.fix.core.HostDexKit");
            Method open=Arrays.stream(kit.getDeclaredMethods()).filter(m->m.getName().startsWith("openBridge")).findFirst().get();
            Object bridge=open.invoke(Rc3ProtocolProbe.singleton(kit),args[0],root.getPath());
            JSONArray records=new JSONArray();
            try {
                Method data=bridge.getClass().getMethod("getMethodData",String.class);
                for(int i=0;i<targets.length();i++) {
                    JSONObject target=targets.getJSONObject(i);
                    if(!target.getBoolean("failed") || !target.getString("kind").equals("method")) continue;
                    Object method=data.invoke(bridge,target.getString("descriptor"));
                    JSONObject item=new JSONObject().put("id",target.getString("id")).put("descriptor",get(method,"getDescriptor"))
                        .put("opcodes",new JSONArray((Collection<?>)get(method,"getOpCodes")))
                        .put("strings",new JSONArray((Collection<?>)get(method,"getUsingStrings")));
                    JSONArray calls=new JSONArray();
                    for(Object called:(Iterable<?>)get(method,"getInvokes")) calls.put(get(called,"getDescriptor"));
                    item.put("calls",calls);
                    records.put(item);
                }
            } finally { ((AutoCloseable)bridge).close(); }
            save(new File(root,"method-audit.json"),records);
        }
        System.out.println("SCAN_FAILURES="+failures.size());
        boolean allowPartial=Arrays.asList(args).contains("--allow-partial");
        System.exit(mismatches.length()==0 && (failures.isEmpty() || allowPartial)?0:2);
    }
}
