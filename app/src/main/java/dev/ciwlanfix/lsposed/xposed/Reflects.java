package dev.ciwlanfix.lsposed.xposed;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;

import de.robv.android.xposed.XposedHelpers;

final class Reflects {
    private Reflects() {}

    static Class<?> find(ClassLoader cl, String name) {
        return XposedHelpers.findClass(name, cl);
    }

    static Class<?> findOrNull(ClassLoader cl, String name) {
        try {
            return XposedHelpers.findClass(name, cl);
        } catch (Throwable t) {
            return null;
        }
    }

    static Object getField(Object target, String name) {
        if (target == null) {
            return null;
        }
        Field f = findField(target.getClass(), name);
        if (f == null) {
            throw new IllegalStateException("field not found: " + target.getClass().getName() + "." + name);
        }
        try {
            f.setAccessible(true);
            return f.get(target);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static Object getFieldOrNull(Object target, String name) {
        try {
            return getField(target, name);
        } catch (Throwable t) {
            return null;
        }
    }

    static void setField(Object target, String name, Object value) {
        Field f = findField(target.getClass(), name);
        if (f == null) {
            throw new IllegalStateException("field not found: " + target.getClass().getName() + "." + name);
        }
        try {
            f.setAccessible(true);
            f.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static Field findField(Class<?> cls, String name) {
        Class<?> c = cls;
        while (c != null) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                c = c.getSuperclass();
            }
        }
        return null;
    }

    static Object call(Object target, String name, Object... args) {
        Method m = match(target.getClass(), name, args);
        if (m == null) {
            throw new IllegalStateException(
                    "method not found: " + target.getClass().getName() + "." + name + argsToString(args));
        }
        try {
            m.setAccessible(true);
            return m.invoke(target, args);
        } catch (Exception e) {
            throw new RuntimeException("invoke " + m, e);
        }
    }

    static Object callOrNull(Object target, String name, Object... args) {
        try {
            return call(target, name, args);
        } catch (Throwable t) {
            LogX.e("call failed " + name, t);
            return null;
        }
    }

    static Object callStatic(Class<?> cls, String name, Object... args) {
        Method m = match(cls, name, args);
        if (m == null) {
            throw new IllegalStateException("static method not found: " + cls.getName() + "." + name + argsToString(args));
        }
        try {
            m.setAccessible(true);
            return m.invoke(null, args);
        } catch (Exception e) {
            throw new RuntimeException("invoke " + m, e);
        }
    }

    static Object newInstance(Class<?> cls, Object... args) {
        Constructor<?> ctor = matchCtor(cls, args);
        if (ctor == null) {
            throw new IllegalStateException("ctor not found: " + cls.getName() + argsToString(args));
        }
        try {
            ctor.setAccessible(true);
            return ctor.newInstance(args);
        } catch (Exception e) {
            throw new RuntimeException("new " + cls.getName(), e);
        }
    }

    static Method match(Class<?> cls, String name, Object[] args) {
        Method best = null;
        for (Method m : cls.getMethods()) {
            if (!m.getName().equals(name)) {
                continue;
            }
            if (compatible(m.getParameterTypes(), args)) {
                best = m;
                break;
            }
        }
        if (best != null) {
            return best;
        }
        Class<?> c = cls;
        while (c != null) {
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals(name)) {
                    continue;
                }
                if (compatible(m.getParameterTypes(), args)) {
                    return m;
                }
            }
            c = c.getSuperclass();
        }
        return null;
    }

    static Constructor<?> matchCtor(Class<?> cls, Object[] args) {
        for (Constructor<?> ctor : cls.getDeclaredConstructors()) {
            if (compatible(ctor.getParameterTypes(), args)) {
                return ctor;
            }
        }
        return null;
    }

    static boolean compatible(Class<?>[] types, Object[] args) {
        if (args == null) {
            return types.length == 0;
        }
        if (types.length != args.length) {
            return false;
        }
        for (int i = 0; i < types.length; i++) {
            if (!compatibleOne(types[i], args[i])) {
                return false;
            }
        }
        return true;
    }

    static boolean compatibleOne(Class<?> type, Object arg) {
        if (arg == null) {
            return !type.isPrimitive();
        }
        if (type.isPrimitive()) {
            if (type == int.class) {
                return arg instanceof Integer;
            }
            if (type == boolean.class) {
                return arg instanceof Boolean;
            }
            if (type == long.class) {
                return arg instanceof Long;
            }
            if (type == float.class) {
                return arg instanceof Float;
            }
            if (type == double.class) {
                return arg instanceof Double;
            }
            if (type == short.class) {
                return arg instanceof Short;
            }
            if (type == byte.class) {
                return arg instanceof Byte;
            }
            if (type == char.class) {
                return arg instanceof Character;
            }
            return false;
        }
        return type.isInstance(arg);
    }

    static String argsToString(Object[] args) {
        return Arrays.toString(args);
    }

    static void dumpMethods(Class<?> cls, String needle) {
        if (cls == null) {
            return;
        }
        String n = needle == null ? "" : needle.toLowerCase();
        for (Method m : cls.getDeclaredMethods()) {
            if (n.isEmpty() || m.getName().toLowerCase().contains(n)) {
                LogX.i("dump " + cls.getName() + "." + m.getName() + Arrays.toString(m.getParameterTypes())
                        + " -> " + m.getReturnType().getName());
            }
        }
    }
}
