#!/usr/bin/env python3
"""本地热更新后端（仅用于开发/联调，不要用于生产）。

它做的事就是 README 里描述的最小协议：
  GET /latest.json  -> {"version","url","sha256","signature","size","notes"}
  GET /bundle.zip   -> 打包好的 web 资源（deflate zip，内含 index.html）

用法：
    python scripts/dev-ota-server.py --dist dist --port 8899 --version 9.9.9
    # 手机通过 USB 访问电脑：adb reverse tcp:8899 tcp:8899
    # 然后在 App 里 configure({ serverUrl: 'http://127.0.0.1:8899', publicKey: <打印出来的公钥> })

签名默认用 `.perf/fixture-key.pem`（`scripts/make-live-update-fixture.py` 生成），
也可以用 `--key` 指定自己的 EC P-256 私钥（PEM/PKCS8）。
"""
import argparse
import base64
import hashlib
import io
import json
import os
import sys
import zipfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def zip_dir(directory: str) -> bytes:
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
        for base, _, files in os.walk(directory):
            for name in sorted(files):
                full = os.path.join(base, name)
                relative = os.path.relpath(full, directory).replace(os.sep, "/")
                archive.write(full, relative)
    return buffer.getvalue()


def load_key(path: str):
    with open(path, "rb") as handle:
        return serialization.load_pem_private_key(handle.read(), password=None)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dist", default=os.path.join(ROOT, "dist"))
    parser.add_argument("--port", type=int, default=8899)
    parser.add_argument("--version", default="9.9.9")
    parser.add_argument("--notes", default="local OTA bundle (dev server)")
    parser.add_argument("--key", default=os.path.join(ROOT, ".perf", "fixture-key.pem"))
    args = parser.parse_args()

    if not os.path.isdir(args.dist):
        sys.exit(f"dist 目录不存在：{args.dist}（先 npm run build）")
    if not os.path.exists(args.key):
        sys.exit(
            f"找不到私钥 {args.key}；先跑一次 python scripts/make-live-update-fixture.py 生成"
        )

    private_key = load_key(args.key)
    public_pem = private_key.public_key().public_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PublicFormat.SubjectPublicKeyInfo,
    ).decode()

    payload = zip_dir(args.dist)
    sha256 = hashlib.sha256(payload).hexdigest()
    signature = private_key.sign(payload, ec.ECDSA(hashes.SHA256()))
    base_url = f"http://127.0.0.1:{args.port}"

    manifest = {
        "version": args.version,
        "url": f"{base_url}/bundle.zip",
        "sha256": sha256,
        "signature": base64.b64encode(signature).decode(),
        "size": len(payload),
        "notes": args.notes,
    }

    print(f"[info] bundle: {len(payload)} bytes, sha256={sha256}")
    print(f"[info] serving on {base_url}")
    print("[info] publicKey (配置到 LiveUpdate.configure):")
    print(public_pem)

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, fmt, *argv):  # noqa: N802
            print(f"[http] {self.address_string()} {fmt % argv}")

        def do_GET(self):  # noqa: N802
            path = urlparse(self.path).path
            if path == "/latest.json":
                body = json.dumps(manifest).encode()
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)
            elif path == "/bundle.zip":
                self.send_response(200)
                self.send_header("Content-Type", "application/zip")
                self.send_header("Content-Length", str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)
            else:
                self.send_response(404)
                self.end_headers()

    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    server.serve_forever()


if __name__ == "__main__":
    main()
