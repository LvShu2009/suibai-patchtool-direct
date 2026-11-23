package com.lvshu.build;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Process;

import java.util.Locale;

public final class SharedUidValidator {

    private SharedUidValidator() {
    }

    public static final class Result {
        public final boolean ok;
        public final int selfUid;
        public final int gameUid;
        public final boolean declMatch;
        public final boolean sigMatch;
        public final String reason;

        public Result(boolean ok, int selfUid, int gameUid, boolean declMatch,
                      boolean sigMatch, String reason) {
            this.ok = ok;
            this.selfUid = selfUid;
            this.gameUid = gameUid;
            this.declMatch = declMatch;
            this.sigMatch = sigMatch;
            this.reason = reason;
        }
    }

    @SuppressWarnings("deprecation")
    public static Result check(Context ctx, String gamePkg, String expectedUid,
                               String expectedCertSha256) {
        int selfUid = Process.myUid();
        int gameUid = -1;
        if (ctx == null || gamePkg == null || gamePkg.isEmpty()) {
            return new Result(false, selfUid, gameUid, false, false, "缺少应用上下文或游戏包名");
        }

        String selfPkg = ctx.getPackageName();
        PackageManager pm = ctx.getPackageManager();
        PackageInfo selfInfo;
        PackageInfo gameInfo;
        try {
            gameUid = pm.getApplicationInfo(gamePkg, 0).uid;
            selfInfo = pm.getPackageInfo(selfPkg, 0);
            gameInfo = pm.getPackageInfo(gamePkg, 0);
        } catch (PackageManager.NameNotFoundException e) {
            return new Result(false, selfUid, gameUid, false, false, "应用未安装或对当前应用不可见");
        } catch (RuntimeException e) {
            return new Result(false, selfUid, gameUid, false, false, "无法读取已安装应用信息");
        }

        boolean declMatch = expectedUid != null && !expectedUid.isEmpty()
                && expectedUid.equals(selfInfo.sharedUserId)
                && expectedUid.equals(gameInfo.sharedUserId);

        String expectedCert = expectedCertSha256 == null ? ""
                : expectedCertSha256.trim().replace(":", "").toUpperCase(Locale.ROOT);
        String selfCert = SigningIdentity.installedSignatureSha256(ctx, selfPkg);
        String gameCert = SigningIdentity.installedSignatureSha256(ctx, gamePkg);
        boolean sigMatch = expectedCert.length() == 64
                && expectedCert.equals(selfCert) && expectedCert.equals(gameCert);

        boolean uidMatch = selfUid >= 0 && gameUid == selfUid;
        boolean ok = declMatch && sigMatch && uidMatch;
        String reason;
        if (!declMatch) {
            reason = "补丁应用或游戏的 sharedUserId 声明与预期不一致";
        } else if (!sigMatch) {
            reason = selfCert.isEmpty() || gameCert.isEmpty()
                    ? "无法取得补丁应用和游戏各自唯一的当前签名证书"
                    : "补丁应用或游戏的当前签名证书指纹与预期不一致";
        } else if (!uidMatch) {
            reason = "系统分配的 UID 不一致";
        } else {
            reason = "共享 UID 校验通过";
        }
        return new Result(ok, selfUid, gameUid, declMatch, sigMatch, reason);
    }
}
