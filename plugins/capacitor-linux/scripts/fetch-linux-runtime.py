#!/usr/bin/env python3
"""Assemble the offline Linux VM runtime used by the Android side of this plugin.

Everything the VM needs is fetched once at build time and then bundled into the
APK: nothing is downloaded on the device.

Outputs (all git-ignored, regenerate with `npm run fetch-runtime`):

  runtime/android/assets/linux/kernel           Alpine vmlinuz-virt (arm64)
  runtime/android/assets/linux/rootfs.cpio.gz   offline rootfs built from minirootfs
  runtime/android/assets/linux/manifest.json    versions + sizes + sha256
  runtime/android/jniLibs/arm64-v8a/libqemu-system-aarch64.so
  runtime/android/jniLibs/arm64-v8a/lib*.so     QEMU's shared-library closure

Why QEMU from Termux: Android has no official QEMU build. Termux ships
`qemu-system-aarch64-headless` built against bionic for arm64, and its binary +
its .so closure can be executed from nativeLibraryDir (the only place Android
10+ lets an app execve from). QEMU is GPLv2; see README "许可证与来源".

Why the rootfs is an initramfs: it is a plain cpio.gz, so the kernel unpacks it
into RAM with no disk image, no mkfs, no loop device and no root privileges.

Requires: python3 (3.9+) on PATH or `--python`.
"""
from __future__ import annotations

import argparse
import gzip
import hashlib
import io
import json
import os
import re
import shutil
import struct
import subprocess
import sys
import tarfile
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
PLUGIN = os.path.dirname(HERE)
LOCK = os.path.join(PLUGIN, "runtime.lock.json")
RUNTIME = os.path.join(PLUGIN, "runtime")
CACHE = os.path.join(RUNTIME, ".cache")
OUT_ANDROID = os.path.join(RUNTIME, "android")
OUT_ASSETS = os.path.join(OUT_ANDROID, "assets", "linux")
OUT_JNI = os.path.join(OUT_ANDROID, "jniLibs", "arm64-v8a")

AR_MAGIC = b"!<arch>\n"
SYSTEM_LIBS = {"libc.so", "libm.so", "libdl.so", "liblog.so", "libandroid.so"}


# --------------------------------------------------------------------------- io
def log(msg: str) -> None:
    print(msg, flush=True)


def fetch(url: str, cache_name: str | None = None, expect_sha256: str | None = None,
          expect_bytes: int | None = None) -> bytes:
    """Download with a persistent cache and optional integrity checks."""
    if cache_name:
        path = os.path.join(CACHE, cache_name)
        if os.path.exists(path):
            data = open(path, "rb").read()
            if (expect_bytes is None or len(data) == expect_bytes) and (
                    expect_sha256 is None or hashlib.sha256(data).hexdigest() == expect_sha256):
                return data
    log(f"  GET {url}")
    req = urllib.request.Request(url, headers={"User-Agent": "capacitor-linux-runtime/1.0"})
    with urllib.request.urlopen(req, timeout=600) as resp:
        data = resp.read()
    if expect_bytes is not None and len(data) != expect_bytes:
        raise SystemExit(f"size mismatch for {url}: {len(data)} != {expect_bytes}")
    if expect_sha256 is not None:
        got = hashlib.sha256(data).hexdigest()
        if got != expect_sha256:
            raise SystemExit(f"sha256 mismatch for {url}\n  expected {expect_sha256}\n  got      {got}")
    if cache_name:
        os.makedirs(CACHE, exist_ok=True)
        with open(os.path.join(CACHE, cache_name), "wb") as fh:
            fh.write(data)
    return data


# ------------------------------------------------------------------- deb / elf
def read_ar(data: bytes) -> list[tuple[str, bytes]]:
    if not data.startswith(AR_MAGIC):
        raise SystemExit("not an ar archive")
    off = len(AR_MAGIC)
    out = []
    while off + 60 <= len(data):
        hdr = data[off:off + 60]
        name = hdr[0:16].decode("utf-8", "replace").strip().rstrip("/")
        size = int(hdr[48:58].decode("ascii").strip())
        out.append((name, data[off + 60:off + 60 + size]))
        off += 60 + size
        if off % 2:
            off += 1
    return out


