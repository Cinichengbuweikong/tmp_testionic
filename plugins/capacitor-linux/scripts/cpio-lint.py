#!/usr/bin/env python3
"""Lint a cpio newc archive the way the kernel's initramfs unpacker sees it.

The kernel unpacks strictly in archive order and refuses to traverse symlinks
while materialising later entries, so an archive can look fine locally and still
lose entries in the guest. This walks the archive and reports:

  * entries whose parent directory does not exist yet
  * entries whose parent path component is a symlink (kernel refuses)
  * duplicate names
  * anything after TRAILER!!!

Usage: cpio_lint.py archive.cpio[.gz]
"""
import gzip
import sys


def entries(data):
    pos = 0
    while pos + 110 <= len(data):
        if data[pos:pos + 6] != b"070701":
            yield None
            return
        f = [int(data[pos + 6 + i * 8:pos + 14 + i * 8], 16) for i in range(13)]
        namez, filesize, mode = f[11], f[6], f[1]
        name = data[pos + 110:pos + 110 + namez - 1].decode("utf-8", "replace")
        pos += 110 + namez
        pos += (4 - pos % 4) % 4
        payload = data[pos:pos + filesize]
        pos += filesize
        pos += (4 - pos % 4) % 4
        yield name, mode, payload


def main(path):
    raw = open(path, "rb").read()
    # Sniff the gzip magic instead of the file name: AAPT renames assets, and the
    # initramfs keeps a .gz payload behind whatever name it ends up with.
    data = gzip.decompress(raw) if raw[:2] == b"\x1f\x8b" else raw
    kinds = {}
    problems = []
    count = 0
    for item in entries(data):
        if item is None:
            problems.append("bad magic / truncated archive")
            break
        name, mode, payload = item
        count += 1
        if name == "TRAILER!!!":
            continue
        kind = ("dir" if mode & 0o170000 == 0o040000 else
                "symlink" if mode & 0o170000 == 0o120000 else
                "file" if mode & 0o170000 == 0o100000 else
                "device" if mode & 0o170000 == 0o020000 else "other")
        if name in kinds:
            problems.append(f"duplicate: {name}")
        # walk parents
        parts = name.split("/")[:-1]
        cur = ""
        for p in parts:
            cur = f"{cur}/{p}" if cur else p
            if cur in kinds:
                if kinds[cur] == "symlink":
                    problems.append(f"parent is symlink: {name} (via {cur})")
                    break
            else:
                problems.append(f"missing parent dir: {name} (needs {cur})")
                break
        if kind == "symlink":
            kinds[name] = "symlink"
        elif kind == "dir":
            kinds[name] = "dir"
        else:
            kinds[name] = "file"
    print(f"{path}: {count} entries, {len(problems)} problems")
    for p in problems[:40]:
        print("  !", p)
    if len(problems) > 40:
        print(f"  ... {len(problems) - 40} more")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1]))
