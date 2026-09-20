#!/usr/bin/env bash
# Make a Bluetooth head tracker visible to ordinary apps on a Galaxy S25.
#
# Samsung ships the dynamic sensor sub-HAL on the device but never lists it in
# hals.conf, so the HID reports the headphones are already sending arrive in the
# kernel and are thrown away. This bind-mounts a hals.conf that names it, which
# leaves /vendor untouched on disk so dm-verity never notices, restarts the
# sensors HAL so it re-reads the file, and lifts the system-only restriction on
# TYPE_HEAD_TRACKER afterwards.
#
# Needs root. Nothing here survives a reboot, by design: the mount is in memory,
# and the restriction is a flag inside system_server. Re-run it after every boot,
# once Root My Galaxy has been re-applied.
#
# Restarting the sensors HAL takes system_server's SensorService down with it,
# so the phone soft-reboots partway through. That is expected, not a failure.
set -u

SERIAL="${1:-}"
ADB=(adb)
[ -n "$SERIAL" ] && ADB=(adb -s "$SERIAL")

say() { printf '\n== %s\n' "$*"; }
sh_() { "${ADB[@]}" shell "$@"; }
root() { "${ADB[@]}" shell "su -c '$1'"; }

say "checking root"
if ! root 'id' | grep -q 'uid=0'; then
    echo "no root. Re-run Root My Galaxy first, and grant Shell in the KernelSU app." >&2
    exit 1
fi

say "backing up the real hals.conf"
mkdir -p ~/device-backups
root 'cat /vendor/etc/sensors/hals.conf' > ~/device-backups/hals.conf."$(date +%Y%m%d-%H%M%S)".txt

if root 'cat /vendor/etc/sensors/hals.conf' | grep -q dynamic_sensor_hal; then
    say "already mounted, skipping"
else
    say "staging the modified config"
    root 'cp /vendor/etc/sensors/hals.conf /data/local/tmp/hals.conf'
    root 'echo sensors.dynamic_sensor_hal.so >> /data/local/tmp/hals.conf'
    root 'chown root:root /data/local/tmp/hals.conf'
    root 'chmod 644 /data/local/tmp/hals.conf'
    # Must carry the vendor label or the HAL is denied reading it.
    root 'chcon u:object_r:vendor_configs_file:s0 /data/local/tmp/hals.conf'

    say "bind mounting over /vendor/etc/sensors/hals.conf"
    root 'mount -o bind /data/local/tmp/hals.conf /vendor/etc/sensors/hals.conf'
    sh_ 'cat /vendor/etc/sensors/hals.conf'

    say "restarting the sensors HAL (the phone will soft reboot here)"
    root 'setprop ctl.restart vendor.sensors-hal-multihal'
fi

say "waiting for sensorservice to come back"
for _ in $(seq 1 40); do
    sleep 3
    if sh_ 'dumpsys sensorservice 2>/dev/null | head -1' 2>/dev/null | grep -q 'Captured at'; then
        break
    fi
done

say "lifting the system-only restriction on TYPE_HEAD_TRACKER"
# Undocumented, and the only lever: SensorService hard-codes head tracker access
# to system_server and audioserver, with no permission that satisfies it. Shell
# holds the signature-level MANAGE_SENSORS that the command requires.
sh_ 'cmd sensorservice unrestrict-ht'

say "result"
if sh_ 'dumpsys sensorservice' | grep -q 'head_tracker'; then
    sh_ 'dumpsys sensorservice' | grep -A1 'head_tracker(37)'
    echo
    echo "Head tracking is live. Put the headphones on and it will appear to apps."
else
    echo "No head tracker. Connect the headphones and run this again." >&2
    exit 1
fi
