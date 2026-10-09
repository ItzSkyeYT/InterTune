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

# The mount has to be made in the phone's own view of the file system, not in Termux's: -M asks
# for that. Where su does not know -M the worker checks for itself and says what to change.
if su -M -c 'id' 2>/dev/null | grep -q 'uid=0'; then
    SU="su -M"
else
    SU="su"
fi

# Only root and adb's shell may write under /data/local/tmp. So the worker is written in a
# folder of Termux's own and root copies it over: written there straight from Termux it was
# "Permission denied", and nothing ran. Started from a root shell it is staged beside itself.
if [ "$(id -u)" = "0" ]; then
    STAGE="$WORKER.staged"
else
    STAGE="${TMPDIR:-${HOME:-/data/data/com.termux/files/home}}/ht-enable-worker.staged"
fi

cat > "$STAGE" <<'WORKER_EOF'
#!/system/bin/sh
# Runs as root, detached, so it survives system_server restarting.
exec >> /data/local/tmp/ht-enable.log 2>&1
echo "=== $(date) ==="

# As the system sees the file, which is what the sensors HAL reads. A root shell started from
# an app has that app's view of the file system, and a mount made there is seen by nobody else:
# on 9 Oct 2026 a run said "mounted", the HAL went on reading the real file, and no head
# tracker ever came.
seen() { grep -q dynamic_sensor_hal /proc/1/root/vendor/etc/sensors/hals.conf 2>/dev/null; }

if seen; then
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
    if ! seen; then
        umount /vendor/etc/sensors/hals.conf 2>/dev/null
        echo "mounted, but only in this shell's own view: the system does not see it."
        echo "In the KernelSU app: Superuser, Termux, Mount namespace, choose Global. Then run this again."
        exit 1
    fi
    echo "mounted:"; cat /proc/1/root/vendor/etc/sensors/hals.conf

    echo "restarting sensors HAL, the phone will soft reboot"
    setprop ctl.restart vendor.sensors-hal-multihal
    RESTARTED=1
fi

up() { dumpsys sensorservice 2>/dev/null | head -1 | grep -q 'Captured at'; }

# The restart takes a moment to reach sensorservice. Asked three seconds after it, the
# service still answered, as the one about to go: "back after 0s". What was then set on
# it went down with it, and the head tracker was looked for before anything had come back.
if [ -n "${RESTARTED:-}" ]; then
    echo "waiting for sensorservice to go down"
    i=0
    while [ $i -lt 10 ] && up; do
        sleep 2
        i=$((i + 1))
    done
    if up; then
        echo "still up after 20s: the HAL restarted without taking it down"
    else
        echo "down after $((i * 2))s"
    fi
fi

echo "waiting for sensorservice"
i=0
while [ $i -lt 60 ]; do
    if up; then
        echo "sensorservice up after $((i * 3))s"
        break
    fi
    sleep 3
    i=$((i + 1))
done

# Undocumented, and the only lever: SensorService hard-codes head tracker access
# to system_server and audioserver with no permission that satisfies it. Root
# passes every permission check, so it can run this as well as shell can.
sleep 5
if cmd sensorservice unrestrict-ht; then
    echo "unrestrict-ht done"
else
    echo "unrestrict-ht FAILED: sensorservice did not take it"
fi

# A head tracker is only there while the headphones are connected, and after a soft reboot
# they take a while to come back. Looked for once, straight away, it was never there.
echo "looking for the head tracker, for up to a minute"
i=0
while [ $i -lt 20 ]; do
    if dumpsys sensorservice | grep -q head_tracker; then
        dumpsys sensorservice | grep 'head_tracker(37)'
        echo "HEAD TRACKING LIVE"
        exit 0
    fi
    sleep 3
    i=$((i + 1))
done
echo "no head tracker after a minute. The rest is in place: connect the headphones and"
echo "put them in your ears. If InterTune still shows none, run this again; it restarts nothing now."
WORKER_EOF

if [ ! -s "$STAGE" ]; then
    echo "Could not write $STAGE. Nothing was changed." >&2
    exit 1
fi
if ! su -c "cp '$STAGE' '$WORKER' && chmod 755 '$WORKER' && : > '$LOG' && chmod 644 '$LOG'"; then
    echo "Root could not put the worker in /data/local/tmp. Nothing was changed." >&2
    exit 1
fi
rm -f "$STAGE"

echo "handing off to a detached root process"
echo "Termux will be killed when the phone soft reboots. That is expected."
echo "It takes up to two minutes. Then:  su -c 'cat $LOG'"

$SU -c "setsid sh $WORKER < /dev/null > /dev/null 2>&1 &"

sleep 4
su -c "cat $LOG" 2>/dev/null || true
