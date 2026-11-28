#!/bin/sh
# capinux /init — PID 1 inside the Alpine VM.
#
# The root filesystem is an initramfs (RAM), so this script must never exit: if
# PID 1 exits the kernel panics. It mounts the pseudo filesystems, loads the few
# virtio modules the QEMU `virt` machine needs, brings the SLIRP NIC up in the
# background, prints the readiness marker and then keeps a root shell alive on
# the serial console (/dev/ttyAMA0).
#
# Serial layout (see LinuxVm.java):
#   /dev/ttyAMA0  console: stdin + stdout + stderr of the interactive shell
#   /dev/ttyAMA1  side-channel: `exec()` sends the command's stderr here so the
#                 plugin can deliver stdout and stderr as separate streams
#
# Installed as /init by scripts/build-initramfs.py; edit here, then re-run
# scripts/fetch-linux-runtime.py.

PATH=/sbin:/usr/sbin:/bin:/usr/bin:/usr/local/sbin:/usr/local/bin
export PATH
export HOME=/root
export TERM=xterm-256color

CONSOLE=/dev/ttyAMA0

mount -t proc proc /proc
mount -t sysfs sysfs /sys 2>/dev/null
mount -t devtmpfs devtmpfs /dev 2>/dev/null
mkdir -p /dev/pts /dev/shm /run /tmp /var/log /var/tmp
mount -t devpts devpts /dev/pts 2>/dev/null
mount -t tmpfs -o size=64m,mode=1777 tmpfs /tmp 2>/dev/null
mount -t tmpfs -o size=32m,mode=755 tmpfs /run 2>/dev/null

echo capinux > /proc/sys/kernel/hostname 2>/dev/null || hostname capinux
echo capinux > /etc/hostname

# --- kernel modules + network + host share (background) ---------------------
# Loading kernel modules under TCG is expensive (netfs alone is 517KB) and the
# shell does not need any of them to be usable. So everything that is not needed
# to reach the console happens here, off the boot path: insmod the network/9p
# stack, bring the SLIRP NIC up, then mount the 9p share.
(
    if [ -r /etc/capinux/modules-bg.list ]; then
        while IFS= read -r m; do
            [ -n "$m" ] || continue
            case "$m" in /*) p="$m" ;; *) p="/$m" ;; esac
            [ -f "$p" ] || continue
            insmod "$p" 2>/dev/null
        done < /etc/capinux/modules-bg.list
    fi

    # networking: QEMU user-mode (SLIRP) NIC, guest 10.0.2.15/24
    i=0
    iface=
    while [ $i -lt 60 ]; do
        for n in /sys/class/net/*; do
            [ -e "$n" ] || continue
            b=${n##*/}
            [ "$b" = "lo" ] && continue
            iface=$b
            break
        done
        [ -n "$iface" ] && break
        sleep 0.1 2>/dev/null || sleep 1
        i=$((i + 1))
    done
    ip link set lo up 2>/dev/null
    if [ -n "$iface" ]; then
        ip link set "$iface" up 2>/dev/null
        ip addr add 10.0.2.15/24 dev "$iface" 2>/dev/null
        ip route add default via 10.0.2.2 dev "$iface" 2>/dev/null
        printf 'nameserver 1.1.1.1\nnameserver 8.8.8.8\nnameserver 10.0.2.3\n' > /etc/resolv.conf
        if ! ip addr show dev "$iface" 2>/dev/null | grep -q 'inet 10\.0\.2\.'; then
            udhcpc -q -n -t 6 -T 2 -i "$iface" -s /etc/capinux/udhcpc.sh >/dev/null 2>&1
        fi
    fi

    # host <-> guest file share (9p over virtio, mounted at /mnt/host)
    SHARE=/mnt/host
    mkdir -p "$SHARE"
    if mount -t 9p -o trans=virtio,version=9p2000.L,msize=1048576,access=any \
        hostshare "$SHARE" 2>/dev/null; then
        mkdir -p /run/capinux
        : > /run/capinux/hostshare-mounted
    fi
) >/dev/null 2>&1 &

# Fallback resolver so DNS resolves before the background setup finishes.
[ -s /etc/resolv.conf ] || printf 'nameserver 1.1.1.1\nnameserver 8.8.8.8\n' > /etc/resolv.conf

