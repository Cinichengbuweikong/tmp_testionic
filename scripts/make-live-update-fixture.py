#!/usr/bin/env python3
"""生成 live-update 插件的「离线自检夹具」，并保存测试用签名私钥。

────────────────────────────────────────────────────────────────────────────
它产出什么
────────────────────────────────────────────────────────────────────────────
1. `src/plugin-test-fixtures.js`
   - 一个**真实的** deflate 压缩 zip（内含 index.html + 2 个资源 + 1 个中文文件名）；
   - 它的 SHA-256（hex）；
   - 它的 ECDSA P-256 签名（DER 编码后 base64）；
   - 一个**故意损坏**的签名（用于验证"验签失败必须拒绝"）；
   - 对应的 PEM 公钥。
   App 的 autotest 会用这份数据调用 `LiveUpdate.verifyBundle()`，在 Android / iOS
   两端验证「zip 解析 + SHA-256 + 验签 + 解压」整条链路，**不需要联网**、不需要起服务器。

2. `.perf/ota-bundle.zip`
   同一份 zip 落盘，方便配合 `scripts/dev-ota-server.py` 做端到端联调（可选）。

3. `.perf/fixture-key.pem`
   P-256 私钥（PKCS#8 PEM）。**已存在的会复用**，所以公钥不会每次重跑都变；
   这把钥匙只用于本地测试，`.perf/` 在 `.gitignore` 里，不会进仓库。

────────────────────────────────────────────────────────────────────────────
为什么这么设计
────────────────────────────────────────────────────────────────────────────
- **必须是真 zip（deflate）**：iOS 的 ZipArchive 是自己实现的（Foundation 没有解压 API），
  如果夹具用 store（不压缩）方式，就测不出 raw-DEFLATE 解压那段代码；用 deflate 才有效。
- **必须有中文文件名**：验证 zip 文件名按 UTF-8 解码、路径拼接没问题（两边都容易写错）。
- **签名覆盖整个 zip 字节**：这和后端协议、两端实现（Java `SHA256withECDSA` /
  CryptoKit `P256.Signing`）完全一致；任何字节被改动都会验签失败。
- **同时生成"损坏签名"**：autotest 要能验证"验签失败 → 拒绝安装"，而不是只测成功路径。

────────────────────────────────────────────────────────────────────────────
用法
────────────────────────────────────────────────────────────────────────────
    python scripts/make-live-update-fixture.py              # 重新生成夹具（复用已存在的私钥）
    python scripts/make-live-update-fixture.py sign a.zip   # 用同一把私钥给任意 zip 签名并打印
                                                            # sha256 / signature / publicKey（JSON）
                                                            # —— 本地做"真 OTA 安装"测试时用

依赖：`pip install cryptography`（本仓库开发机已安装）。
"""
import base64
import hashlib
import io
import json
import os
import sys
import zipfile

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

# 仓库根目录（本文件在 scripts/ 下）
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# ── 夹具 zip 的内容 ─────────────────────────────────────────────────────────
# 故意包含：
#   - 根目录的 index.html（插件会校验它存在，缺了就不算有效 bundle）
#   - 子目录 assets/（测嵌套路径）
#   - 中文文件名 assets/中文.txt（测 UTF-8 文件名）
FILES = {
    "index.html": (
        "<!doctype html>\n<html><head><meta charset=\"utf-8\">\n"
        "<title>OTA bundle (test fixture)</title>\n"
        "<link rel=\"stylesheet\" href=\"assets/style.css\"></head>\n"
        "<body><h1 id=\"ota-marker\">OTA TEST BUNDLE</h1>\n"
        "<script src=\"assets/app.js\"></script></body></html>\n"
    ),
    "assets/app.js": (
        "// 这段脚本会出现在解压后的 bundle 里，用于确认 zip 内的文件没被破坏\n"
        "window.__otaFixture = { version: '9.9.9', env: 'fixture' };\n"
        "console.log('OTA fixture bundle loaded');\n"
    ),
    "assets/style.css": "body { font-family: sans-serif; background: #101418; color: #eee; }\n",
    "assets/中文.txt": "中文文件名也要能正确解压。\n",
}


