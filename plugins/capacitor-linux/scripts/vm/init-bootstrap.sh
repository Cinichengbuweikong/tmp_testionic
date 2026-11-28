#!/bin/sh
# capinux bootstrap /init — PID 1 of the *initramfs* when the VM boots from a
# persistent disk (`start({ rootfsMode: 'disk' })`).
#
# Its only job: load the few virtio modules, then either mount the existing
# ext4 rootfs on /dev/vda or (first boot) format it and unpack the rootfs from
# the read-only tar drive (/dev/vdb), and finally switch_root into it. The real
# /init (shell, 9p share, network) lives inside the disk image, see init.sh.
#
# Everything is offline: the tar drive is `assets/linux/rootfs.tar.gz` from the APK.

PATH=/sbin:/usr/sbin:/bin:/usr/bin
export PATH

CONSOLE=/dev/ttyAMA0
say() { printf '[capinux] %s\n' "$*" > "$CONSOLE" 2>/dev/null; }

# The host tells us which mode it configured (see LinuxVm.start()): with
# `capinux.rootfs=ram` there is no disk at all, so skip the /dev/vda wait.
case " $(cat /proc/cmdline 2>/dev/null) " in
*" capinux.rootfs=ram "*)
    exec /etc/capinux/init-root.sh
    ;;
esac

mount -t proc proc /proc 2>/dev/null
mount -t sysfs sysfs /sys 2>/dev/null
mount -t devtmpfs devtmpfs /dev 2>/dev/null
mkdir -p /dev/pts /dev/shm /run /tmp
mount -t devpts devpts /dev/pts 2>/dev/null

# RAM mode (`capinux.rootfs=ram`): no disk at all, and the shell needs no kernel
# module up front - the runtime init loads the network/9p stack in the background,
# so boot is not held up by slow insmods under TCG.
case " $(cat /proc/cmdline 2>/dev/null) " in
*" capinux.rootfs=ram "*)
    exec /etc/capinux/init-root.sh
    ;;
esac

# Disk mode: only the storage stack is needed synchronously (mount happens before
# switch_root); everything else is loaded in the background afterwards.
mkdir -p /run
mount -t tmpfs -o size=8m,mode=755 tmpfs /run 2>/dev/null
if [ -r /etc/capinux/modules-disk.list ]; then
    while IFS= read -r m; do
        [ -n "$m" ] || continue
        case "$m" in /*) p="$m" ;; *) p="/$m" ;; esac
        [ -f "$p" ] || continue
        insmod "$p" 2>/dev/null
    done < /etc/capinux/modules-disk.list
fi

# virtio-blk probes asynchronously: wait for the root disk to show up.
i=0
while [ ! -b /dev/vda ] && [ "$i" -lt 100 ]; do
    sleep 0.1 2>/dev/null || sleep 1
    i=$((i + 1))
done

if [ ! -b /dev/vda ]; then
    # No disk attached -> this is the RAM-boot mode (rootfsMode 'ram'): hand over to
    # the runtime init that lives inside this initramfs.
    say "no /dev/vda - booting the in-memory rootfs"
    exec /etc/capinux/init-root.sh
fi

mkdir -p /mnt
mounted=
if mount -t ext4 /dev/vda /mnt 2>/dev/null; then
    if [ -x /mnt/init ]; then
        say "rootfs: existing filesystem on /dev/vda"
        mounted=1
    else
        say "rootfs: /dev/vda mounted but empty - re-initialising"
        umount /mnt
    fi
fi

if [ -z "$mounted" ]; then
    say "rootfs: first boot - formatting /dev/vda (this takes ~1 minute)"
    if ! mkfs.ext4 -q -F -L capinux /dev/vda; then
        say "ERROR: mkfs.ext4 failed (e2fsprogs missing from the rootfs?)"
        exec sh
    fi
    if ! mount -t ext4 /dev/vda /mnt; then
        say "ERROR: mount /dev/vda failed"
        exec sh
    fi
    if [ ! -b /dev/vdb ]; then
        say "ERROR: /dev/vdb (rootfs payload) not found"
        exec sh
    fi
    say "rootfs: unpacking /dev/vdb into /dev/vda ..."
    # The payload is a gzipped tar (assets/linux/rootfs.tar.gz). gzip stops at the
    # end of the compressed stream, so reading the raw block device is fine.
    if ! gzip -dc /dev/vdb 2>/dev/null | tar -x -C /mnt -f - 2>/dev/null; then
        say "ERROR: unpacking the rootfs payload failed"
        exec sh
    fi
    # tar keeps no device nodes we care about (devtmpfs is mounted anyway).
    if [ ! -x /mnt/init ]; then
        say "ERROR: unpacked rootfs has no /init"
        exec sh
    fi
    say "rootfs: initialised ($(df -h /mnt 2>/dev/null | tail -1 | awk '{print $2}') on /dev/vda)"
fi

say "rootfs: switching root"
exec switch_root /mnt /init
