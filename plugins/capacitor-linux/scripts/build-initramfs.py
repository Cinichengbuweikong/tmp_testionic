#!/usr/bin/env python3
"""Build a bootable initramfs (cpio.gz) for the capinux VM from an Alpine minirootfs.

The result is a *complete, offline* Linux root filesystem: Alpine busybox, apk,
musl, /etc, plus the kernel modules QEMU's `virt` machine needs. Booting it
requires no network access at all.

Why a tree is built first instead of streaming entries: the kernel's initramfs
unpacker is strictly order-dependent. If an entry arrives before its parent
directory exists and that parent is later a symlink (Alpine ships /lib and
others as symlinks), the kernel silently drops that subtree. So we

  1. read everything into an in-memory tree,
  2. resolve entries that live below a symlinked directory onto the real path,
  3. emit directories (shallow -> deep), then symlinks, then files.

Reproducible: mtimes are normalised, gzip mtime is 0, so identical inputs give
byte-identical output.

Usage:
  build-initramfs.py --rootfs alpine-minirootfs-3.23.8-aarch64.tar.gz \
      --out rootfs.cpio.gz --init init.sh --udhcpc udhcpc.sh \
      --modules-dir <dir> --modules "virtio_mmio.ko virtio_net.ko" \
      --version 3.23.8 --kernel 6.18.60-0-virt
"""
import argparse
import gzip
import json
import io
import os
import sys
import tarfile

FIXED_MTIME = 1700000000  # fixed timestamp keeps builds reproducible
MODULE_DIR = "opt/capinux/modules"  # NOT /lib: Alpine's /lib is a symlink


def cpio_entry(name, ino, mode, uid, gid, nlink, mtime, filesize, rdevmajor=0, rdevminor=0):
    namez = name.encode() + b"\0"
    fields = [ino, mode, uid, gid, nlink, mtime, filesize, 0, 0, rdevmajor, rdevminor,
              len(namez), 0]
    data = b"070701" + b"".join(b"%08x" % f for f in fields) + namez
    data += b"\0" * ((4 - len(data) % 4) % 4)
    return data