def build_zip() -> bytes:
    """把 FILES 压成一个 deflate zip（每个条目单独用 ZIP_DEFLATED）。"""
    buffer = io.BytesIO()
    # compresslevel=9：让压缩率固定，保证同样的输入产出同样的字节（便于排查）
    with zipfile.ZipFile(buffer, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for name, content in FILES.items():
            info = zipfile.ZipInfo(name)
            info.compress_type = zipfile.ZIP_DEFLATED
            # 0o644 << 16 是 zip 里保存 unix 文件权限的字段（解压端可以忽略）
            info.external_attr = 0o644 << 16
            archive.writestr(info, content.encode("utf-8"))
    return buffer.getvalue()


def key_path() -> str:
    return os.path.join(ROOT, ".perf", "fixture-key.pem")


def load_or_create_key():
    """复用 `.perf/fixture-key.pem`；没有就生成一把 P-256 私钥并落盘。

    复用的原因：App 里 `configure({ publicKey })` 配置的是公钥，如果每次重跑都换钥匙，
    之前配好的公钥就失效了，联调会很烦。
    """
    path = key_path()
    if os.path.exists(path):
        with open(path, "rb") as handle:
            return serialization.load_pem_private_key(handle.read(), password=None)

    private_key = ec.generate_private_key(ec.SECP256R1())
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as handle:
        handle.write(
            private_key.private_bytes(
                encoding=serialization.Encoding.PEM,
                format=serialization.PrivateFormat.PKCS8,
                encryption_algorithm=serialization.NoEncryption(),
            )
        )
    print(f"[info] 生成新的测试私钥：{path}（.perf/ 已 gitignore）")
    return private_key


def main() -> None:
    # 1) 生成 zip 并算 SHA-256（插件会用同一个算法校验下载到的文件）
    payload = build_zip()
    sha256 = hashlib.sha256(payload).hexdigest()

    # 2) 取私钥 / 公钥
    private_key = load_or_create_key()
    public_key = private_key.public_key()

    # 3) 对 zip **整个文件的字节**签名（ECDSA P-256 + SHA-256，DER 编码）
    der_signature = private_key.sign(payload, ec.ECDSA(hashes.SHA256()))
    signature_b64 = base64.b64encode(der_signature).decode()

    # 4) 公钥导出成 PEM（Java 侧用 X509EncodedKeySpec 解析，CryptoKit 用 pemRepresentation）
    public_pem = public_key.public_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PublicFormat.SubjectPublicKeyInfo,
    ).decode()

    # 5) 造一个"看起来是 base64、但签名内容不对"的签名：只改 DER 的第一个字节。
    #    注意不能改 base64 字符本身（可能变成非法 base64），要改解码后的字节。
    tampered = bytearray(der_signature)
    tampered[0] = (tampered[0] + 1) % 256
    tampered_signature_b64 = base64.b64encode(bytes(tampered)).decode()

    # 6) 输出给 JS 用的夹具模块（被 src/views/PluginTestView.vue 的 autotest 引用）
    js = (
        "// 由 scripts/make-live-update-fixture.py 生成，请勿手改。\n"
        "// 这是一份真实签名过的 deflate zip：用于三端自检 zip 解析 + SHA-256 + ECDSA 验签。\n"
        "export const LIVE_UPDATE_FIXTURE = "
        + json.dumps(
            {
                "zipBase64": base64.b64encode(payload).decode(),
                "sha256": sha256,
                "signatureBase64": signature_b64,
                "tamperedSignatureBase64": tampered_signature_b64,
                "publicKeyPem": public_pem,
                "files": list(FILES.keys()),
                "bytes": len(payload),
            },
            ensure_ascii=False,
            indent=2,
        )
        + ";\n"
    )

    out_path = os.path.join(ROOT, "src", "plugin-test-fixtures.js")
    with open(out_path, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(js)

    # 7) 顺手把 zip 落到 .perf/，方便 dev-ota-server.py 之类的手工联调
    perf_dir = os.path.join(ROOT, ".perf")
    os.makedirs(perf_dir, exist_ok=True)
    with open(os.path.join(perf_dir, "ota-bundle.zip"), "wb") as handle:
        handle.write(payload)

    print(f"fixture zip: {len(payload)} bytes, sha256={sha256}")
    print(f"wrote {out_path}")
    print(f"wrote {os.path.join(perf_dir, 'ota-bundle.zip')}")


def sign_file(path: str) -> None:
    """给任意 zip 签名，并打印插件后端协议需要的三个字段（JSON）。

    用途：本地做"真的安装一次热更新"的端到端测试 ——
    你自己打包一个 zip（比如当前 `dist/`），用这个命令签名，再把输出塞进
    `latest.json`（或者改用 scripts/dev-ota-server.py，它内置了签名逻辑）。
    """
    with open(path, "rb") as handle:
        payload = handle.read()
    private_key = load_or_create_key()
    public_key = private_key.public_key()
    signature = private_key.sign(payload, ec.ECDSA(hashes.SHA256()))
    public_pem = public_key.public_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PublicFormat.SubjectPublicKeyInfo,
    ).decode()
    print(
        json.dumps(
            {
                "sha256": hashlib.sha256(payload).hexdigest(),
                "size": len(payload),
                "signature": base64.b64encode(signature).decode(),
                "publicKey": public_pem,
            },
            indent=2,
        )
    )


if __name__ == "__main__":
    # 两种模式：默认生成夹具；`sign <zip>` 只签名并打印字段
    if len(sys.argv) >= 3 and sys.argv[1] == "sign":
        sign_file(sys.argv[2])
    else:
        main()