def deb_data_tar(deb: bytes) -> bytes:
    for name, body in read_ar(deb):
        if name.startswith("data.tar"):
            if name.endswith(".xz"):
                import lzma
                return lzma.decompress(body)
            if name.endswith(".gz"):
                return gzip.decompress(body)
            if name.endswith(".zst"):
                from compression import zstd  # Python 3.14+
                return zstd.decompress(body)
            return body
    raise SystemExit("deb has no data.tar member")


def elf_needed(data: bytes) -> tuple[list[str], str | None]:
    """Return (DT_NEEDED list, DT_RUNPATH/DT_RPATH) of an ELF64 little-endian file.

    Note: DT_NEEDED values are byte offsets into the *string table*, not virtual
    addresses (getting this wrong silently returns garbage).
    """
    if data[:4] != b"\x7fELF" or data[4] != 2 or data[5] != 1:
        return [], None
    e = "<"
    e_phoff = struct.unpack_from(e + "Q", data, 0x20)[0]
    e_phentsize = struct.unpack_from(e + "H", data, 0x36)[0]
    e_phnum = struct.unpack_from(e + "H", data, 0x38)[0]
    loads, dynamic = [], None
    for i in range(e_phnum):
        base = e_phoff + i * e_phentsize
        p_type, _flags, p_offset, p_vaddr, _pa, p_filesz, _ms, _al = struct.unpack_from(
            e + "IIQQQQQQ", data, base)
        if p_type == 1:
            loads.append((p_vaddr, p_offset, p_filesz))
        elif p_type == 2:
            dynamic = (p_offset, p_filesz)
    if dynamic is None:
        return [], None

    def v2o(vaddr: int) -> int:
        for va, off, size in loads:
            if va <= vaddr < va + size:
                return off + (vaddr - va)
        raise ValueError(hex(vaddr))

    entries = []
    off, size = dynamic
    for i in range(0, size, 16):
        tag, val = struct.unpack_from(e + "qQ", data, off + i)
        if tag == 0:
            break
        entries.append((tag, val))
    strtab_vaddr = next((v for t, v in entries if t == 5), None)
    if strtab_vaddr is None:
        return [], None
    strtab = v2o(strtab_vaddr)

    def read_str(idx: int) -> str:
        start = strtab + idx
        end = data.index(b"\0", start)
        return data[start:end].decode("utf-8", "replace")

    needed = [read_str(v) for t, v in entries if t == 1]
    runpath = next((read_str(v) for t, v in entries if t in (29, 15)), None)
    return needed, runpath


# ------------------------------------------------------------------ cpio (read)
def cpio_iter(data: bytes):
    pos = 0
    while pos + 110 <= len(data):
        if data[pos:pos + 6] != b"070701":
            return
        f = [int(data[pos + 6 + i * 8:pos + 14 + i * 8], 16) for i in range(13)]
        namesize, filesize = f[11], f[6]
        name = data[pos + 110:pos + 110 + namesize - 1].decode("utf-8", "replace")
        pos += 110 + namesize
        pos += (4 - pos % 4) % 4
        payload = data[pos:pos + filesize]
        pos += filesize
        pos += (4 - pos % 4) % 4
        if name == "TRAILER!!!":
            return
        yield name, payload


# ------------------------------------------------------------------ termux debs
def parse_packages(text: str) -> dict:
    pkgs = {}
    for block in text.split("\n\n"):
        fields, key = {}, None
        for line in block.splitlines():
            if line[:1] in (" ", "\t") and key:
                continue
            if ":" in line:
                key, val = line.split(":", 1)
                fields[key.strip()] = val.strip()
        if "Package" in fields:
            pkgs[fields["Package"]] = fields
    return pkgs


