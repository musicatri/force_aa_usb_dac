#!/system/bin/sh
MODDIR=${0%/*}
# Stop gracefully and restore module-owned routing preferences.
touch "$MODDIR/stop"
echo "Stop requested. Reboot after deleting $MODDIR/stop to resume."
tail -n 20 "$MODDIR/daemon.log"
