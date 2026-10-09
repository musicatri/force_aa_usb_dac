package dev.usbdac;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;

/** Runtime discovery supports framework variants without fixed strategy ids. */
final class StrategyAccess {
    private final Method method;
    private final Object receiver;
    private final Object[] arguments;
    final String description;

    private StrategyAccess(Method method, Object receiver, Object[] arguments) {
        this.method = method;
        this.receiver = receiver;
        this.arguments = arguments;
        this.description = method.toGenericString();
    }
    static StrategyAccess resolve(Class<?> serviceApi, Object service, Class<?> strategyClass)
            throws NoSuchMethodException {
        try {
            return new StrategyAccess(serviceApi.getMethod("getAudioProductStrategies"),
                service,new Object[0]);
        } catch (NoSuchMethodException missingNoArg) {
            try {
                // false includes all strategies; callers separately skip internal/uplink names.
                return new StrategyAccess(serviceApi.getMethod("getAudioProductStrategies",boolean.class),
                    service,new Object[]{Boolean.FALSE});
            } catch (NoSuchMethodException missingBoolean) {
                Method nativeGetter = strategyClass.getMethod("getAudioProductStrategies");
                if (!Modifier.isStatic(nativeGetter.getModifiers()))
                    throw new NoSuchMethodException("AudioProductStrategy getter is not static");
                return new StrategyAccess(nativeGetter,null,new Object[0]);
            }
        }
    }
    List<?> read() throws Exception {
        Object value = method.invoke(receiver,arguments);
        if (!(value instanceof List<?>) || ((List<?>)value).isEmpty())
            throw new IllegalStateException("No audio strategies returned by " + description);
        return (List<?>) value;
    }
}
