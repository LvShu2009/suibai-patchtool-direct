package com.lvshu.build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.LinkedHashMap;
import java.util.Map;

/** 桌面端测试入口：把 base.apk 做成直装包。 */
public final class TestMain {

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.out.println("用法: TestMain <base.apk> <out.apk> <key.der> <cert.der>");
            return;
        }
        File base = new File(args[0]);
        File out = new File(args[1]);
        byte[] keyDer = readAll(new java.io.FileInputStream(args[2]));
        byte[] certDer = readAll(new java.io.FileInputStream(args[3]));

        PrivateKey key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(keyDer));
        X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new java.io.ByteArrayInputStream(certDer));

        java.util.zip.ZipFile zf = new java.util.zip.ZipFile(base);
        byte[] manifest;
        InputStream in = zf.getInputStream(zf.getEntry("AndroidManifest.xml"));
        manifest = readAll(in);
        in.close();
        zf.close();
        System.out.println("manifest " + manifest.length + "B, sharedUserId 现有 = " + Axml.getSharedUserId(manifest));

        byte[] patched = Axml.setSharedUserId(manifest, "xx.yy.zz");
        System.out.println("patched  " + patched.length + "B, sharedUserId = " + Axml.getSharedUserId(patched));

        Map<String, byte[]> replace = new LinkedHashMap<String, byte[]>();
        replace.put("AndroidManifest.xml", patched);

        File unsigned = new File(out.getAbsolutePath() + ".unsigned");
        long t0 = System.currentTimeMillis();
        ApkRepack.repack(base, unsigned, replace, new ApkRepack.Progress() {
            @Override
            public void onProgress(int percent, String message) {
                if (percent % 20 == 0) {
                    System.out.println("  " + percent + "% " + message);
                }
            }
        });
        System.out.println("repack " + (System.currentTimeMillis() - t0) + "ms -> " + unsigned.length() + "B");

        t0 = System.currentTimeMillis();
        Signer.sign(unsigned, out, key, cert, 21);
        System.out.println("sign   " + (System.currentTimeMillis() - t0) + "ms -> " + out.length() + "B");
        new FileOutputStream(out.getAbsolutePath() + ".ok").close();
    }

    static byte[] readAll(InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[1 << 16];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        in.close();
        return bos.toByteArray();
    }
}