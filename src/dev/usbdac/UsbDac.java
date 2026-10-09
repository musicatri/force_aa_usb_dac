package dev.usbdac;

import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import java.io.File;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.lang.reflect.Method;
import java.util.*;

/** Root app_process daemon; no APK, framework injection, or vendor policy edits. */
public final class UsbDac {
    static Object audio;
    static Method get, set, remove, enumerate;
    static final Map<Integer, List<?>> saved = new HashMap<>();
    static final Map<Integer, List<?>> owned = new HashMap<>();
    static File dir;
    static final Map<String, Long> logged = new HashMap<>();
    static AaRouting aa;
    static StrategyAccess strategyAccess;
    static boolean restoredExtras = true;
    static void log(String s) {
        long now = System.currentTimeMillis();
        Long previous = logged.get(s);
        if (previous == null || now - previous > 60000) {
            System.out.println(new Date() + " UsbDac: " + s);
            logged.put(s, now);
            if (logged.size() > 100) logged.clear();
        }
    }
    static Object call(Method m, Object... args) throws Exception {
        return m.invoke(audio, args);
    }
    static void check(Object result) {
        if (!(result instanceof Integer) || ((Integer) result) != 0)
            throw new IllegalStateException("audio policy status=" + result);
    }
    static List<?> current(int id) throws Exception {
        return new ArrayList<>((List<?>) call(get, id));
    }
    static synchronized void restore() {
        if (aa != null && !restoredExtras) {
            try { aa.restore(); restoredExtras = true; }
            catch (Exception e) { log("Restore Android Auto/communication routes: " + e); }
        }
        for (Integer id : new ArrayList<>(saved.keySet())) {
            try {
                // Do not undo a newer preference from another controller.
                if (current(id).equals(owned.get(id))) {
                    List<?> old = saved.get(id);
                    if (old.isEmpty()) check(call(remove, id));
                    else check(call(set, id, old));
                }
                saved.remove(id);
                owned.remove(id);
            } catch (Exception e) { log("Restore strategy " + id + ": " + e); }
        }
    }
    static void connect() throws Exception {
        Object binder = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String.class).invoke(null, "audio");
        if (binder == null) throw new IllegalStateException("AudioService unavailable");
        audio = Class.forName("android.media.IAudioService$Stub")
            .getMethod("asInterface", IBinder.class).invoke(null, binder);
        Class<?> api = Class.forName("android.media.IAudioService");
        get = api.getMethod("getPreferredDevicesForStrategy", int.class);
        set = api.getMethod("setPreferredDevicesForStrategy", int.class, List.class);
        remove = api.getMethod("removePreferredDevicesForStrategy", int.class);
        enumerate = AudioManager.class.getMethod("getDevicesStatic", int.class);
        strategyAccess = StrategyAccess.resolve(api,audio,
            Class.forName("android.media.audiopolicy.AudioProductStrategy"));
        // Validate discovery before any policy-mix or device preference mutation.
        strategyAccess.read();
        log("Strategy discovery: " + strategyAccess.description);
    }
    static List<?> strategies() throws Exception {
        return strategyAccess.read();
    }
    static AudioDeviceInfo usb(String address) throws Exception {
        AudioDeviceInfo[] devices = (AudioDeviceInfo[]) enumerate.invoke(null, AudioManager.GET_DEVICES_OUTPUTS);
        List<AudioDeviceInfo> candidates = new ArrayList<>();
        for (AudioDeviceInfo d : devices) {
            int t = d.getType();
            if (d.isSink() && (t == AudioDeviceInfo.TYPE_USB_DEVICE || t == AudioDeviceInfo.TYPE_USB_HEADSET
                    || t == AudioDeviceInfo.TYPE_USB_ACCESSORY)
                    && (address.isEmpty() || address.equals(d.getAddress()))) candidates.add(d);
        }
        candidates.sort(Comparator.comparing(AudioDeviceInfo::getAddress).thenComparingInt(AudioDeviceInfo::getId));
        return candidates.isEmpty() ? null : candidates.get(0);
    }
    public static void main(String[] args) throws Exception {
        if (args.length == 0) throw new IllegalArgumentException("module directory required");
        dir = new File(args[0]);
        Looper.prepareMainLooper();
        // Applies only to this process; does not change global hidden-API settings.
        Class<?> vm = Class.forName("dalvik.system.VMRuntime");
        vm.getMethod("setHiddenApiExemptions", String[].class).invoke(
            vm.getMethod("getRuntime").invoke(null), (Object) new String[]{"L"});
        connect();
        aa = new AaRouting();
        if (args.length > 1 && args[1].equals("--probe")) {
            for (AudioDeviceInfo d : (AudioDeviceInfo[]) enumerate.invoke(null, AudioManager.GET_DEVICES_OUTPUTS))
                System.out.println("output type=" + d.getType() + " address=" + d.getAddress() + " name=" + d.getProductName());
            for (Object strategy : strategies()) {
                int id = (Integer) strategy.getClass().getMethod("getId").invoke(strategy);
                System.out.println("strategy=" + id + " preferred=" + current(id));
            }
            aa.probe();
            return;
        }
        RandomAccessFile lockFile = new RandomAccessFile(new File(dir,"daemon.lock"),"rw");
        FileLock lock = lockFile.getChannel().tryLock();
        if (lock == null) { log("Another daemon is already running"); return; }
        restoredExtras = false;
        Runtime.getRuntime().addShutdownHook(new Thread(UsbDac::restore));
        String address = args.length > 1 ? args[1] : "";
        log("Version 1.2.0 started; USB address filter=" + (address.isEmpty() ? "any" : address));
        int consecutiveErrors = 0;
        int ticks = 0;
        int lastPort = -1;
        try {
        while (true) {
            if (new File(dir, "disable").exists() || new File(dir, "remove").exists()
                    || new File(dir, "stop").exists()) {
                restore();
                if (saved.isEmpty() && restoredExtras) { log("Stopped; preferences restored"); return; }
                SystemClock.sleep(2000);
                continue;
            }
            try {
                AudioDeviceInfo device = usb(address);
                if (device == null) {
                    restore();
                    lastPort = -1;
                    log("No matching USB output; normal routing");
                } else {
                    restoredExtras = false;
                    if (device.getId() != lastPort || ticks % 3 == 0) aa.enforceMixes();
                    lastPort = device.getId();
                    Object attributes = Class.forName("android.media.AudioDeviceAttributes")
                        .getConstructor(AudioDeviceInfo.class).newInstance(device);
                    List<?> target = Collections.singletonList(attributes);
                    int applied = 0;
                    for (Object strategy : strategies()) {
                        String name = (String) strategy.getClass().getMethod("getName").invoke(strategy);
                        if (name.contains("REROUTING") || name.contains("PATCH") || name.contains("CALL_ASSISTANT")) continue;
                        int id = (Integer) strategy.getClass().getMethod("getId").invoke(strategy);
                        List<?> before = current(id);
                        if (!before.equals(target)) {
                            check(call(set, id, target));
                            if (!saved.containsKey(id)) saved.put(id, before);
                            owned.put(id, target);
                        }
                        applied++;
                    }
                    aa.communication(device, attributes);
                    if (applied == 0) throw new IllegalStateException("No audio strategies exposed");
                    log("Preferred USB " + device.getProductName() + " address=" + device.getAddress()
                        + " for " + applied + " strategies (policy preference, not verified playback)");
                }
                consecutiveErrors = 0;
            } catch (Exception e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                log("Routing error: " + cause);
                lastPort = -1; // Retry failed mix enforcement on the next poll, not only every third poll.
                if (++consecutiveErrors >= 5) {
                    restore();
                    throw new IllegalStateException("Repeated routing failures; inspect log and SELinux denials", cause);
                }
            }
            ticks++;
            SystemClock.sleep(2000);
        }
        } finally {
            restore();
            lock.release();
            lockFile.close();
        }
    }
}
