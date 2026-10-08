package com.reamicro.fix.xposed;

import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class XposedHelpers {
    private XposedHelpers() {
    }

    public static Class<?> findClass(String className, ClassLoader classLoader) throws ClassNotFoundException {
        return Class.forName(className, false,
                classLoader != null ? classLoader : XposedHelpers.class.getClassLoader());
    }

    public static XposedInterface.HookHandle findAndHookMethod(
            String className,
            ClassLoader classLoader,
            String methodName,
            Object... parameterTypesAndCallback
    ) throws ClassNotFoundException {
        return findAndHookMethod(findClass(className, classLoader), methodName, parameterTypesAndCallback);
    }

    public static XposedInterface.HookHandle findAndHookMethod(
            Class<?> clazz,
            String methodName,
            Object... parameterTypesAndCallback
    ) {
        if (parameterTypesAndCallback.length == 0
                || !(parameterTypesAndCallback[parameterTypesAndCallback.length - 1] instanceof XC_MethodHook)) {
            throw new IllegalArgumentException("last argument must be XC_MethodHook");
        }
        XC_MethodHook callback = (XC_MethodHook) parameterTypesAndCallback[parameterTypesAndCallback.length - 1];
        Class<?>[] parameterTypes = new Class<?>[parameterTypesAndCallback.length - 1];
        for (int i = 0; i < parameterTypes.length; i++) {
            parameterTypes[i] = resolveParameterType(parameterTypesAndCallback[i], clazz.getClassLoader());
        }
        Method method = findMethodExact(clazz, methodName, parameterTypes);
        return XposedBridge.INSTANCE.hookMethod(method, callback);
    }

    public static XposedInterface.HookHandle findAndHookConstructor(
            Class<?> clazz,
            Object... parameterTypesAndCallback
    ) {
        if (parameterTypesAndCallback.length == 0
                || !(parameterTypesAndCallback[parameterTypesAndCallback.length - 1] instanceof XC_MethodHook)) {
            throw new IllegalArgumentException("last argument must be XC_MethodHook");
        }
        XC_MethodHook callback = (XC_MethodHook) parameterTypesAndCallback[parameterTypesAndCallback.length - 1];
        Class<?>[] parameterTypes = new Class<?>[parameterTypesAndCallback.length - 1];
        for (int i = 0; i < parameterTypes.length; i++) {
            parameterTypes[i] = resolveParameterType(parameterTypesAndCallback[i], clazz.getClassLoader());
        }
        try {
            return XposedBridge.INSTANCE.hookMethod(clazz.getDeclaredConstructor(parameterTypes), callback);
        } catch (NoSuchMethodException e) {
            throw new NoSuchMethodError(clazz.getName() + "#<init>");
        }
    }

    public static Object callMethod(Object target, String methodName, Object... args) throws Exception {
        if (target == null) throw new IllegalArgumentException("target must not be null");
        Class<?> clazz = target instanceof Class<?> ? (Class<?>) target : target.getClass();
        Method method = findMethodBestMatch(clazz, methodName, target instanceof Class<?>, args);
        return method.invoke(target instanceof Class<?> ? null : target, args);
    }

    public static Object callStaticMethod(Class<?> clazz, String methodName, Object... args) throws Exception {
        Method method = findMethodBestMatch(clazz, methodName, true, args);
        return method.invoke(null, args);
    }

    public static Field findField(Class<?> clazz, String fieldName) {
        Class<?> current = clazz;
        while (current != null) {
            try {
                Field field = current.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldError(clazz.getName() + "#" + fieldName);
    }

    public static Object getObjectField(Object target, String fieldName) throws IllegalAccessException {
        return findField(target.getClass(), fieldName).get(target);
    }

    public static void setObjectField(Object target, String fieldName, Object value) throws IllegalAccessException {
        findField(target.getClass(), fieldName).set(target, value);
    }

    public static Object getStaticObjectField(Class<?> clazz, String fieldName) throws IllegalAccessException {
        return findField(clazz, fieldName).get(null);
    }

    public static void setStaticObjectField(Class<?> clazz, String fieldName, Object value) throws IllegalAccessException {
        findField(clazz, fieldName).set(null, value);
    }

    public static int getIntField(Object target, String fieldName) throws IllegalAccessException {
        return ((Number) findField(target.getClass(), fieldName).get(target)).intValue();
    }

    public static void setIntField(Object target, String fieldName, int value) throws IllegalAccessException {
        findField(target.getClass(), fieldName).setInt(target, value);
    }

    public static boolean getBooleanField(Object target, String fieldName) throws IllegalAccessException {
        return findField(target.getClass(), fieldName).getBoolean(target);
    }

    public static void setBooleanField(Object target, String fieldName, boolean value) throws IllegalAccessException {
        findField(target.getClass(), fieldName).setBoolean(target, value);
    }

    public static Method findMethodExact(Class<?> clazz, String methodName, Class<?>... parameterTypes) {
        Method method = findExactOrNull(clazz, methodName, parameterTypes);
        if (method == null) throw new NoSuchMethodError(clazz.getName() + "#" + methodName);
        method.setAccessible(true);
        return method;
    }

    private static Method findExactOrNull(Class<?> clazz, String name, Class<?>[] types) {
        for (Class<?> current = clazz; current != null; current = current.getSuperclass()) {
            try {
                return current.getDeclaredMethod(name, types);
            } catch (NoSuchMethodException ignored) {

            }
        }
        try {
            return clazz.getMethod(name, types);
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }

    public static Method findMethodBestMatch(Class<?> clazz, String methodName, Object... args) {
        return findMethodBestMatch(clazz, methodName, false, args);
    }

    private static Method findMethodBestMatch(Class<?> clazz, String name, boolean staticOnly, Object[] args) {

        if (args.length == 0) {
            Method exact = findExactOrNull(clazz, name, new Class<?>[0]);
            if (exact != null && !exact.isBridge() && (!staticOnly || Modifier.isStatic(exact.getModifiers()))) {
                exact.setAccessible(true);
                return exact;
            }
        }
        List<Method> best = new ArrayList<>();
        int bestScore = Integer.MAX_VALUE;
        for (Method method : matchingMethods(clazz, name, args.length, staticOnly)) {
            int score = distanceScore(method.getParameterTypes(), args);
            if (score == Integer.MAX_VALUE || score > bestScore) continue;
            if (score < bestScore) {
                best.clear();
                bestScore = score;
            }
            best.add(method);
        }
        if (best.isEmpty()) throw new NoSuchMethodError(clazz.getName() + "#" + name);
        Method selected = mostSpecific(best);
        if (selected == null) throw new IllegalArgumentException("ambiguous method: " + clazz.getName() + "#" + name);
        selected.setAccessible(true);
        return selected;
    }

    private static Method mostSpecific(List<Method> methods) {
        for (Method candidate : methods) {
            boolean dominates = true;
            for (Method other : methods) {
                if (candidate != other && !moreSpecific(candidate.getParameterTypes(), other.getParameterTypes())) {
                    dominates = false;
                    break;
                }
            }
            if (dominates) return candidate;
        }
        return null;
    }

    private static boolean moreSpecific(Class<?>[] first, Class<?>[] second) {
        for (int i = 0; i < first.length; i++) {
            Class<?> a = first[i], b = second[i];
            if (a == b) continue;
            if (a.isPrimitive() && b.isPrimitive()) {
                if (!canWiden(boxed(a), b)) return false;
            } else if (!a.isPrimitive() && !b.isPrimitive()) {
                if (!b.isAssignableFrom(a)) return false;
            } else if (a.isPrimitive() || a != boxed(b)) {
                return false;
            }
        }
        return true;
    }

    private static Class<?> resolveParameterType(Object value, ClassLoader classLoader) {
        if (value instanceof Class<?>) return (Class<?>) value;
        if (value instanceof String) {
            try {
                return findClass((String) value, classLoader);
            } catch (ClassNotFoundException e) {
                throw new IllegalArgumentException("class not found: " + value, e);
            }
        }
        throw new IllegalArgumentException("unsupported parameter type: " + value);
    }

    private static Collection<Method> matchingMethods(Class<?> clazz, String name, int count, boolean staticOnly) {
        Map<List<Class<?>>, Method> result = new LinkedHashMap<>();
        for (Class<?> current = clazz; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (method.getName().equals(name)) addMatching(result, method, count, staticOnly);
            }
        }
        for (Method method : clazz.getMethods()) {
            if (method.getDeclaringClass().isInterface() && method.getName().equals(name)) {
                addMatching(result, method, count, staticOnly);
            }
        }
        return result.values();
    }

    private static void addMatching(Map<List<Class<?>>, Method> result, Method method,
                                    int count, boolean staticOnly) {
        if (method.getParameterCount() != count ||
                (staticOnly && !Modifier.isStatic(method.getModifiers()))) return;

        List<Class<?>> key = Arrays.asList(method.getParameterTypes());
        Method previous = result.get(key);
        if (previous == null || (previous.getDeclaringClass() == method.getDeclaringClass() &&
                previous.isBridge() && !method.isBridge())) result.put(key, method);
    }

    private static int distanceScore(Class<?>[] types, Object[] args) {
        int score = 0;
        for (int i = 0; i < types.length; i++) {
            Class<?> type = types[i];
            Object arg = args[i];
            if (arg == null) {
                if (type.isPrimitive()) return Integer.MAX_VALUE;
                score++;
            } else if (boxed(type) == arg.getClass()) {

            } else if (!type.isPrimitive() && type.isInstance(arg)) {
                score++;
            } else if (type.isPrimitive() && canWiden(arg.getClass(), type)) {

                score += types.length + 1;
            } else {
                return Integer.MAX_VALUE;
            }
        }
        return score;
    }

    private static boolean canWiden(Class<?> from, Class<?> to) {
        if (from == Byte.class && to == Short.TYPE) return true;
        if (from == Byte.class || from == Short.class || from == Character.class) {
            return to == Integer.TYPE || to == Long.TYPE || to == Float.TYPE || to == Double.TYPE;
        }
        if (from == Integer.class) return to == Long.TYPE || to == Float.TYPE || to == Double.TYPE;
        if (from == Long.class) return to == Float.TYPE || to == Double.TYPE;
        return from == Float.class && to == Double.TYPE;
    }

    private static Class<?> boxed(Class<?> type) {
        if (!type.isPrimitive()) return type;
        if (type == Boolean.TYPE) return Boolean.class;
        if (type == Byte.TYPE) return Byte.class;
        if (type == Character.TYPE) return Character.class;
        if (type == Short.TYPE) return Short.class;
        if (type == Integer.TYPE) return Integer.class;
        if (type == Long.TYPE) return Long.class;
        if (type == Float.TYPE) return Float.class;
        if (type == Double.TYPE) return Double.class;
        if (type == Void.TYPE) return Void.class;
        return type;
    }
}
