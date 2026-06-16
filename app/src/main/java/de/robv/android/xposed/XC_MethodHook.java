package de.robv.android.xposed;

import de.robv.android.xposed.callbacks.XCallback;
import java.lang.reflect.Executable;
import java.util.HashMap;
import java.util.Map;

public class XC_MethodHook {
    public final int priority;

    public XC_MethodHook() {
        this(XCallback.PRIORITY_DEFAULT);
    }

    public XC_MethodHook(int priority) {
        this.priority = priority;
    }

    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
    }

    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
    }

    public final void callBeforeHookedMethod(MethodHookParam param) throws Throwable {
        beforeHookedMethod(param);
    }

    public final void callAfterHookedMethod(MethodHookParam param) throws Throwable {
        afterHookedMethod(param);
    }

    public static class MethodHookParam {
        public Executable method;
        public Object thisObject;
        public Object[] args;

        private Object result;
        private Throwable throwable;
        private boolean returnEarly;
        private final Map<String, Object> extras = new HashMap<>();

        public MethodHookParam(Executable method, Object thisObject, Object[] args) {
            this.method = method;
            this.thisObject = thisObject;
            this.args = args;
        }

        public Object getResult() {
            return result;
        }

        public void setResult(Object result) {
            this.result = result;
            this.throwable = null;
            this.returnEarly = true;
        }

        public Throwable getThrowable() {
            return throwable;
        }

        public void setThrowable(Throwable throwable) {
            this.throwable = throwable;
            this.result = null;
            this.returnEarly = true;
        }

        public Object getObjectExtra(String key) {
            return extras.get(key);
        }

        public void setObjectExtra(String key, Object value) {
            extras.put(key, value);
        }

        public boolean isReturnEarly() {
            return returnEarly;
        }

        public void setResultFromOriginal(Object result) {
            this.result = result;
            this.throwable = null;
        }

        public void setThrowableFromOriginal(Throwable throwable) {
            this.throwable = throwable;
            this.result = null;
        }
    }
}