def resolve_debs(lock: dict) -> dict:
    """Download the QEMU deb + its dependency closure; return {filename: bytes}."""
    qemu = lock["qemu"]
    raw = fetch(qemu["index"], cache_name="termux-Packages.gz")
    index = parse_packages(gzip.decompress(raw).decode("utf-8", "replace"))
    root = qemu["package"]
    if root not in index:
        raise SystemExit(f"{root} not in Termux index")
    pinned = index[root].get("Version")
    if pinned != qemu["version"]:
        log(f"  ! Termux moved {root} from {qemu['version']} to {pinned}; update runtime.lock.json")

    needed, todo = set(), [root]
    while todo:
        name = todo.pop()
        if name in needed:
            continue
        if name not in index:
            raise SystemExit(f"dependency {name} missing from index")
        needed.add(name)
        for dep in index[name].get("Depends", "").split(","):
            dep = re.split(r"[\s(|:]", dep.strip())[0]
            if dep:
                todo.append(dep)

    debs = {}
    total = 0
    for name in sorted(needed):
        info = index[name]
        url = "https://packages.termux.dev/apt/termux-main/" + info["Filename"]
        expect = info.get("SHA256")
        size = int(info.get("Size", "0") or 0)
        data = fetch(url, cache_name="termux-" + os.path.basename(info["Filename"]),
                     expect_sha256=expect, expect_bytes=size or None)
        debs[info["Filename"]] = data
        total += len(data)
    log(f"  debs: {len(debs)} packages, {total / 1e6:.1f} MB")
    return debs


def unpack_termux(debs: dict, lock: dict) -> tuple[dict[str, bytes], bytes]:
    """Extract the qemu binary and every usr/lib/*.so* it might need.

    Termux packages express library versions with symlinks (libz.so.1 ->
    libz.so.1.4). An APK's jniLibs cannot carry symlinks, so aliases are
    materialised as real copies: the loader resolves DT_NEEDED by file name.
    """
    libs: dict[str, bytes] = {}
    aliases: dict[str, str] = {}
    binary = None
    want_bin = lock["qemu"]["binary"]
    for filename, deb in debs.items():
        try:
            payload = deb_data_tar(deb)
        except Exception as exc:  # noqa: BLE001
            raise SystemExit(f"{filename}: {exc}")
        with tarfile.open(fileobj=io.BytesIO(payload)) as tf:
            for m in tf:
                member = m.name[2:] if m.name.startswith("./") else m.name
                base = os.path.basename(member)
                in_lib = "files/usr/lib/" in member and (
                    base.endswith(".so") or re.search(r"\.so\.\d", base))
                if m.isfile() and member.endswith(want_bin):
                    binary = tf.extractfile(m).read()
                elif in_lib and m.issym():
                    aliases.setdefault(base, os.path.basename(m.linkname))
                elif in_lib and m.isfile():
                    libs.setdefault(base, tf.extractfile(m).read())
    for _ in range(8):
        pending = {a: t for a, t in aliases.items() if a not in libs}
        if not pending:
            break
        progress = False
        for alias, target in pending.items():
            if target in libs:
                libs[alias] = libs[target]
                progress = True
        if not progress:
            raise SystemExit(f"unresolved library symlinks: {sorted(pending)[:5]}")
    if binary is None:
        raise SystemExit("qemu binary not found in the Termux packages")
    return libs, binary


def jni_name(soname: str) -> str:
    """APK jniLibs only packages files named like `lib*.so`.

    Termux libraries carry version suffixes (`libz.so.1`, `libglib-2.0.so.0`),
    which AGP silently drops from the APK. Rename them to an equally long
    `libz_1.so` / `libglib-2.0_0.so` form so DT_NEEDED strings can be rewritten
    in place without moving the string table.
    """
    if re.fullmatch(r"lib.+\.so", soname):
        return soname
    idx = soname.find(".so.")
    if idx < 3:
        raise SystemExit(f"cannot rename library {soname!r} into a jniLibs-compatible name")
    renamed = soname[:idx] + "_" + soname[idx + 4:] + ".so"
    if len(renamed) != len(soname):
        raise SystemExit(f"rename of {soname!r} changes length; in-place ELF patching is unsafe")
    return renamed