class Tree:
    def __init__(self):
        # name -> {kind, mode, mtime, uid, gid, data}
        self.nodes = {}
        self.modules = []

    def ensure_dir(self, name, mode=0o755, mtime=FIXED_MTIME, uid=0, gid=0):
        if not name or name in self.nodes:
            return
        parts = name.split("/")
        for i in range(1, len(parts)):
            self.ensure_dir("/".join(parts[:i]))
        self.nodes[name] = {"kind": "dir", "mode": mode, "mtime": mtime, "uid": uid, "gid": gid}

    def add_dir(self, name, mode, mtime, uid=0, gid=0):
        self.ensure_dir(name)
        node = self.nodes[name]
        node.update(kind="dir", mode=mode, mtime=mtime, uid=uid, gid=gid)

    def add_symlink(self, name, target, mtime, uid=0, gid=0):
        self.ensure_dir(name.rsplit("/", 1)[0] if "/" in name else "")
        if name in self.nodes:  # a directory of that name already exists: keep it
            return
        self.nodes[name] = {"kind": "symlink", "mode": 0o777, "mtime": mtime, "uid": uid,
                            "gid": gid, "data": target.encode()}

    def add_file(self, name, mode, mtime, data, uid=0, gid=0):
        self.ensure_dir(name.rsplit("/", 1)[0] if "/" in name else "")
        if name in self.nodes:
            return
        self.nodes[name] = {"kind": "file", "mode": mode, "mtime": mtime, "uid": uid, "gid": gid,
                            "data": data}

    def add_chardev(self, name, mode, major, minor):
        self.ensure_dir(name.rsplit("/", 1)[0] if "/" in name else "")
        self.nodes[name] = {"kind": "chardev", "mode": mode, "mtime": FIXED_MTIME, "uid": 0,
                            "gid": 0, "rdev": (major, minor)}

    def clone(self):
        other = Tree()
        other.nodes = {k: dict(v) for k, v in self.nodes.items()}
        return other

    def replace_file(self, name, mode, mtime, data):
        self.nodes.pop(name, None)
        self.add_file(name, mode, mtime, data)

    # --- symlink-aware relayout ------------------------------------------------
    def resolve(self, name, _depth=0):
        """Resolve a path whose *ancestor* components may be symlinks.

        Only ancestors are followed: for an entry that is itself a symlink we
        must keep the symlink, not its target.
        """
        if _depth > 16:
            return name
        parts = name.split("/")
        cur = ""
        for i in range(len(parts) - 1):
            cur = parts[i] if not cur else cur + "/" + parts[i]
            node = self.nodes.get(cur)
            if node and node["kind"] == "symlink":
                target = node["data"].decode()
                if not target.startswith("/"):
                    base = cur.rsplit("/", 1)[0] if "/" in cur else ""
                    target = (base + "/" + target) if base else target
                rest = "/".join(parts[i + 1:])
                merged = os.path.normpath(target).lstrip("/")
                if rest:
                    merged = merged + "/" + rest
                return self.resolve(merged, _depth + 1)
        return name

    def relayout(self):
        """Move entries that sit under a symlinked directory onto the target path."""
        moved = 0
        for name in list(self.nodes):
            real = self.resolve(name)
            if real != name:
                node = self.nodes.pop(name)
                if real not in self.nodes:
                    self.nodes[real] = node
                    moved += 1
        for name in list(self.nodes):
            self.ensure_dir(name.rsplit("/", 1)[0] if "/" in name else "")
        return moved

    # --- emit -----------------------------------------------------------------
    def emit(self, fh):
        ino = 1
        entries = 0
        payload = 0
        dirs = sorted((n for n, v in self.nodes.items() if v["kind"] == "dir"),
                      key=lambda n: (n.count("/"), n))
        symlinks = sorted(n for n, v in self.nodes.items() if v["kind"] == "symlink")
        others = sorted(n for n, v in self.nodes.items() if v["kind"] not in ("dir", "symlink"))

        def write(name, mode, uid, gid, nlink, mtime, data=b"", rdev=(0, 0)):
            nonlocal ino, entries, payload
            fh.write(cpio_entry(name, ino, mode, uid, gid, nlink, mtime, len(data), *rdev))
            ino += 1
            if data:
                fh.write(data)
                fh.write(b"\0" * ((4 - len(data) % 4) % 4))
            entries += 1
            payload += len(data)

        for name in dirs:
            v = self.nodes[name]
            write(name, 0o040000 | v["mode"], v["uid"], v["gid"], 2, v["mtime"])
        for name in symlinks:
            v = self.nodes[name]
            write(name, 0o120000 | 0o777, v["uid"], v["gid"], 1, v["mtime"], v["data"])
        for name in others:
            v = self.nodes[name]
            if v["kind"] == "chardev":
                write(name, 0o020000 | v["mode"], v["uid"], v["gid"], 1, v["mtime"], b"",
                      v["rdev"])
            else:
                write(name, 0o100000 | v["mode"], v["uid"], v["gid"], 1, v["mtime"], v["data"])

        fh.write(cpio_entry("TRAILER!!!", 0, 0, 0, 0, 1, 0, 0))
        return entries, payload

    def emit_tar(self, fh):
        """Write the tree as a gzipped-tar payload (rootfs for a disk image).

        Device nodes are skipped: the guest mounts devtmpfs over /dev anyway, and
        busybox tar cannot create them without privileges.
        """
        entries = 0
        payload = 0
        dirs = sorted((n for n, v in self.nodes.items() if v["kind"] == "dir"),
                      key=lambda n: (n.count("/"), n))
        symlinks = sorted(n for n, v in self.nodes.items() if v["kind"] == "symlink")
        others = sorted(n for n, v in self.nodes.items() if v["kind"] == "file")

        def info(name, mode, size=0):
            ti = tarfile.TarInfo(name)
            ti.mode = mode
            ti.uid = 0
            ti.gid = 0
            ti.uname = "root"
            ti.gname = "root"
            ti.mtime = FIXED_MTIME
            ti.size = size
            return ti

        for name in dirs:
            fh.write(info(name + "/", self.nodes[name]["mode"]).tobuf())
            entries += 1
        for name in symlinks:
            ti = info(name, 0o777)
            ti.type = tarfile.SYMTYPE
            ti.linkname = self.nodes[name]["data"].decode()
            fh.write(ti.tobuf())
            entries += 1
        for name in others:
            v = self.nodes[name]
            data = v["data"]
            fh.write(info(name, v["mode"], len(data)).tobuf())
            fh.write(data)
            fh.write(b"\0" * ((512 - len(data) % 512) % 512))
            entries += 1
            payload += len(data)
        fh.write(b"\0" * 1024)   # two zero blocks terminate a tar archive
        return entries, payload


