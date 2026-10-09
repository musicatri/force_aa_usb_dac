#!/system/bin/sh
MODDIR=${0%/*}
# KernelSU executes this in the late-start stage.
while [ "$(getprop sys.boot_completed)" != "1" ]; do
    [ -f "$MODDIR/disable" ] && exit 0
    [ -f "$MODDIR/remove" ] && exit 0
    sleep 2
done
[ -f "$MODDIR/stop" ] && exit 0
USB_ADDRESS=""
[ -f "$MODDIR/config.sh" ] && . "$MODDIR/config.sh"
# A single daemon per boot, with rate-limited logging; failures are visible, not respawned forever.
[ -f "$MODDIR/daemon.log" ] && mv "$MODDIR/daemon.log" "$MODDIR/daemon.previous.log"
printf 'Starting USB DAC daemon\n' > "$MODDIR/daemon.log"
CLASSPATH="$MODDIR/daemon.jar" /system/bin/app_process /system/bin dev.usbdac.UsbDac "$MODDIR" "$USB_ADDRESS" >> "$MODDIR/daemon.log" 2>&1 &
