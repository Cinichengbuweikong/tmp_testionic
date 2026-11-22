package com.testionic.liveupdate;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * 更新包校验：SHA-256 摘要 + ECDSA P-256 签名。
 *
 * 约定：后端用 ECDSA P-256（SHA-256）对**整个 zip 文件的字节**签名，签名用 DER 编码、
 * base64 传输；公钥用 PEM（`-----BEGIN PUBLIC KEY-----`）或裸 base64 的 X.509
 * SubjectPublicKeyInfo。
 *
 * 为什么签整个文件：这样包里的任何字节都受保护，不需要再单独校验 manifest；
 * 而 SHA-256 摘要又让"只签 manifest"的实现也能复用（后端可以把 manifest 哈希也放进去）。
 */
public final class UpdateVerifier {

    private UpdateVerifier() {
    }

    public static String sha256Hex(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
        }
        return toHex(digest.digest());
    }

    public static String toHex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            builder.append(String.format("%02x", value));
        }
        return builder.toString();
    }

    /** 把 PEM / 裸 base64 的公钥解析成 `PublicKey`（EC / P-256）。 */
    public static PublicKey parsePublicKey(String pemOrBase64) throws Exception {
        String normalized = pemOrBase64
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replace("-----BEGIN EC PUBLIC KEY-----", "")
                .replace("-----END EC PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(normalized);
        X509EncodedKeySpec spec = new X509EncodedKeySpec(der);
        return KeyFactory.getInstance("EC").generatePublic(spec);
    }

    /**
     * 校验签名。`signatureBase64` 是 DER 编码的 ECDSA 签名。
     *
     * 采用流式 `Signature.update`，避免把几十 MB 的包整体读进内存。
     */
    public static boolean verifyFileSignature(File file, String signatureBase64, String publicKeyText)
            throws Exception {
        PublicKey publicKey = parsePublicKey(publicKeyText);
        Signature signature = Signature.getInstance("SHA256withECDSA");
        signature.initVerify(publicKey);
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) > 0) {
                signature.update(buffer, 0, read);
            }
        }
        byte[] signatureBytes = Base64.getDecoder().decode(signatureBase64.replaceAll("\\s", ""));
        return signature.verify(signatureBytes);
    }

    /** 通过 `Signature.update` 校验一段内存数据（用于小文件/测试）。 */
    public static boolean verifyBytes(byte[] data, String signatureBase64, String publicKeyText)
            throws Exception {
        PublicKey publicKey = parsePublicKey(publicKeyText);
        Signature signature = Signature.getInstance("SHA256withECDSA");
        signature.initVerify(publicKey);
        signature.update(data);
        return signature.verify(Base64.getDecoder().decode(signatureBase64.replaceAll("\\s", "")));
    }

    public static byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        int read;
        while ((read = input.read(buffer)) > 0) {
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    public static String utf8(byte[] data) {
        return new String(data, StandardCharsets.UTF_8);
    }
}
