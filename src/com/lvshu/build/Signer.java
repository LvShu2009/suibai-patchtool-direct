package com.lvshu.build;

import com.android.apksig.ApkSigner;

import java.io.File;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 用内置密钥对重建后的 APK 做 V1+V2+V3 签名。 */
public final class Signer {

    private Signer() {
    }

    public static void sign(File in, File out, PrivateKey key, X509Certificate cert, int minSdk) throws Exception {
        List<X509Certificate> certs = new ArrayList<X509Certificate>();
        certs.add(cert);
        ApkSigner.SignerConfig config = new ApkSigner.SignerConfig.Builder("LVSHU", key, certs).build();
        new ApkSigner.Builder(Collections.singletonList(config))
                .setInputApk(in)
                .setOutputApk(out)
                .setMinSdkVersion(minSdk)
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                .setCreatedBy("lincense")
                .build()
                .sign();
    }
}