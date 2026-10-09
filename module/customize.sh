#!/system/bin/sh
[ "$(getprop ro.build.version.sdk)" -eq 36 ] || abort "Requires Android 16 (API 36)."
ui_print "USB DAC + wireless Android Auto routing daemon"
ui_print "Turn OFF Developer options > Disable USB audio routing."
[ -f "$MODPATH/stop" ] && rm -f "$MODPATH/stop"
ui_print "Install, reboot, connect DAC, then inspect daemon.log."
set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/service.sh" 0 0 0755
set_perm "$MODPATH/action.sh" 0 0 0755
set_perm "$MODPATH/probe.sh" 0 0 0755
set_perm "$MODPATH/diagnose.sh" 0 0 0755