# --- optional persistent data disk -----------------------------------------
# Two persistence modes exist (see LinuxVm.java):
#   rootfsMode 'disk' (推荐): 整个 root 就在 /dev/vda 上，这个 /init 是从磁盘
#       启动的（init-bootstrap.sh 已经 mkfs + 解包 + switch_root），所以这里
#       什么都不用挂 —— /usr、/lib、/root 全都是持久的，`apk add` 也会留下。
#   rootfsMode 'ram' + persistent:true（旧模式）: root 在内存里，只把
#       /root /home /opt /usr/local bind 到数据盘上；包仍然不持久。
PERSIST=/mnt/persist
ROOT_ON_DISK=
[ "$(cat /etc/capinux/rootfs-mode 2>/dev/null)" = "disk" ] && ROOT_ON_DISK=1
if [ -b /dev/vda ] && [ -z "$ROOT_ON_DISK" ]; then
    mkdir -p "$PERSIST"
    mount -t ext4 /dev/vda "$PERSIST" 2>/dev/null || {
        mkfs.ext4 -q -F -L capinux /dev/vda >/dev/null 2>&1 &&
            mount -t ext4 /dev/vda "$PERSIST" 2>/dev/null
    }
    if mountpoint -q "$PERSIST" 2>/dev/null; then
        for d in root home opt usr-local; do mkdir -p "$PERSIST/$d"; done
        mount -o bind "$PERSIST/root" /root 2>/dev/null
        mkdir -p /home && mount -o bind "$PERSIST/home" /home 2>/dev/null
        mkdir -p /opt && mount -o bind "$PERSIST/opt" /opt 2>/dev/null
        mkdir -p /usr/local && mount -o bind "$PERSIST/usr-local" /usr/local 2>/dev/null
        mkdir -p /run/capinux
        : > /run/capinux/persist-mounted
    fi
fi

# --- networking -------------------------------------------------------------
# Already handled by the background block above (modules + NIC + 9p), so nothing
# to do here: it keeps the boot path short.

. /etc/capinux/release 2>/dev/null

{
    printf '\n'
    printf '  \033[1;36mcapinux\033[0m - %s %s (offline rootfs, runs in RAM)\n' \
        "${DISTRO:-alpine}" "${VERSION:-unknown}"
    printf '  kernel %s   arch %s   rootfs initramfs\n' "$(uname -r)" "$(uname -m)"
    printf '  try `apk version`, `apk update`, `apk add <pkg>`, `poweroff -f` to stop\n'
    # Machine-readable readiness marker: LinuxVm waits for this exact line
    # before resolving start(). Keep it in sync with LinuxVm.READY_MARKER.
    printf 'capinux: ready\n\n'
} > $CONSOLE 2>&1

# --- keep a root shell on the console forever -------------------------------
# Job control is switched OFF for the console shell (/etc/profile.d below). With
# `set -m` the shell gives each job its own process group and hands it the
# terminal; in this guest that leaves the tty's foreground group pointing at the
# dead job after a Ctrl-C, and the shell then blocks on read (SIGTTIN) - the
# console would look dead. Without job control every command stays in the shell's
# process group: the tty still sends SIGINT to the foreground program (so
# `sleep`/`node`/`cat` are interrupted), while the shell ignores SIGINT and keeps
# reading. Trade-off: no Ctrl-Z / fg / bg.
printf 'set +m\n' > /etc/profile.d/00-capinux-nojc.sh
export ENV=/etc/profile.d/00-capinux-nojc.sh
while :; do
    setsid -c sh -l < $CONSOLE > $CONSOLE 2>&1 &
    child=$!
    # Watchdog: if the shell ever ends up stopped (SIGTTIN after an interrupt) or
    # exits, replace it so the console always comes back within ~1s.
    while kill -0 $child 2>/dev/null; do
        state=$(awk '{print $3}' /proc/$child/stat 2>/dev/null)
        if [ "$state" = "T" ] || [ "$state" = "t" ]; then
            kill -9 $child 2>/dev/null
            break
        fi
        sleep 0.3
    done
    wait $child 2>/dev/null
    printf '\n[capinux] shell exited - respawning in 1s (VM stops on `poweroff -f`)\n' > $CONSOLE
    sleep 1
done
