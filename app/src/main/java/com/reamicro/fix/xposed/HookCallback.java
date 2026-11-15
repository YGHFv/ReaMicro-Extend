package com.reamicro.fix.xposed;

import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * A synchronous API 102 interceptor with an optional before/after pair.
 *
 * The invocation never exposes or retains the framework Chain. Only this interceptor decides when
 * to proceed, so even a failing after callback cannot invoke the host method a second time.
 */
public abstract class HookCallback implements XposedInterface.Hooker {
    public final int priority;

    protected HookCallback() {
        this(XposedInterface.PRIORITY_DEFAULT);
    }

    protected HookCallback(int priority) {
        this.priority = priority;
    }

    protected void before(Call call) throws Throwable {
    }

    protected void after(Call call) throws Throwable {
    }

    @Override
    public final Object intercept(XposedInterface.Chain chain) throws Throwable {
        return HookInvocation.intercept(this, chain);
    }

    /**
     * State shared by before and after for this invocation only. Input slots may be changed; the
     * receiver and executable cannot be redirected accidentally. Extras do not escape into Chain.
     */
    public static final class Call {
        public final Executable method;
        public final Object thisObject;
        public final Object[] args;

        private Object result;
        private Throwable throwable;
        private boolean intercepted;
        private Map<String, Object> extras;

        Call(Executable method, Object thisObject, Object[] args) {
            this.method = method;
            this.thisObject = thisObject;
            this.args = args;
        }

        public Object getResult() {
            return result;
        }

        public void setResult(Object result) {
            // Reject a broken replacement inside the callback boundary, before ART attempts to
            // unbox/cast it. Constructors and void methods ignore their interceptor's return value.
            if (method instanceof Method) {
                Class<?> type = ((Method) method).getReturnType();
                if (type != void.class && !HookInvocation.accepts(type, result)) {
                    throw new IllegalArgumentException("Invalid hook result for " + method);
                }
            }
            this.result = result;
            this.throwable = null;
            this.intercepted = true;
        }

        public Throwable getThrowable() {
            return throwable;
        }

        /** Deliberate task cancellation and business failures must propagate to the host caller. */
        public void setThrowable(Throwable throwable) {
            if (throwable == null) {
                throw new IllegalArgumentException("A hook failure must not be null");
            }
            this.throwable = throwable;
            this.result = null;
            this.intercepted = true;
        }

        public Object getObjectExtra(String key) {
            return extras == null ? null : extras.get(key);
        }

        public void setObjectExtra(String key, Object value) {
            if (extras == null) extras = new HashMap<>();
            extras.put(key, value);
        }

        boolean isIntercepted() {
            return intercepted;
        }

        void resetOutcome() {
            restoreOutcome(null, null);
            intercepted = false;
        }

        void restoreOutcome(Object result, Throwable throwable) {
            this.result = result;
            this.throwable = throwable;
        }
    }
}
