package dev.usbdac;
import java.nio.file.*;
import java.util.*;
public final class RoutingDumpTest {
    static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        String audio = new String(Files.readAllBytes(Paths.get(args[0])), "UTF-8");
        String policy = new String(Files.readAllBytes(Paths.get(args[1])), "UTF-8");
        Map<String,RoutingDump.Mix> mixes = RoutingDump.parse(audio,policy,10101);
        require(mixes.size()==2,"Two Android Auto mixes from sanitized fixtures");
        RoutingDump.Mix music = mixes.get("-100:ap:1mixp:0");
        require(music.rate==48000 && music.channels==12 && !music.blocked(),"Media format and rules");
        require(mixes.get("-100:ap:1mixp:1").usages.contains("USAGE_ASSISTANCE_NAVIGATION_GUIDANCE"),"Navigation capture");
        require(RoutingDump.parse(audio,policy,9999).isEmpty(),"Reject another owner's mixes");
        require(RoutingDump.parse(audio,policy,-1).isEmpty(),"Reject unknown package UID");
        require(RoutingDump.parse(audio,"permission denied",10101).isEmpty(),"Reject missing native confirmation");
        String blocked = policy.replace("RULE_MATCH_ATTRIBUTE_USAGE AUDIO_USAGE_MEDIA","RULE_MATCH_UID 2147483647")
            .replace("    - Criterion 1: RULE_MATCH_ATTRIBUTE_USAGE AUDIO_USAGE_GAME\n","");
        require(RoutingDump.parse(audio,blocked,10101).get(music.address).blocked(),"Recognize owned marker");
        String changed = policy.replace("RULE_MATCH_ATTRIBUTE_USAGE AUDIO_USAGE_MEDIA","RULE_MATCH_UID 12345");
        require(!RoutingDump.parse(audio,changed,10101).containsKey(music.address),"Reject unfamiliar native rule changes");
        require(RoutingDump.parse(audio.replace("-100:ap:1","42:ap:9"),policy.replace("-100:ap:1","42:ap:9"),10101).size()==2,"Discover new AA registration");
        String unrelated = audio.replace("u/pid:10101/100 with config:reg:-100:ap:1","u/pid:10999/100 with config:reg:-100:ap:1");
        require(RoutingDump.parse(unrelated,policy,10101).isEmpty(),"Do not identify by usage alone");
        require(RoutingDump.nativeMixes(blocked).get(music.address).blocked(),"Restore identity survives registration event rotation");
        require(RoutingDump.nativeMixes(policy).get(music.address).generation.equals(music.generation),"Native registration generation");
        if (args.length >= 4) {
            String lineageAudio = new String(Files.readAllBytes(Paths.get(args[2])), "UTF-8");
            String lineagePolicy = new String(Files.readAllBytes(Paths.get(args[3])), "UTF-8");
            Map<String,RoutingDump.Mix> lineage = RoutingDump.parse(lineageAudio,lineagePolicy,10102);
            require(lineage.size()==2,"LineageOS media/navigation mix discovery");
            require(lineage.get("-200:ap:2mixp:0").rate==48000,"LineageOS media rate");
            require(lineage.get("-200:ap:2mixp:1").rate==16000,"LineageOS navigation rate");
            require(RoutingDump.parse(lineageAudio,lineagePolicy,10101).isEmpty(),"Do not reuse OxygenOS UID on LineageOS");
            System.out.println("PASS: LineageOS captures and changed Android Auto UID/registration");
        }
        System.out.println("PASS: sanitized media/navigation routes, ownership, native matching, marker recovery, foreign changes, dynamic registration");
    }
}
