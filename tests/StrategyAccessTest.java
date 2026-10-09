package dev.usbdac;

import java.lang.reflect.InvocationTargetException;
import java.util.*;

public final class StrategyAccessTest {
    public static class NoArgService {
        public List<String> getAudioProductStrategies() { return Arrays.asList("service-noarg"); }
    }
    public static class BooleanService {
        public List<String> getAudioProductStrategies(boolean filterInternal) {
            if (filterInternal) throw new AssertionError("Need all strategies before name filtering");
            return Arrays.asList("service-boolean");
        }
    }
    public static class NativeOnlyService { }
    public static class NativeStrategies {
        public static List<String> getAudioProductStrategies() { return Arrays.asList("native"); }
    }
    public static class MissingNativeStrategies { }
    public static class EmptyService {
        public List<String> getAudioProductStrategies() { return Collections.emptyList(); }
    }
    public static class DeniedService {
        public List<String> getAudioProductStrategies() { throw new SecurityException("denied"); }
    }
    static void require(boolean value, String why) { if (!value) throw new AssertionError(why); }
    public static void main(String[] args) throws Exception {
        require(StrategyAccess.resolve(NoArgService.class,new NoArgService(),NativeStrategies.class)
            .read().equals(Arrays.asList("service-noarg")),"No-argument binder API");
        require(StrategyAccess.resolve(BooleanService.class,new BooleanService(),NativeStrategies.class)
            .read().equals(Arrays.asList("service-boolean")),"Boolean binder variant");
        require(StrategyAccess.resolve(NativeOnlyService.class,new NativeOnlyService(),NativeStrategies.class)
            .read().equals(Arrays.asList("native")),"Lineage/older framework native getter fallback");
        try {
            StrategyAccess.resolve(NativeOnlyService.class,new NativeOnlyService(),MissingNativeStrategies.class);
            throw new AssertionError("Missing API must fail during preflight");
        } catch (NoSuchMethodException expected) { }
        try {
            StrategyAccess.resolve(EmptyService.class,new EmptyService(),NativeStrategies.class).read();
            throw new AssertionError("Empty discovery must fail before routing mutations");
        } catch (IllegalStateException expected) { }
        try {
            StrategyAccess.resolve(DeniedService.class,new DeniedService(),NativeStrategies.class).read();
            throw new AssertionError("Permission failure must remain visible");
        } catch (InvocationTargetException expected) {
            require(expected.getCause() instanceof SecurityException,"Preserve actual service failure");
        }
        System.out.println("PASS: noarg binder, boolean binder, missing-binder native fallback, unsupported API, empty list, permission failure");
    }
}
