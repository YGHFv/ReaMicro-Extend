import dalvik.system.PathClassLoader;
import java.lang.reflect.*;
import java.util.*;

/** 独立进程核对本地功能源规则与候选构造，不连接关联接口。 */
public final class Manual233SourceContract {
    static Method method(Class<?> type, String name, int count) {
        for (Method m : type.getDeclaredMethods()) if (m.getName().equals(name) && m.getParameterCount() == count) {
            m.setAccessible(true);
            return m;
        }
        throw new AssertionError(name);
    }
    static void check(boolean value, String name) {
        if (!value) throw new AssertionError(name);
    }
    public static void main(String[] args) throws Exception {
        Rc3ProtocolProbe.setup(args);
        ClassLoader module = Rc3ProtocolProbe.module;
        ClassLoader host = Rc3ProtocolProbe.host;
        ClassLoader source = new PathClassLoader(args[3], module);
        Class<?> feature = source.loadClass("com.reamicro.fix.external.source.ManualEditFeatureProvider");
        Object provider = feature.getConstructor().newInstance();
        Set<?> caps = (Set<?>) feature.getMethod("getCapabilities").invoke(provider);
        check(caps.containsAll(Arrays.asList("manual_edit", "manual_edit_host_233")), "source capability");
        Class<?> policy = module.loadClass("com.reamicro.fix.core.ExternalFeatureCompatibility");
        Object singleton = policy.getField("INSTANCE").get(null);
        check(policy.getMethod("disabledReason", String.class, Set.class, String.class)
            .invoke(singleton, "manual-edit", caps, "2.3.3") == null, "new source accepted");
        check(policy.getMethod("disabledReason", String.class, Set.class, String.class)
            .invoke(singleton, "manual-edit", Collections.singleton("manual_edit"), "2.3.3") != null, "old source still rejected");
        Class<?> f0 = Class.forName("kotlin.jvm.functions.Function0", true, module);
        Class<?> f2 = Class.forName("kotlin.jvm.functions.Function2", true, module);
        Object empty = Proxy.newProxyInstance(module, new Class<?>[]{f0}, (p,m,a)->null);
        Object noop = Proxy.newProxyInstance(module, new Class<?>[]{f2}, (p,m,a)->null);
        Class<?> hookType = source.loadClass("com.reamicro.fix.hook.ManualAssociationUiHook");
        Object hook = hookType.getConstructor(ClassLoader.class, f0, f0, f0, f2)
            .newInstance(host, empty, empty, empty, noop);
        Class<?> bookSource = module.loadClass("com.reamicro.fix.association.model.BookSource");
        Object companion = bookSource.getField("Companion").get(null);
        Object qidian = companion.getClass().getMethod("getQiDian").invoke(companion);
        Class<?> draftType = module.loadClass("com.reamicro.fix.association.model.ManualAssociationDraft");
        Constructor<?> ctor = draftType.getConstructor(String.class, String.class, String.class, bookSource,
            String.class, String.class, String.class);
        String[] names = {"title", "author", "intro", "cover", "words", "status"};
        String[] valid = {"ManualLocalFixture", "ReaMicro QA", "Task-owned intro", "120", "完结", "https://example.invalid/qa.png"};
        Method required = method(hookType, "requiredManualDraftError", 1);
        Object draft = ctor.newInstance(valid[0], valid[1], valid[2], qidian, valid[3], valid[4], valid[5]);
        check(required.invoke(hook, draft) == null, "valid fields");
        for (int slot = 0; slot < 6; slot++) {
            String[] fields = valid.clone();
            fields[slot] = " ";
            Object invalid = ctor.newInstance(fields[0], fields[1], fields[2], qidian, fields[3], fields[4], fields[5]);
            check(required.invoke(hook, invalid) != null, "missing slot " + slot);
        }
        check(method(hookType, "normalizeManualStatus", 1).invoke(hook, "已完结").equals("完结"), "finished status");
        Class<?> serviceType = module.loadClass("com.reamicro.fix.association.ManualAssociationService");
        Object service = serviceType.getConstructor().newInstance();
        Object validation = serviceType.getMethod("validate", draftType).invoke(service, draft);
        check((Boolean) validation.getClass().getMethod("isValid").invoke(validation), "source whitelist valid");
        Object unknown = bookSource.getConstructor(String.class, String.class, Integer.class)
            .newInstance("unlisted-local-test", "Unlisted", null);
        Object wrong = ctor.newInstance(valid[0], valid[1], valid[2], unknown, valid[3], valid[4], valid[5]);
        Object refused = serviceType.getMethod("validate", draftType).invoke(service, wrong);
        check(!(Boolean) refused.getClass().getMethod("isValid").invoke(refused), "source whitelist refused");
        Object longIntro = ctor.newInstance(valid[0], valid[1], String.join("", Collections.nCopies(2001, "x")),
            qidian, valid[3], valid[4], valid[5]);
        check(!(Boolean) serviceType.getMethod("validate", draftType).invoke(service, longIntro).getClass()
            .getMethod("isValid").invoke(serviceType.getMethod("validate", draftType).invoke(service, longIntro)), "intro 2000 limit");
        Object candidate = serviceType.getMethod("buildCandidate", draftType).invoke(service, draft);
        Class<?> factoryType = source.loadClass("com.reamicro.fix.hook.ReaMicroThirdPartyBookFactory");
        Object factory = factoryType.getConstructor(ClassLoader.class).newInstance(host);
        Object nativeBook = factoryType.getMethod("create", candidate.getClass(), String.class)
            .invoke(factory, candidate, "fallback-cover");
        Class<?> helper = module.loadClass("com.reamicro.fix.xposed.XposedHelpers");
        Method call = helper.getMethod("callMethod", Object.class, String.class, Object[].class);
        check(call.invoke(null, nativeBook, "getTitle", new Object[0]).equals(valid[0]), "candidate title");
        check(call.invoke(null, nativeBook, "getCover", new Object[0]).equals(valid[5]), "candidate cover");
        check(call.invoke(null, nativeBook, "getStatus", new Object[0]).equals(valid[4]), "candidate status");
        check(call.invoke(null, nativeBook, "getWords", new Object[0]).equals(valid[3]), "candidate words");
        System.out.println("MANUAL_SOURCE_REQUIRED_WHITELIST_LENGTH_STATUS_PASS");
        System.out.println("MANUAL_SOURCE_NATIVE_CANDIDATE_PASS");
        System.out.println("MANUAL_SOURCE_FAILURES=0");
    }
}