def add_extra_rootfs(t, root):
    """Merge a directory tree into the build tree (used for extra Alpine packages).

    The packages are unpacked into a staging directory by
    fetch-linux-runtime.py. Symlinks that could not be created on the build host
    (Windows) are listed in `.capinux-symlinks.json` and are recreated here.
    """
    root = os.path.abspath(root)
    links = {}
    link_file = os.path.join(root, ".capinux-symlinks.json")
    if os.path.isfile(link_file):
        with open(link_file, "r", encoding="utf-8") as fh:
            links = json.load(fh)
    modes = {}
    modes_file = os.path.join(root, ".capinux-modes.json")
    if os.path.isfile(modes_file):
        with open(modes_file, "r", encoding="utf-8") as fh:
            modes = json.load(fh)
    for dirpath, dirnames, filenames in os.walk(root):
        rel_dir = os.path.relpath(dirpath, root).replace("\\", "/")
        if rel_dir != ".":
            t.add_dir(rel_dir, 0o755, FIXED_MTIME)
        for name in list(dirnames) + list(filenames):
            if name in (".capinux-symlinks.json", ".capinux-modes.json"):
                continue
            full = os.path.join(dirpath, name)
            rel = os.path.relpath(full, root).replace("\\", "/")
            if os.path.islink(full):
                t.add_symlink(rel, os.readlink(full), FIXED_MTIME)
            elif os.path.isdir(full):
                t.add_dir(rel, 0o755, FIXED_MTIME)
            elif os.path.isfile(full):
                # Windows has no POSIX exec bit: use the mode recorded at unpack time,
                # otherwise /sbin/mkfs.ext4 ends up as 0666 and cannot run.
                mode = modes.get(rel)
                if mode is None:
                    mode = 0o755 if rel.startswith(
                        ("sbin/", "usr/sbin/", "bin/", "usr/bin/")) else 0o644
                with open(full, "rb") as fh:
                    t.add_file(rel, mode, FIXED_MTIME, fh.read())
    for name, target in links.items():
        t.add_symlink(name, target, FIXED_MTIME)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--rootfs", required=True, help="alpine-minirootfs-*.tar.gz")
    ap.add_argument("--out", required=True, help="output .cpio.gz")
    ap.add_argument("--init", required=True, help="/init script to install")
    ap.add_argument("--udhcpc", help="udhcpc hook script to install")
    ap.add_argument("--modules-dir", help="directory holding .ko files")
    ap.add_argument("--modules", default="", help="space separated .ko names, load order")
    ap.add_argument("--disk-modules", default="",
                    help="modules loaded by the bootstrap before mounting the disk (virtio_blk + ext4 stack)")
    ap.add_argument("--background-modules", default="",
                    help="modules loaded by the runtime init in the background (network + 9p)")
    ap.add_argument("--extra-rootfs", help="extra directory tree to merge into the rootfs")
    ap.add_argument("--bootstrap-init",
                    help="init installed as /init of the initramfs (disk-boot mode)")
    ap.add_argument("--tar-out",
                    help="also write a .tar.gz of the runtime rootfs (persistent disk image payload)")
    ap.add_argument("--version", default="unknown", help="Alpine version string")
    ap.add_argument("--kernel", default="", help="kernel release string (banner only)")
    args = ap.parse_args()

    t = Tree()

    # Device nodes must exist before devtmpfs is mounted: the kernel opens
    # /dev/console to give PID 1 its stdio.
    t.add_chardev("dev/console", 0o600, 5, 1)
    t.add_chardev("dev/null", 0o666, 1, 3)
    t.add_chardev("dev/zero", 0o666, 1, 5)
    t.add_chardev("dev/tty", 0o666, 5, 0)
    for d, mode in (("tmp", 0o1777), ("run", 0o755), ("dev/pts", 0o755), ("dev/shm", 0o1777)):
        t.add_dir(d, mode, FIXED_MTIME)

    with open(args.init, "r", encoding="utf-8") as fh:
        t.add_file("init", 0o755, FIXED_MTIME, fh.read().encode())

    with tarfile.open(args.rootfs, "r:gz") as tf:
        for m in tf.getmembers():
            name = m.name.lstrip("./").rstrip("/")
            if not name:
                continue
            mode = m.mode & 0o7777
            if m.isdir():
                t.add_dir(name, mode, int(m.mtime), m.uid, m.gid)
            elif m.issym():
                t.add_symlink(name, m.linkname, int(m.mtime), m.uid, m.gid)
            elif m.isfile():
                t.add_file(name, mode, int(m.mtime), tf.extractfile(m).read(), m.uid, m.gid)
            # hard links: content already exists under the first name
            # sockets/fifos: not meaningful inside a fresh rootfs

    moved = t.relayout()

    # Extra Alpine packages (e.g. e2fsprogs, so the guest can mkfs the persistent
    # data disk) are unpacked into a staging tree by the fetch script.
    if args.extra_rootfs:
        add_extra_rootfs(t, args.extra_rootfs)

    if args.udhcpc:
        with open(args.udhcpc, "r", encoding="utf-8") as fh:
            t.add_file("etc/capinux/udhcpc.sh", 0o755, FIXED_MTIME, fh.read().encode())

    mods = [m for m in args.modules.split() if m]
    if mods and args.modules_dir:
        for mod in mods:
            with open(os.path.join(args.modules_dir, mod), "rb") as fh:
                t.add_file(MODULE_DIR + "/" + mod, 0o644, FIXED_MTIME, fh.read())
    t.add_file("etc/capinux/modules.list", 0o644, FIXED_MTIME,
               ("\n".join(MODULE_DIR + "/" + m for m in mods) + "\n").encode())
    # Two subsets so the boot path stays short: the disk stack must be loaded
    # before /dev/vda can be mounted, while everything else (network + 9p, incl.
    # the 517KB netfs) is loaded by the runtime init in the background after the
    # shell is already usable.
    disk_mods = set(args.disk_modules.split())
    bg_mods = set(args.background_modules.split())
    t.add_file("etc/capinux/modules-disk.list", 0o644, FIXED_MTIME,
               ("\n".join(MODULE_DIR + "/" + m for m in mods if m in disk_mods) + "\n").encode())
    t.add_file("etc/capinux/modules-bg.list", 0o644, FIXED_MTIME,
               ("\n".join(MODULE_DIR + "/" + m for m in mods if m in bg_mods) + "\n").encode())

    release = (f'NAME="capinux"\nDISTRO="alpine"\nVERSION="{args.version}"\n'
               f'KERNEL="{args.kernel}"\n')
    t.add_file("etc/capinux/release", 0o644, FIXED_MTIME, release.encode())

    # --- outputs -----------------------------------------------------------
    # Same tree, two artifacts:
    #   * disk payload (tar.gz): rootfs for a persistent ext4 image; /init is the
    #     runtime init and etc/capinux/rootfs-mode says "disk";
    #   * initramfs (cpio.gz): RAM-boot rootfs; when --bootstrap-init is given its
    #     /init is the bootstrap script that formats/unpacks/switch_roots into the
    #     disk, so the same kernel cmdline works for both modes.
    if args.tar_out:
        disk_tree = t.clone()
        disk_tree.add_file("etc/capinux/rootfs-mode", 0o644, FIXED_MTIME, b"disk\n")
        tariff = io.BytesIO()
        with gzip.GzipFile(fileobj=tariff, mode="wb", compresslevel=6, mtime=0, filename="") as gz:
            entries, payload = disk_tree.emit_tar(gz)
        data = tariff.getvalue()
        with open(args.tar_out, "wb") as fh:
            fh.write(data)
        print(f"{args.tar_out}: {len(data) / 1e6:.2f} MB gz / {payload / 1e6:.2f} MB payload "
              f"({entries} entries, persistent-disk rootfs)")

    cpio_tree = t
    if args.bootstrap_init:
        cpio_tree = t.clone()
        # The initramfs gets two inits: the bootstrap one at /init (decides disk vs
        # RAM), and the runtime one next to it so RAM-mode can exec into it when no
        # disk is attached. The disk payload keeps the runtime one at /init.
        with open(args.bootstrap_init, "r", encoding="utf-8") as fh:
            cpio_tree.replace_file("init", 0o755, FIXED_MTIME, fh.read().encode())
        with open(args.init, "r", encoding="utf-8") as fh:
            cpio_tree.add_file("etc/capinux/init-root.sh", 0o755, FIXED_MTIME,
                               fh.read().encode())

    raw = io.BytesIO()
    with gzip.GzipFile(fileobj=raw, mode="wb", compresslevel=6, mtime=0, filename="") as gz:
        entries, payload = cpio_tree.emit(gz)
    data = raw.getvalue()
    with open(args.out, "wb") as fh:
        fh.write(data)
    print(f"{args.out}: {len(data) / 1e6:.2f} MB gz / {payload / 1e6:.2f} MB payload, "
          f"{entries} entries, {len(mods)} modules, {moved} relayouted")


if __name__ == "__main__":
    sys.exit(main())
