package dev.usbdac;

import java.util.*;
import java.util.regex.*;

/** Pure-Java parser: change only mixes tied to the verified Android Auto UID. */
public final class RoutingDump {
    public static final int BLOCK_UID = Integer.MAX_VALUE;
    public static final class Mix {
        public String address, generation;
        public int rate, encoding, channels;
        public List<String> usages = new ArrayList<>();
        public String nativeRules;
        public boolean blocked() {
            return nativeRules.equals("RULE_MATCH_UID " + BLOCK_UID);
        }
    }
    static String field(String block, String regex) {
        Matcher m = Pattern.compile(regex, Pattern.MULTILINE).matcher(block);
        if (!m.find()) throw new IllegalArgumentException("Missing field: " + regex);
        return m.group(1);
    }
    /** Native identity and criteria, independent of the finite registration event log. */
    public static Map<String, Mix> nativeMixes(String policy) {
        Map<String, Mix> result = new LinkedHashMap<>();
        Matcher blocks = Pattern.compile("(?ms)^  Audio Policy Mix \\d+ \\(([^)]+)\\):(.*?)(?=^  Audio Policy Mix \\d+|^ Audio sources|\\z)").matcher(policy);
        while (blocks.find()) {
            String block = blocks.group(2);
            if (!block.contains("mix type: MIX_TYPE_PLAYERS")
                || !block.contains("device type: AUDIO_DEVICE_OUT_REMOTE_SUBMIX")
                || !block.contains("Route Flags: MIX_ROUTE_FLAG_LOOP_BACK")) continue;
            Mix mix = new Mix();
            mix.address = field(block,"device address: ([^\\r\\n]+)").trim();
            mix.generation = blocks.group(1);
            List<String> rules = new ArrayList<>();
            Matcher criteria = Pattern.compile("Criterion \\d+: ([^\\r\\n]+)").matcher(block);
            while (criteria.find()) rules.add(criteria.group(1).trim());
            mix.nativeRules = String.join("|",rules);
            result.put(mix.address,mix);
        }
        return result;
    }
    public static Map<String, Mix> parse(String audio, String policy, int uid) {
        Map<String, Mix> result = new LinkedHashMap<>();
        if (uid < 0) return result;
        // Map registration prefix to the UID recorded by AudioService at registration.
        Map<String,Integer> owners = new HashMap<>();
        Matcher events = Pattern.compile("registerAudioPolicy[^\\n]*u/pid:(\\d+)/\\d+[^\\n]*config:reg:([^\\s]+)").matcher(audio);
        while (events.find()) owners.put(events.group(2), Integer.parseInt(events.group(1)));
        Matcher configs = Pattern.compile("(?ms)^android\\.media\\.audiopolicy\\.AudioPolicyConfig:\\s*\\n(.*?)(?=^android\\.media\\.audiopolicy\\.AudioPolicyConfig:|^Events log:|\\z)").matcher(audio);
        while (configs.find()) {
            String block = configs.group(1);
            Matcher header = Pattern.compile("^(\\d+) AudioMix, reg:([^\\s]+)",Pattern.MULTILINE).matcher(block);
            if (!header.find()) continue;
            String reg = header.group(2);
            if (!Integer.valueOf(uid).equals(owners.get(reg))) continue;
            Matcher sections = Pattern.compile("(?ms)^\\* route flags=(.*?)(?=^\\* route flags=|^ Uid Device Affinities:|\\z)").matcher(block);
            int index = 0;
            while (sections.find()) {
                String s = sections.group();
                String address = reg + "mixp:" + index++;
                // Conservative: support only the two usage-only loopback player mixes observed.
                if (!field(s,"route flags=(0x[0-9a-fA-F]+)").equals("0x2")) continue;
                if (!s.contains("specified mix type=0")) continue;
                if (s.contains("exclude ") || s.contains("match UID") || s.contains("match uid")
                        || s.contains("match user") || s.contains("match session")) continue;
                Mix mix = new Mix(); mix.address = address;
                mix.rate = Integer.parseInt(field(s,"rate=(\\d+)Hz"));
                mix.encoding = Integer.parseInt(field(s,"encoding=(\\d+)"));
                mix.channels = Integer.decode(field(s,"channels=(0x[0-9a-fA-F]+)"));
                Matcher usage = Pattern.compile("match usage (USAGE_[A-Z_]+)").matcher(s);
                while (usage.find()) mix.usages.add(usage.group(1));
                Set<String> names = new HashSet<>(mix.usages);
                boolean supported = names.equals(new HashSet<>(Arrays.asList("USAGE_MEDIA", "USAGE_GAME")))
                    || names.equals(new HashSet<>(Arrays.asList("USAGE_ASSISTANT", "USAGE_ASSISTANCE_NAVIGATION_GUIDANCE")));
                if (supported) result.put(address,mix);
            }
        }
        Set<String> found = new HashSet<>();
        for (Mix nativeMix : nativeMixes(policy).values()) {
            Mix mix = result.get(nativeMix.address);
            if (mix == null) continue;
            mix.generation = nativeMix.generation;
            mix.nativeRules = nativeMix.nativeRules;
            List<String> expected = new ArrayList<>();
            for (String usage : mix.usages) expected.add("RULE_MATCH_ATTRIBUTE_USAGE AUDIO_" + usage);
            if (mix.blocked() || new HashSet<>(Arrays.asList(mix.nativeRules.split("\\|")))
                    .equals(new HashSet<>(expected))) found.add(mix.address);
        }
        result.keySet().retainAll(found);
        return result;
    }
}