def elf_rename_deps(data: bytes, mapping: dict[str, str]) -> tuple[bytes, list[str]]:
    """Rewrite DT_NEEDED / DT_SONAME strings in place; returns new bytes + changes."""
    if data[:4] != b"\x7fELF" or data[4] != 2 or data[5] != 1:
        return data, []
    e = "<"
    e_phoff = struct.unpack_from(e + "Q", data, 0x20)[0]
    e_phentsize = struct.unpack_from(e + "H", data, 0x36)[0]
    e_phnum = struct.unpack_from(e + "H", data, 0x38)[0]
    loads, dynamic = [], None
    for i in range(e_phnum):
        base = e_phoff + i * e_phentsize
        p_type, _f, p_offset, p_vaddr, _pa, p_filesz, _ms, _al = struct.unpack_from(
            e + "IIQQQQQQ", data, base)
        if p_type == 1:
            loads.append((p_vaddr, p_offset, p_filesz))
        elif p_type == 2:
            dynamic = (p_offset, p_filesz)
    if dynamic is None:
        return data, []

    def v2o(vaddr: int) -> int:
        for va, off, size in loads:
            if va <= vaddr < va + size:
                return off + (vaddr - va)
        raise ValueError(hex(vaddr))

    off, size = dynamic
    entries = []
    for i in range(0, size, 16):
        tag, val = struct.unpack_from(e + "qQ", data, off + i)
        if tag == 0:
            break
        entries.append((tag, val))
    strtab_vaddr = next((v for t, v in entries if t == 5), None)
    if strtab_vaddr is None:
        return data, []
    strtab = v2o(strtab_vaddr)

    out = bytearray(data)
    changes = []
    for tag, val in entries:
        if tag not in (1, 14):  # DT_NEEDED, DT_SONAME
            continue
        start = strtab + val
        end = data.index(b"\0", start)
        old = data[start:end].decode("utf-8", "replace")
        new = mapping.get(old)
        if not new or new == old:
            continue
        if len(new) != len(old):
            raise SystemExit(f"refusing to patch {old!r} -> {new!r}: length differs")
        out[start:end] = new.encode()
        changes.append(old)
    return bytes(out), changes


