#!/bin/sh
# capinux udhcpc hook — configures the QEMU SLIRP NIC from a DHCP lease.
# /init normally configures 10.0.2.15/24 statically (slirp always uses that
# layout) and only falls back to udhcpc when the static address does not take.
# busybox udhcpc calls this with: deconfig | bound | renew | leasefail

dotted_to_prefix() {
    p=0
    old_ifs=$IFS
    IFS=.
    for o in $1; do
        case "$o" in
        255) p=$((p + 8)) ;;
        254) p=$((p + 7)) ;;
        252) p=$((p + 6)) ;;
        248) p=$((p + 5)) ;;
        240) p=$((p + 4)) ;;
        224) p=$((p + 3)) ;;
        192) p=$((p + 2)) ;;
        128) p=$((p + 1)) ;;
        0) ;;
        esac
    done
    IFS=$old_ifs
    echo "$p"
}

case "$1" in
bound | renew)
    prefix="$mask"
    case "$mask" in
    *.*) prefix=$(dotted_to_prefix "$mask") ;;
    esac
    [ -n "$prefix" ] || prefix=24
    ip addr flush dev "$interface" 2>/dev/null
    ip addr add "$ip/$prefix" dev "$interface" 2>/dev/null
    [ -n "$router" ] && ip route add default via "$router" dev "$interface" 2>/dev/null
    if [ -n "$dns" ]; then
        : >/etc/resolv.conf
        for d in $dns; do echo "nameserver $d" >>/etc/resolv.conf; done
    fi
    ;;
esac
