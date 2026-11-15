package com.reamicro.fix.xposed;

import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Executable;

/** The exception boundary belongs to this invocation, rather than an emulated legacy API. */
final class HookInvocation {
    interface FailureReporter {
        void report(String phase, Executable executable, Throwable error);
    }

    private static volatile FailureReporter reporter = (phase, executable, error) -> {};

    static void setFailureReporter(FailureReporter next) {
        reporter = next;
    }

    static Object intercept(HookCallback callback, XposedInterface.Chain chain) throws Throwable {
        Object[] inputs = chain.getArgs().toArray();
        HookCallback.Call call = new HookCallback.Call(
                chain.getExecutable(), chain.getThisObject(), inputs.clone());
        try {
            callback.before(call);
            if (!call.isIntercepted()) validateArguments(call, inputs);
        } catch (Throwable error) {
            // A callback may fail after changing one slot. Restore the full slot array so the
            // original method does not receive a mixture of original and partially edited inputs.
            System.arraycopy(inputs, 0, call.args, 0, inputs.length);
            call.resetOutcome();
            report("before", call.method, error);
        }

        if (!call.isIntercepted()) {
            try {
                call.restoreOutcome(chain.proceed(call.args), null);
            } catch (Throwable error) {
                call.restoreOutcome(null, error);
            }
        }

        Object result = call.getResult();
        Throwable failure = call.getThrowable();
        try {
            callback.after(call);
        } catch (Throwable error) {
            call.restoreOutcome(result, failure);
            report("after", call.method, error);
        }
        if (call.getThrowable() != null) throw call.getThrowable();
        return call.getResult();
    }

    private static void validateArguments(HookCallback.Call call, Object[] inputs) {
        Class<?>[] parameters = null;
        for (int index = 0; index < inputs.length; index++) {
            if (inputs[index] == call.args[index]) continue;
            if (parameters == null) parameters = call.method.getParameterTypes();
            if (!accepts(parameters[index], call.args[index])) {
                throw new IllegalArgumentException("Invalid hook argument " + index + " for " + call.method);
            }
        }
    }

    static boolean accepts(Class<?> type, Object value) {
        if (!type.isPrimitive()) return value == null || type.isInstance(value);
        if (type == boolean.class) return value instanceof Boolean;
        if (type == byte.class) return value instanceof Byte;
        if (type == short.class) return value instanceof Short;
        if (type == char.class) return value instanceof Character;
        if (type == int.class) return value instanceof Integer;
        if (type == long.class) return value instanceof Long;
        if (type == float.class) return value instanceof Float;
        if (type == double.class) return value instanceof Double;
        return type == void.class;
    }

    private static void report(String phase, Executable executable, Throwable error) {
        try {
            reporter.report(phase, executable, error);
        } catch (Throwable loggingError) {
            // Logging must not turn a recovered callback failure into a second host exception.
            if (loggingError != error) error.addSuppressed(loggingError);
        }
    }
}
