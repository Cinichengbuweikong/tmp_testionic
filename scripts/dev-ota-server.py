#!/usr/bin/env python3
"""本地热更新后端（**仅用于开发/联调**，不要用于生产）。

────────────────────────────────────────────────────────────────────────────
它是干什么的
────────────────────────────────────────────────────────────────────────────
它是 `capacitor-live-update` 插件 README 里那套「最小后端协议」的可运行实现：

    GET /latest.json  →  {
                            "version": "9.9.9",
                            "url": "http://127.0.0.1:8899/bundle.zip",
                            "sha256": "<zip 全文件 SHA-256，hex>",
                            "signature": "<ECDSA P-256 + SHA-256 对 zip 全文件签名，base64(DER)>",
                            "size": 123456,
                            "notes": "本地联调包"
                          }
    GET /bundle.zip   →  把 `--dist` 目录压成 deflate zip 返回（内含 index.html）

App 端流程（对应插件 API）：

    LiveUpdate.configure({ serverUrl: 'http://127.0.0.1:8899', publicKey: <脚本打印的 PEM> })
    LiveUpdate.checkForUpdate()      → 拿到 version/url/sha256/signature
    LiveUpdate.downloadAndInstall()  → 下载 → 验 SHA-256 → 验签 → 解压 → 切换 → reload

────────────────────────────────────────────────────────────────────────────
为什么这么实现（对应插件的安全设计）
────────────────────────────────────────────────────────────────────────────
- **签名对 zip 的原始字节**：客户端校验的也是文件字节，不需要额外解析"清单"，
  少一层解析就少一类漏洞；任何字节被改都会让 SHA-256 与签名同时失败。
- **同时提供 sha256 与 signature**：sha256 防"传输损坏/被换包"，签名防"有人自己造包"。
  两者都过才安装（插件默认要求二者，除非显式 `allowUnsigned: true`）。
- **服务只绑 127.0.0.1**：开发机上不对外暴露；手机通过 USB 用
  `adb reverse tcp:8899 tcp:8899` 访问电脑的回环地址（插件允许明文 http 仅限回环）。
- **每次启动重新打包 + 重新签名**：改完 web 代码重启脚本就能测新一轮，避免"改了没生效"。
- **没有鉴权/限流/TLS**：这是开发工具，别拿它上生产；生产后端至少要有 HTTPS、
  鉴权、灰度、审计日志。

────────────────────────────────────────────────────────────────────────────
用法
────────────────────────────────────────────────────────────────────────────
    # 1) 先构建 web 资源（脚本会把 dist/ 打包）
    npm run build

    # 2) 起本地后端（会打印 App 端要用的 publicKey）
    python scripts/dev-ota-server.py --dist dist --port 8899 --version 9.9.9

    # 3) 让手机能访问电脑的回环地址（USB 连着的设备）
    adb reverse tcp:8899 tcp:8899

    # 4) 在 App 里配置 serverUrl 与 publicKey，然后 checkForUpdate / downloadAndInstall

可选参数：
    --dist <目录>     要打包的 web 资源目录（默认仓库根的 dist/）
    --port <端口>     监听端口（默认 8899，只绑 127.0.0.1）
    --version <版本>  返回给客户端的版本号（默认 9.9.9；决定"有没有更新"）
    --notes <文本>    latest.json 里的说明文字
    --key <PEM>       签名私钥（默认 .perf/fixture-key.pem；由 make-live-update-fixture.py 生成）

依赖：`pip install cryptography`。
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

# 仓库根目录（本文件在 scripts/ 下）
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def zip_dir(directory: str) -> bytes:
    """把一个目录压成 deflate zip（路径相对于该目录，index.html 在根）。

    注意：打包时**不要**包含 `capacitor.config.json` 之类只对宿主编译期有意义的东西；
    真实项目里通常直接打包 `dist/` 就够了。
    """
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
        # 排序后写入：同样的输入产出同样的 zip（便于 diff/复现）
        for base, _, files in os.walk(directory):
            for name in sorted(files):
                full = os.path.join(base, name)
                relative = os.path.relpath(full, directory).replace(os.sep, "/")
                archive.write(full, relative)
    return buffer.getvalue()


def load_key(path: str):
    """读 PKCS#8 PEM 私钥。"""
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

    # 1) 准备签名密钥与公钥（公钥要配到 App 的 configure 里）
    private_key = load_key(args.key)
    public_pem = private_key.public_key().public_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PublicFormat.SubjectPublicKeyInfo,
    ).decode()

    # 2) 打包 + 摘要 + 签名（启动时算一次，之后所有请求复用同一份字节）
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
            # 把每个请求打到 stdout，便于确认"设备真的来拉了"
            print(f"[http] {self.address_string()} {fmt % argv}")

        def do_GET(self):  # noqa: N802
            path = urlparse(self.path).path
            if path == "/latest.json":
                # 忽略 query（appId/platform/channel/version 等），永远返回"有新版本"，
                # 方便反复测试同一次安装流程。
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

    # 只绑回环：手机通过 `adb reverse` 访问，不需要同 Wi-Fi、也不会暴露到局域网
    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    server.serve_forever()


if __name__ == "__main__":
    main()
