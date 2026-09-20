#!/data/data/com.termux/files/usr/bin/sh
# On-device version of enable-head-tracking.sh. No laptop.
#
#   1. Open Root My Galaxy, get root
#   2. Grant Termux root in the KernelSU app, once
#   3. sh enable-head-tracking-termux.sh
#
# Restarting the sensors HAL takes system_server down with it, which kills every
# app including Termux. So this does not do the work itself: it hands a worker
# script to a detached root process, which is not managed by ActivityManager and
# therefore lives through the soft reboot. Termux exiting halfway is expected.
#
# Watch it with:  su -c 'cat /data/local/tmp/ht-enable.log'
set -u

LOG=/data/local/tmp/ht-enable.log
WORKER=/data/local/tmp/ht-enable-worker.sh

if ! su -c 'id' 2>/dev/null | grep -q 'uid=0'; then
    echo "No root. Run Root My Galaxy, then grant Termux in the KernelSU app." >&2
    exit 1
fi

cat > "$WORKER" <<'WORKER_EOF'
#!/system/bin/sh
# Runs as root, detached, so it survives system_server restarting.
exec >> /data/local/tmp/ht-enable.log 2>&1
echo "=== $(date) ==="

if grep -q dynamic_sensor_hal /vendor/etc/sensors/hals.conf; then
    echo "already mounted"
else
    cp /vendor/etc/sensors/hals.conf /data/local/tmp/hals.conf.backup
    cp /vendor/etc/sensors/hals.conf /data/local/tmp/hals.conf
    echo sensors.dynamic_sensor_hal.so >> /data/local/tmp/hals.conf
    chown root:root /data/local/tmp/hals.conf
    chmod 644 /data/local/tmp/hals.conf
    # Must carry the vendor label or the HAL is denied reading it.
    chcon u:object_r:vendor_configs_file:s0 /data/local/tmp/hals.conf
    mount -o bind /data/local/tmp/hals.conf /vendor/etc/sensors/hals.conf || {
        echo "bind mount failed"; exit 1; }
    echo "mounted:"; cat /vendor/etc/sensors/hals.conf

    echo "restarting sensors HAL, the phone will soft reboot"
    setprop ctl.restart vendor.sensors-hal-multihal
fi

echo "waiting for sensorservice"
i=0
while [ $i -lt 60 ]; do
    sleep 3
    if dumpsys sensorservice 2>/dev/null | head -1 | grep -q 'Captured at'; then
        echo "sensorservice back after $((i * 3))s"
        break
    fi
    i=$((i + 1))
done

# Undocumented, and the only lever: SensorService hard-codes head tracker access
# to system_server and audioserver with no permission that satisfies it. Root
# passes every permission check, so it can run this as well as shell can.
sleep 5
cmd sensorservice unrestrict-ht
echo "unrestrict-ht done"

if dumpsys sensorservice | grep -q head_tracker; then
    dumpsys sensorservice | grep 'head_tracker(37)'
    echo "HEAD TRACKING LIVE"
else
    echo "no head tracker. Connect the headphones and run this again."
fi
WORKER_EOF

chmod 755 "$WORKER"
: > "$LOG"

echo "handing off to a detached root process"
echo "Termux will be killed when the phone soft reboots. That is expected."
echo "Afterwards:  su -c 'cat $LOG'"

su -c "setsid sh $WORKER < /dev/null > /dev/null 2>&1 &"

sleep 4
cat "$LOG" 2>/dev/null || true