def extract_kernel_package(apk_bytes: bytes, kern: dict, modules: list[str]) -> tuple[bytes, dict]:
    """Pull the kernel image and the listed .ko files out of an Alpine .apk.

    An Alpine .apk is a concatenation of gzip members (signature, control, data),
    i.e. several tar archives back to back, and kernel modules are shipped
    gzip-compressed (`9p.ko.gz`). Both facts are handled here.
    """
    flat = gzip.decompress(apk_bytes)
    wanted = {m for m in modules}
    want_image = kern["image"]
    found: dict[str, bytes] = {}
    image = None
    off = 0
    while off < len(flat):
        try:
            tf = tarfile.open(fileobj=io.BytesIO(flat[off:]), mode="r:")
        except Exception:  # noqa: BLE001 - end of data / padding
            break
        members = tf.getmembers()
        if not members:
            break
        end = 0
        for m in members:
            end = m.offset_data + ((m.size + 511) // 512) * 512
            if not m.isfile():
                continue
            if m.name == want_image:
                image = tf.extractfile(m).read()
                continue
            base = os.path.basename(m.name)
            if not base.endswith(".ko.gz"):
                continue
            # lib/modules/<release>/kernel/fs/9p/9p.ko.gz -> kernel/fs/9p/9p.ko
            idx = m.name.find("/kernel/")
            rel = (m.name[idx + 1:] if idx >= 0 else base)[:-3]
            if rel in wanted:
                found[os.path.basename(rel)] = gzip.decompress(tf.extractfile(m).read())
        if end <= 0:
            break
        off += end
    if image is None:
        raise SystemExit(f"kernel image {want_image!r} not found in the package")
    missing = {os.path.basename(p) for p in wanted} - set(found)
    if missing:
        raise SystemExit(f"kernel modules missing from the package: {sorted(missing)}")
    return image, found


def install_alpine_packages(pkgs: list[dict], dest: str) -> int:
    """Unpack Alpine .apk packages into `dest` (an extra-rootfs staging tree).

    Used to bake tools the guest needs but the minirootfs lacks: e2fsprogs, so
    `mkfs.ext4` exists inside the VM for the persistent data disk. Signature and
    control members are skipped; only the filesystem payload is copied.

    Symlinks are a problem on Windows (creating them needs privileges), so links
    whose target lives inside the staging tree are materialised as copies, and
    the remaining ones (e.g. `etc/mtab -> /proc/mounts`) are recorded in
    `.capinux-symlinks.json` for build-initramfs.py to recreate.
    """
    if os.path.isdir(dest):
        shutil.rmtree(dest)
    os.makedirs(dest, exist_ok=True)
    count = 0
    pending_links: dict[str, str] = {}
    # Windows cannot store POSIX exec bits, so remember each member's mode and let
    # build-initramfs.py restore it (otherwise /sbin/mkfs.ext4 lands as 0666).
    modes: dict[str, int] = {}
    for pkg in pkgs:
        url = "https://dl-cdn.alpinelinux.org/alpine/v3.23/main/aarch64/" + pkg["file"]
        blob = fetch(url, cache_name=pkg["file"], expect_sha256=pkg["sha256"],
                     expect_bytes=pkg["bytes"])
        flat = gzip.decompress(blob)
        off = 0
        while off < len(flat):
            try:
                tf = tarfile.open(fileobj=io.BytesIO(flat[off:]), mode="r:")
            except Exception:  # noqa: BLE001
                break
            members = tf.getmembers()
            if not members:
                break
            end = 0
            for m in members:
                end = m.offset_data + ((m.size + 511) // 512) * 512
                name = m.name.lstrip("./")
                if not name or name.startswith("."):
                    continue
                out = os.path.join(dest, name)
                parent = os.path.dirname(out)
                if parent:
                    os.makedirs(parent, exist_ok=True)
                if m.issym():
                    pending_links[name] = m.linkname
                    count += 1
                elif m.isfile():
                    if os.path.lexists(out):
                        os.remove(out)
                    with open(out, "wb") as fh:
                        fh.write(tf.extractfile(m).read())
                    os.chmod(out, m.mode & 0o7777)
                    modes[name] = m.mode & 0o7777
                    count += 1
            if end <= 0:
                break
            off += end
        log(f"  {pkg['name']:16s} {pkg['version']:18s} {pkg['bytes'] / 1024:6.0f} KB  ok")

    # Resolve links that point inside the tree into real copies; keep the rest.
    unresolved = dict(pending_links)
    for _ in range(8):
        if not unresolved:
            break
        progressed = False
        for name, target in list(unresolved.items()):
            if target.startswith("/"):
                continue  # absolute: keep as a symlink in the cpio
            resolved = os.path.normpath(os.path.join(os.path.dirname(name), target)).replace("\\", "/")
            src = os.path.join(dest, resolved)
            out = os.path.join(dest, name)
            if os.path.isfile(src):
                shutil.copyfile(src, out)
                unresolved.pop(name)
                progressed = True
        if not progressed:
            break
    with open(os.path.join(dest, ".capinux-symlinks.json"), "w", encoding="utf-8") as fh:
        json.dump(unresolved, fh, indent=2, sort_keys=True)
    with open(os.path.join(dest, ".capinux-modes.json"), "w", encoding="utf-8") as fh:
        json.dump(modes, fh, indent=2, sort_keys=True)
    return count


def closure(libs: dict[str, bytes], binary: bytes) -> list[str]:
    """Transitive DT_NEEDED closure of the qemu binary, resolved to lib names."""
    by_name = dict(libs)
    needed: list[str] = []

    def walk(data: bytes, owner: str) -> None:
        deps, runpath = elf_needed(data)
        for dep in deps:
            if dep in SYSTEM_LIBS:
                continue
            if dep not in by_name:
                raise SystemExit(
                    f"{owner} needs {dep}, which none of the extracted Termux packages provide. "
                    "Add the providing package to runtime.lock.json's qemu closure.")
            if dep in needed:
                continue
            needed.append(dep)
            walk(by_name[dep], dep)

    walk(binary, "qemu-system-aarch64")
    return needed


# --------------------------------------------------------------------- main
def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--python", default=sys.executable, help="python used for build-initramfs.py")
    ap.add_argument("--no-cache", action="store_true", help="ignore cached downloads")
    args = ap.parse_args()

    lock = json.load(open(LOCK, encoding="utf-8"))
    al = lock["alpine"]
    base = al["releaseBase"]
    log(f"== capinux runtime: Alpine {al['version']} "
        f"(kernel {al['kernel']['release']}) + QEMU {lock['qemu']['version']}")

    if args.no_cache and os.path.isdir(CACHE):
        shutil.rmtree(CACHE)
    os.makedirs(CACHE, exist_ok=True)
    for d in (OUT_ASSETS, OUT_JNI):
        os.makedirs(d, exist_ok=True)
    # Start from a clean output: a stale library with an old name would still be
    # packaged and could shadow the patched one at runtime.
    for d in (OUT_ASSETS, OUT_JNI):
        for name in os.listdir(d):
            path = os.path.join(d, name)
            if os.path.isfile(path):
                os.remove(path)

    # 1. Kernel image + kernel modules, both out of the same Alpine package.
    log("== kernel package (alpine " + al["kernel"]["package"] + " " + al["kernel"]["version"] + ")")
    kern = al["kernel"]
    apk = fetch(kern["url"], cache_name=os.path.basename(kern["url"]),
                expect_sha256=kern["sha256"], expect_bytes=kern["bytes"])
    kernel_image, found = extract_kernel_package(apk, kern, al["modules"])
    log(f"  kernel image {len(kernel_image) / 1e6:.2f} MB ({kern['image']})")
    log("  " + ", ".join(f"{k} ({len(v) / 1024:.0f} KB)" for k, v in sorted(found.items())))
    moddir = os.path.join(CACHE, "modules")
    os.makedirs(moddir, exist_ok=True)
    for name, payload in found.items():
        with open(os.path.join(moddir, name), "wb") as fh:
            fh.write(payload)

    # 2. Alpine minirootfs (the userland that becomes the rootfs).
    log("== alpine artifacts")
    blobs = {}
    for key, meta in al["artifacts"].items():
        blobs[key] = fetch(f"{base}/{meta['path']}", cache_name=os.path.basename(meta["path"]),
                           expect_sha256=meta["sha256"], expect_bytes=meta["bytes"])
        log(f"  {key:12s} {meta['bytes'] / 1e6:7.2f} MB  ok")

    # 3. Offline rootfs (initramfs) built from the Alpine minirootfs.
    # 注意：AAPT 会把 assets 里以 .gz 结尾的文件改名（去掉 .gz），所以这里就
    # 用 rootfs.cpio 这个名字；内容仍然是 gzip，内核按 magic 字节识别。
    log("== rootfs (offline initramfs)")
    # Extra packages the guest needs at runtime (e2fsprogs -> mkfs.ext4 for the
    # persistent data disk). Baked in at build time, so still fully offline.
    extra_root = os.path.join(CACHE, "extra-rootfs")
    installed = install_alpine_packages(al.get("persist", {}).get("packages", []), extra_root)
    log(f"  extra files: {installed}")
    rootfs_out = os.path.join(OUT_ASSETS, "rootfs.cpio")
    # 同样避开 AAPT 去掉 .gz 的坑：文件叫 rootfs.tar，内容仍是 gzip
    # （内核/客户机里的 busybox tar 都按 magic 识别，不看扩展名）。
    rootfs_tar = os.path.join(OUT_ASSETS, "rootfs.tar")
    mini = os.path.join(CACHE, os.path.basename(al["artifacts"]["minirootfs"]["path"]))
    cmd = [args.python, os.path.join(HERE, "build-initramfs.py"),
           "--rootfs", mini, "--out", rootfs_out,
           "--init", os.path.join(HERE, "vm", "init.sh"),
           # 磁盘启动时 initramfs 里的 /init 换成 bootstrap（mkfs + 解包 + switch_root）
           "--bootstrap-init", os.path.join(HERE, "vm", "init-bootstrap.sh"),
           # 同一棵树再导出一份 tar.gz：持久化磁盘镜像的初始内容
           "--tar-out", rootfs_tar,
           "--udhcpc", os.path.join(HERE, "vm", "udhcpc.sh"),
           "--extra-rootfs", extra_root,
           "--modules-dir", moddir,
           # build-initramfs copies modules by file name; the package lists them
           # by in-tree path (kernel/fs/9p/9p.ko), so pass the basenames.
           "--modules", " ".join(os.path.basename(m) for m in al["modules"]),
           # 磁盘栈必须同步加载（mount 之前）；net/9p 放到后台（TCG 下 insmod 很慢）
           "--disk-modules", " ".join(
               os.path.basename(m) for m in al["modules"]
               if any(k in m for k in ("/ext4", "/jbd2", "/mbcache", "/crc16",
                                       "virtio_mmio", "virtio_blk"))),
           "--background-modules", " ".join(
               os.path.basename(m) for m in al["modules"]
               if any(k in m for k in ("/9p/", "/netfs", "virtio_net", "failover"))),
           "--version", al["version"], "--kernel", al["kernel"]["release"]]
    subprocess.run(cmd, check=True)
    lint = subprocess.run([args.python, os.path.join(HERE, "cpio-lint.py"), rootfs_out],
                          capture_output=True, text=True)
    log("  " + lint.stdout.strip().replace("\n", "\n  "))
    if lint.returncode != 0:
        raise SystemExit("initramfs lint failed: the kernel would silently drop entries")

    # 4. QEMU for Android arm64 + its library closure.
    log("== qemu (Termux " + lock["qemu"]["provider"] + ")")
    debs = resolve_debs(lock)
    libs, qemu_bin = unpack_termux(debs, lock)
    log(f"  qemu binary {len(qemu_bin) / 1e6:.1f} MB, {len(libs)} candidate libs")
    used = closure(libs, qemu_bin)
    lib_bytes = sum(len(libs[n]) for n in used)
    log(f"  closure: {len(used)} libs, {lib_bytes / 1e6:.1f} MB")
    for name in used:
        log(f"    {name}")

    # 5. Write assets + jniLibs.
    log("== write runtime/android")
    kernel_out = os.path.join(OUT_ASSETS, "kernel")
    with open(kernel_out, "wb") as fh:
        fh.write(kernel_image)

    # Rename versioned libraries into jniLibs-compatible `lib*.so` names and
    # rewrite DT_NEEDED/SONAME in place. Without this AGP drops every
    # `libz.so.1`-style file from the APK and QEMU cannot even start.
    mapping = {name: jni_name(name) for name in used if jni_name(name) != name}
    if mapping:
        log(f"  renaming {len(mapping)} versioned libs for jniLibs: "
            + ", ".join(f"{k}->{v}" for k, v in list(mapping.items())[:4]) + (" ..." if len(mapping) > 4 else ""))

    patched_qemu, qemu_changes = elf_rename_deps(qemu_bin, mapping)
    jni_qemu = os.path.join(OUT_JNI, "libqemu-system-aarch64.so")
    with open(jni_qemu, "wb") as fh:
        fh.write(patched_qemu)

    for name in used:
        patched, _changes = elf_rename_deps(libs[name], mapping)
        with open(os.path.join(OUT_JNI, mapping.get(name, name)), "wb") as fh:
            fh.write(patched)

    def digest(path: str) -> dict:
        data = open(path, "rb").read()
        return {"bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}

    manifest = {
        "generatedBy": "scripts/fetch-linux-runtime.py",
        "distribution": "alpine",
        "distributionVersion": al["version"],
        "kernelRelease": al["kernel"]["release"],
        "kernel": {"asset": "linux/kernel", "package": al["kernel"]["package"] + " " + al["kernel"]["version"], **digest(kernel_out)},
        "rootfs": {"asset": "linux/rootfs.cpio", "compression": "gzip", **digest(rootfs_out)},
        "rootfsDisk": {"asset": "linux/rootfs.tar", "compression": "gzip",
                       "note": "persistent disk payload (ext4 image is created by the guest)",
                       **digest(rootfs_tar)},
        "qemu": {
            "provider": lock["qemu"]["provider"],
            "version": lock["qemu"]["version"],
            "binary": "libqemu-system-aarch64.so",
            "libraries": [mapping.get(n, n) for n in used],
            "libraryRename": mapping,
            "bytes": len(qemu_bin) + lib_bytes,
        },
        "modules": sorted(os.path.basename(m) for m in al["modules"]),
        "console": {"stdout": "/dev/ttyAMA0", "stderr": "/dev/ttyAMA1"},
        "readyMarker": "capinux: ready",
    }
    with open(os.path.join(OUT_ASSETS, "manifest.json"), "w", encoding="utf-8") as fh:
        json.dump(manifest, fh, ensure_ascii=False, indent=2)
        fh.write("\n")

    total = sum(digest(p)["bytes"] for p in (kernel_out, rootfs_out, jni_qemu))
    total += lib_bytes
    log(f"== done: {total / 1e6:.1f} MB total runtime "
        f"(kernel {len(kernel_image) / 1e6:.1f} + rootfs "
        f"{os.path.getsize(rootfs_out) / 1e6:.1f} + qemu {(len(qemu_bin) + lib_bytes) / 1e6:.1f})")
    log("   next: npm run build:plugins  (the app then picks up assets + jniLibs)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
 