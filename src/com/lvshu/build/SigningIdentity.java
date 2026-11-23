package com.lvshu.build;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;

import java.io.ByteArrayInputStream;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;

public final class SigningIdentity {

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private SigningIdentity() {
    }

    /** 返回 DER 字节的 SHA-256 指纹，格式为无冒号的大写十六进制。 */
    public static String certSha256(byte[] certDer) {
        if (certDer == null || certDer.length == 0) {
            return "";
        }

        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(certDer);
            char[] result = new char[digest.length * 2];
            for (int i = 0; i < digest.length; i++) {
                int b = digest[i] & 0xff;
                result[i * 2] = HEX[b >>> 4];
                result[i * 2 + 1] = HEX[b & 0x0f];
            }
            return new String(result);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("运行环境缺少 SHA-256", e);
        }
    }

    /** 用 PKCS#8 私钥签署随机数据，再用 X.509 证书中的公钥验签。 */
    public static boolean verifyKeypair(byte[] keyDer, byte[] certDer) {
        if (keyDer == null || keyDer.length == 0 || certDer == null || certDer.length == 0) {
            return false;
        }

        try {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            X509Certificate cert = (X509Certificate) factory.generateCertificate(
                    new ByteArrayInputStream(certDer));
            PublicKey publicKey = cert.getPublicKey();
            String keyAlgorithm = publicKey.getAlgorithm();
            String signatureAlgorithm;
            if ("RSA".equalsIgnoreCase(keyAlgorithm)) {
                signatureAlgorithm = "SHA256withRSA";
            } else if ("EC".equalsIgnoreCase(keyAlgorithm)) {
                signatureAlgorithm = "SHA256withECDSA";
            } else if ("DSA".equalsIgnoreCase(keyAlgorithm)) {
                signatureAlgorithm = "SHA256withDSA";
            } else {
                return false;
            }

            PrivateKey privateKey = KeyFactory.getInstance(keyAlgorithm)
                    .generatePrivate(new PKCS8EncodedKeySpec(keyDer));
            SecureRandom random = new SecureRandom();
            byte[] challenge = new byte[32];
            random.nextBytes(challenge);

            Signature signature = Signature.getInstance(signatureAlgorithm);
            signature.initSign(privateKey, random);
            signature.update(challenge);
            byte[] signed = signature.sign();

            signature.initVerify(publicKey);
            signature.update(challenge);
            return signature.verify(signed);
        } catch (GeneralSecurityException | RuntimeException e) {
            return false;
        }
    }

    /** 仅在已安装包有唯一当前签名者时返回指纹。查询失败或签名者不唯一时返回空串。 */
    @SuppressWarnings("deprecation")
    public static String installedSignatureSha256(Context ctx, String pkg) {
        if (ctx == null || pkg == null || pkg.isEmpty()) {
            return "";
        }

        try {
            PackageManager pm = ctx.getPackageManager();
            android.content.pm.Signature[] signers;
            if (Build.VERSION.SDK_INT >= 28) {
                PackageInfo info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES);
                signers = info.signingInfo == null ? null : info.signingInfo.getApkContentsSigners();
            } else {
                PackageInfo info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES);
                signers = info.signatures;
            }
            if (signers == null || signers.length != 1 || signers[0] == null) {
                return "";
            }
            return certSha256(signers[0].toByteArray());
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return "";
        }
    }
}
