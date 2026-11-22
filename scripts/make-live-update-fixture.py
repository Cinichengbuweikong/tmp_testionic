#!/usr/bin/env python3
"""生成 live-update 插件的自检夹具（zip + SHA-256 + ECDSA P-256 签名）。

用途：`LiveUpdate.verifyBundle()` 的自检需要一份**真实**的 deflate zip 与真签名。
放在仓库里可以让 Android / iOS / 浏览器三端用同一份数据做验证，不需要联网。

用法：
    python scripts/make-live-update-fixture.py            # 重新生成夹具
    python scripts/make-live-update-fixture.py sign a.zip # 用同一把私钥给任意 zip 签名（本地 E2E 测试用）

私钥保存在 `.perf/fixture-key.pem`（.perf/ 已在 .gitignore 里）；已存在时会复用，
这样公钥不会每次都变，App 里配置的公钥可以一直有效。

输出：src/plugin-test-fixtures.js
"""
import base64
import hashlib
import io
import json
import os
import subprocess
import sys
import zipfile
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

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
    # 中文文件名同时验证 UTF-8 文件名解析
    "assets/中文.txt": "中文文件名也要能正确解压。\n",
}

PASSWORD = b"fixture"


def build_zip() -> bytes:
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for name, content in FILES.items():
            info = zipfile.ZipInfo(name)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            archive.writestr(info, content.encode("utf-8"))
    return buffer.getvalue()


def key_path() -> str:
    return os.path.join(ROOT, ".perf", "fixture-key.pem")


def load_or_create_key():
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
    payload = build_zip()
    sha256 = hashlib.sha256(payload).hexdigest()

    private_key = load_or_create_key()
    public_key = private_key.public_key()

    der_signature = private_key.sign(payload, ec.ECDSA(hashes.SHA256()))
    # 转成 Java/CryptoKit 都能直接用的 DER 编码（cryptography 默认已经是 DER）
    signature_b64 = base64.b64encode(der_signature).decode()

    public_pem = public_key.public_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PublicFormat.SubjectPublicKeyInfo,
    ).decode()

    zip_b64 = base64.b64encode(payload).decode()

    # 一个"看起来合法但签名不对"的签名：把 DER 的第一个字节换掉
    tampered = bytearray(der_signature)
    tampered[0] = (tampered[0] + 1) % 256
    tampered_signature_b64 = base64.b64encode(bytes(tampered)).decode()

    js = (
        "// 由 scripts/make-live-update-fixture.py 生成，请勿手改。\n"
        "// 这是一份真实签名过的 deflate zip：用于三端自检 zip 解析 + SHA-256 + ECDSA 验签。\n"
        "export const LIVE_UPDATE_FIXTURE = "
        + json.dumps(
            {
                "zipBase64": zip_b64,
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

    # 顺便把 zip 落到 .perf/，方便本机起一个 HTTP 服务做端到端热更新实测
    perf_dir = os.path.join(ROOT, ".perf")
    os.makedirs(perf_dir, exist_ok=True)
    with open(os.path.join(perf_dir, "ota-bundle.zip"), "wb") as handle:
        handle.write(payload)

    print(f"fixture zip: {len(payload)} bytes, sha256={sha256}")
    print(f"wrote {out_path}")
    print(f"wrote {os.path.join(perf_dir, 'ota-bundle.zip')}")


def sign_file(path: str) -> None:
    """给任意 zip 签名并打印 sha256 / signature / publicKey（本地 E2E 热更新测试用）。"""
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
    if len(sys.argv) >= 3 and sys.argv[1] == "sign":
        sign_file(sys.argv[2])
    else:
        main()
