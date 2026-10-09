#!/system/bin/sh
MODDIR=${0%/*}
OUT="$MODDIR/diagnostics"
mkdir -p "$OUT"
chmod 0700 "$OUT"
{
    cat "$MODDIR/module.prop"
    getprop ro.build.version.release
    getprop ro.build.version.sdk
    getprop ro.lineage.version
    getprop ro.build.fingerprint
} > "$OUT/build.txt"
sh "$MODDIR/probe.sh" > "$OUT/probe.txt" 2>&1
cp "$MODDIR/daemon.log" "$OUT/daemon.log"
dumpsys audio > "$OUT/audio.txt"
dumpsys media.audio_policy > "$OUT/policy.txt"
logcat -b all -d -t 1500 > "$OUT/logcat.txt"
chmod 0600 "$OUT"/*
echo "Diagnostics saved to $OUT. Run while Android Auto and USB are connected and audio is playing."
