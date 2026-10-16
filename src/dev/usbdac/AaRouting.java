package dev.usbdac;

import android.content.AttributionSource;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.os.Binder;
import android.os.IBinder;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Native mix rule updates preserve Android Auto's registered capture endpoints. */
final class AaRouting {
    private final Map<String, RoutingDump.Mix> originals = new LinkedHashMap<>();
    private final Map<String, String> generations = new HashMap<>();
    private final Class<?> ruleClass = Class.forName("android.media.audiopolicy.AudioMixingRule");
    private final Class<?> mixClass = Class.forName("android.media.audiopolicy.AudioMix");
    private final Method update;
    private final IBinder callToken = new Binder();
    private final AttributionSource attribution = new AttributionSource.Builder(0)
        .setPid(android.os.Process.myPid()).build();
    private final Method communicationSet;
    private final Method communicationGet;
    private boolean callOwned;
    private Set<String> observedSubmixes = new HashSet<>();
    private Set<String> verifiedSubmixes = new HashSet<>();
    private int aaUid = -1;
    private long lastUidRead;

    AaRouting() throws Exception {
        update = Class.forName("android.media.AudioSystem").getMethod("updatePolicyMixes",
            Array.newInstance(mixClass,0).getClass(),Array.newInstance(ruleClass,0).getClass());
        Class<?> api = Class.forName("android.media.IAudioService");
        Method setter;
        try { setter = api.getMethod("setCommunicationDevice",IBinder.class,int.class,AttributionSource.class); }
        catch (NoSuchMethodException e) {
            setter = api.getMethod("setCommunicationDevice",IBinder.class,
                Class.forName("android.media.AudioDeviceAttributes"),AttributionSource.class);
        }
        communicationSet = setter;
        communicationGet = api.getMethod("getCommunicationDevice");
    }
    static String command(String... args) throws Exception {
        Process process = new ProcessBuilder(args).redirectErrorStream(true).start();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try (InputStream input = process.getInputStream()) {
                byte[] buffer = new byte[4096]; int count;
                while ((count = input.read(buffer)) >= 0) {
                    if (bytes.size() + count > 2 * 1024 * 1024) { process.destroy(); break; }
                    bytes.write(buffer,0,count);
                }
            } catch (IOException ignored) { }
        });
        reader.start();
        if (!process.waitFor(8, TimeUnit.SECONDS)) { process.destroyForcibly(); reader.join(1000); throw new IOException("Command timed out: " + args[0]); }
        reader.join(1000);
        if (reader.isAlive() || process.exitValue()!=0) throw new IOException("Command failed: " + Arrays.toString(args));
        return bytes.toString("UTF-8");
    }
    private int uid() throws Exception {
        if (aaUid < 0 || System.currentTimeMillis()-lastUidRead > 60000) {
            String list = command("/system/bin/cmd","package","list","packages","-U","--user","0","com.google.android.projection.gearhead");
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                "(?m)^package:com\\.google\\.android\\.projection\\.gearhead uid:(\\d+)\\s*$").matcher(list);
            aaUid = matcher.find() ? Integer.parseInt(matcher.group(1)) : -1;
            lastUidRead = System.currentTimeMillis();
        }
        return aaUid;
    }
    private Map<String, RoutingDump.Mix> snapshot() throws Exception {
        return RoutingDump.parse(command("/system/bin/dumpsys","audio"),
            command("/system/bin/dumpsys","media.audio_policy"),uid());
    }
    /** Read-only observation. Stable sessions need only output-device enumeration. */
    boolean connectionPresent() throws Exception {
        AudioDeviceInfo[] outputs = (AudioDeviceInfo[]) UsbDac.enumerate.invoke(null,
            android.media.AudioManager.GET_DEVICES_OUTPUTS);
        Set<String> addresses = new HashSet<>();
        for (AudioDeviceInfo device : outputs) {
            if (device.getType() == AudioDeviceInfo.TYPE_REMOTE_SUBMIX) addresses.add(device.getAddress());
        }
        if (addresses.isEmpty()) {
            observedSubmixes.clear(); verifiedSubmixes.clear();
            return false;
        }
        if (!addresses.equals(observedSubmixes) || verifiedSubmixes.isEmpty()) {
            Map<String,RoutingDump.Mix> mixes = snapshot();
            Set<String> verified = new HashSet<>(mixes.keySet());
            verified.retainAll(addresses);
            // Commit observation only after successful reads; errors are not disconnections.
            observedSubmixes = addresses;
            verifiedSubmixes = verified;
        }
        return !verifiedSubmixes.isEmpty();
    }
    private Object rule(RoutingDump.Mix mix, boolean block) throws Exception {
        Class<?> builderClass = Class.forName("android.media.audiopolicy.AudioMixingRule$Builder");
        Object builder = builderClass.getConstructor().newInstance();
        if (block) {
            // This UID cannot belong to an installed Android app. Only this rule is used.
            builderClass.getMethod("addMixRule",int.class,Object.class)
                .invoke(builder,4,Integer.valueOf(RoutingDump.BLOCK_UID));
        } else {
            Method add = builderClass.getMethod("addRule",AudioAttributes.class,int.class);
            for (String name : mix.usages) {
                int usage = AudioAttributes.class.getField(name).getInt(null);
                add.invoke(builder,new AudioAttributes.Builder().setUsage(usage).build(),1);
            }
        }
        return builderClass.getMethod("build").invoke(builder);
    }
    private void change(RoutingDump.Mix mix, boolean block) throws Exception {
        Object old = rule(mix,false), replacement = rule(mix,block);
        Class<?> builderClass = Class.forName("android.media.audiopolicy.AudioMix$Builder");
        Object builder = builderClass.getConstructor(ruleClass).newInstance(old);
        builderClass.getMethod("setRouteFlags",int.class).invoke(builder,2);
        builderClass.getMethod("setFormat",AudioFormat.class).invoke(builder,new AudioFormat.Builder()
            .setSampleRate(mix.rate).setEncoding(mix.encoding).setChannelMask(mix.channels).build());
        Object audioMix = builderClass.getMethod("build").invoke(builder);
        Method registration = mixClass.getDeclaredMethod("setRegistration",String.class);
        registration.setAccessible(true); registration.invoke(audioMix,mix.address);
        Object mixes = Array.newInstance(mixClass,1), rules = Array.newInstance(ruleClass,1);
        Array.set(mixes,0,audioMix); Array.set(rules,0,replacement);
        UsbDac.check(update.invoke(null,mixes,rules));
    }
    void applyMixesOnce() throws Exception {
        Map<String, RoutingDump.Mix> mixes = snapshot();
        if (aaUid < 0) throw new IllegalStateException("Android Auto package UID unavailable");
        if (mixes.isEmpty()) throw new IllegalStateException("Android Auto capture mixes disappeared before the single routing attempt");
        for (RoutingDump.Mix mix : mixes.values()) {
            if (mix.blocked()) {
                if (!originals.containsKey(mix.address)) {
                    // Recover a previous daemon's marker using the unchanged framework config.
                    originals.put(mix.address,mix); generations.put(mix.address,mix.generation);
                }
                continue;
            }
            change(mix,true);
            originals.put(mix.address,mix); generations.put(mix.address,mix.generation);
            UsbDac.log("Android Auto capture bypass requested for " + mix.address + " " + mix.usages);
        }
        Map<String,RoutingDump.Mix> nativeNow = RoutingDump.nativeMixes(
            command("/system/bin/dumpsys","media.audio_policy"));
        for (String address : new ArrayList<>(originals.keySet())) {
            RoutingDump.Mix actual = nativeNow.get(address);
            if (actual == null || !actual.generation.equals(generations.get(address))) {
                originals.remove(address); generations.remove(address);
            }
        }
        for (RoutingDump.Mix mix : mixes.values()) {
            RoutingDump.Mix actual = nativeNow.get(mix.address);
            if (actual != null && actual.generation.equals(mix.generation) && !actual.blocked())
                throw new IllegalStateException("Native mix change did not stick: " + mix.address);
        }
        if (!mixes.isEmpty()) UsbDac.log("Verified native Android Auto capture rules no longer match installed app UIDs");
    }
    void communicationOnce(AudioDeviceInfo device, Object attributes) throws Exception {
        Object actual = communicationGet.invoke(UsbDac.audio);
        boolean matches = actual instanceof Integer ? ((Integer)actual) == device.getId() : attributes.equals(actual);
        if (matches) { UsbDac.log("Communication output reports USB port " + device.getId()); return; }
        Object selected = communicationSet.getParameterTypes()[1] == int.class ? Integer.valueOf(device.getId()) : attributes;
        Object accepted = communicationSet.invoke(UsbDac.audio,callToken,selected,attribution);
        if (Boolean.TRUE.equals(accepted)) {
            callOwned = true;
            UsbDac.log("USB communication request accepted once; actual call routing remains unverified");
        } else UsbDac.log("ROM rejected USB communication request; calls may remain on another device");
    }
    void restore() throws Exception {
        if (callOwned) {
            Object cleared = communicationSet.getParameterTypes()[1] == int.class ? Integer.valueOf(0) : null;
            communicationSet.invoke(UsbDac.audio,callToken,cleared,attribution);
            callOwned = false;
        }
        // Recover from unchanged framework config after a previous daemon was killed.
        Map<String,RoutingDump.Mix> candidates = new LinkedHashMap<>(originals);
        for (RoutingDump.Mix mix : snapshot().values()) {
            if (mix.blocked() && !candidates.containsKey(mix.address)) candidates.put(mix.address,mix);
        }
        Map<String,RoutingDump.Mix> nativeNow = RoutingDump.nativeMixes(
            command("/system/bin/dumpsys","media.audio_policy"));
        for (RoutingDump.Mix original : candidates.values()) {
            RoutingDump.Mix actual = nativeNow.get(original.address);
            if (actual != null && actual.generation.equals(original.generation) && actual.blocked()) {
                change(original,false);
                UsbDac.log("Restored Android Auto capture rules: " + original.address);
            }
            // A disappeared registration or another controller's rule must not be overwritten.
            originals.remove(original.address); generations.remove(original.address);
        }
    }
    void probe() throws Exception {
        for (RoutingDump.Mix mix : snapshot().values()) {
            System.out.println("Android Auto mix=" + mix.address + " usages=" + mix.usages
                + " nativeRules=" + mix.nativeRules);
        }
        System.out.println("Android Auto uid=" + aaUid + " communicationDevice=" + communicationGet.invoke(UsbDac.audio));
        System.out.println("communication setter=" + communicationSet);
    }
}
