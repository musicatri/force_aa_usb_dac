#!/system/bin/sh
MODDIR=${0%/*}
CLASSPATH="$MODDIR/daemon.jar" /system/bin/app_process /system/bin dev.usbdac.UsbDac "$MODDIR" --probe
