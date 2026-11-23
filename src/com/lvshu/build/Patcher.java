package com.lvshu.build;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** 补丁包的解压 / 打包 / 部署脚本生成（不依赖 root，全部本地完成）。 */
final class Patcher {

    private Patcher() {
    }

    /** 目录里所有 .zip 补丁，按文件名排序。 */
    static List<File> listPatches(File dir) {
        List<File> out = new ArrayList<File>();
        File[] files = dir.listFiles();
        if (files == null) return out;
        for (File f : files) {
            if (f.isFile() && f.getName().toLowerCase(Locale.ROOT).endsWith(".zip")) {
                out.add(f);
            }
        }
        java.util.Collections.sort(out, new java.util.Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });
        return out;
    }

    /**
     * 把补丁 zip 解压到 outDir，内部路径剥掉开头的 files/，
     * 这样 outDir 就等于游戏 files 目录的镜像。
     *
     * @return 解出的文件数
     */
    static int extract(File zip, File outDir, Progress progress) throws IOException {
        if (!outDir.exists() && !outDir.mkdirs()) {
            throw new IOException("无法创建临时目录: " + outDir);
        }
        outDir.setReadable(true, false);
        outDir.setExecutable(true, false);
        String base = outDir.getCanonicalPath();
        int count = 0;
        ZipFile zf = new ZipFile(zip);
        try {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory()) continue;
                String name = e.getName().replace('\\', '/');
                String rel = stripPrefix(name);
                if (rel.length() == 0) continue;
                File out = new File(outDir, rel);
                String canon = out.getCanonicalPath();
                if (!canon.startsWith(base + File.separator)) continue; // 防目录穿越
                File parent = out.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    throw new IOException("无法创建目录: " + parent);
                }
                InputStream in = zf.getInputStream(e);
                try {
                    copy(in, out);
                } finally {
                    closeQuietly(in);
                }
                out.setReadable(true, false);
                count++;
                if (progress != null && (count % 20 == 0)) {
                    progress.onProgress("解压中… " + count + " 个文件");
                }
            }
        } finally {
            try {
                zf.close();
            } catch (IOException ignored) {
            }
        }
        return count;
    }

    private static String stripPrefix(String name) {
        if (name.startsWith("files/")) return name.substring(6);
        int i = name.indexOf("/files/");
        if (i >= 0) return name.substring(i + 7);
        return name;
    }

    /**
     * /storage/emulated/0/... → /data/media/0/...（root 直连真实数据区，绕开 FUSE）。
     * 非 root 身份没有权限走这条，返回原路径。
     */
    static String toDirectPath(String path) {
        String[] prefixes = new String[]{
                "/storage/emulated/0/", "/storage/self/primary/", "/sdcard/"
        };
        for (String p : prefixes) {
            if (path.startsWith(p)) return "/data/media/0/" + path.substring(p.length());
        }
        if (path.equals("/storage/emulated/0") || path.equals("/sdcard")) return "/data/media/0";
        return path;
    }

    /**
     * 生成 shell 侧部署脚本。
     *
     * @param directDataPaths shell 是 root 时传 true：全部走 /data/media/0，
     *                        避免 FUSE 掉线导致 "Transport endpoint is not connected"。
     */
    /** 去掉 /storage/emulated/0、/data/media/0 这类卷前缀，得到相对路径（用于挂载点替换）。 */
    private static String stripVolumePrefix(String abs) {
        String[] pres = {"/storage/emulated/0/", "/data/media/0/",
                "/mnt/pass_through/0/emulated/0/", "/mnt/user/0/emulated/0/",
                "/storage/self/primary/", "/sdcard/"};
        for (String pre : pres) {
            if (abs.startsWith(pre)) return abs.substring(pre.length());
        }
        return null;
    }

    static String buildInstallScript(String zipName, File stageDir, File gameFiles, File backupBase,
                                     File logFile, boolean directDataPaths) {
        String stagePublic = stageDir.getAbsolutePath();
        String dstPublic = gameFiles.getAbsolutePath();
        String bkPublic = backupBase == null ? "" : backupBase.getAbsolutePath();
        String logPublic = logFile.getAbsolutePath();

        String stage = esc(directDataPaths ? toDirectPath(stagePublic) : stagePublic);
        String dst = esc(directDataPaths ? toDirectPath(dstPublic) : dstPublic);
        String bk = directDataPaths ? esc(toDirectPath(bkPublic)) : esc(bkPublic);
        String log = esc(directDataPaths ? toDirectPath(logPublic) : logPublic);
        String pub = directDataPaths ? esc(dstPublic) : "";
        String zip = shQuote(zipName);

        // 挂载点候选：无线调试激活的 Shizuku 是 uid=2000，走 FUSE 时写 Android/data
        // 可能中途 ENOTCONN；pass_through / mnt 视图往往还能用，所以逐个探测。
        String dstRel = stripVolumePrefix(dstPublic);
        String bkRel = bkPublic.length() == 0 ? "" : stripVolumePrefix(bkPublic);
        String roots = directDataPaths
                ? "/data/media/0 /storage/emulated/0"
                : "/mnt/pass_through/0/emulated/0 /mnt/user/0/emulated/0 /storage/emulated/0";

        StringBuilder sb = new StringBuilder();
        sb.append("#!/system/bin/sh\n");
        sb.append("# 由 酸败补丁助手 生成（Shizuku shell 执行）\n");
        sb.append("LOG='").append(log).append("'\n");
        sb.append("exec >>\"$LOG\" 2>&1\n");
        sb.append("echo \"[$(date '+%Y-%m-%d %H:%M:%S')] === 部署补丁 ").append(zip).append(" ===\"\n");
        sb.append("mkd() { mkdir -p \"$1\" && return 0; sleep 0.4; mkdir -p \"$1\" && return 0; sleep 1; mkdir -p \"$1\"; }\n");
        sb.append("put() { cp -f \"$1\" \"$2\" && return 0; sleep 0.4; cp -f \"$1\" \"$2\" && return 0; sleep 1; cp -f \"$1\" \"$2\"; }\n");
        if (dstRel == null) {
            // 未知布局：退回固定路径
            sb.append("DST='").append(dst).append("'\n");
            sb.append("BK='").append(bk).append("'\n");
            sb.append("ROOTS=''\n");
            sb.append("switch_mount() { return 1; }\n");
        } else {
            sb.append("ROOTS='").append(esc(roots)).append("'\n");
            sb.append("GRELP='").append(esc(dstRel)).append("'\n");
            sb.append("BKRELP='").append(esc(bkRel)).append("'\n");
            sb.append("setpaths() { DST=\"$ROOT/$GRELP\"; if [ -n \"$BKRELP\" ]; then BK=\"$ROOT/$BKRELP\"; else BK=''; fi; BKM=\"$BK/.manifest.tsv\"; }\n");
            sb.append("pick() { for c in $ROOTS; do [ -d \"$c/$GRELP\" ] || continue; if touch \"$c/$GRELP/.w_test\" 2>/dev/null; then rm -f \"$c/$GRELP/.w_test\" 2>/dev/null; ROOT=\"$c\"; return 0; fi; done; return 1; }\n");
            sb.append("switch_mount() { OLD=\"$ROOT\"; for c in $ROOTS; do [ \"$c\" = \"$OLD\" ] && continue; [ -d \"$c/$GRELP\" ] || continue; if touch \"$c/$GRELP/.w_test\" 2>/dev/null; then rm -f \"$c/$GRELP/.w_test\" 2>/dev/null; ROOT=\"$c\"; setpaths; echo \"[$(date '+%H:%M:%S')] ↻ 挂载点已切换: $c\"; return 0; fi; done; return 1; }\n");
            sb.append("ROOT=''; pick || { echo \"[$(date '+%H:%M:%S')] ✗ 没有可写的挂载点，试过: $ROOTS\"; echo \"  处理：重启手机 / 重启 Shizuku 后重试；用 root 激活 Shizuku 可走 /data/media/0 彻底绕开\"; exit 6; }\n");
            sb.append("setpaths\n");
        }
        sb.append("PUB='").append(pub).append("'\n");
        sb.append("echo \"[$(date '+%H:%M:%S')] 写入目标: $DST (uid=$(id -u))\"\n");
        sb.append("if ! touch \"$DST/.w_preflight\" 2>/dev/null; then echo \"[$(date '+%H:%M:%S')] ✗ 游戏目录当前不可写（挂载点失效），已中止，一个文件都没动: $DST\"; echo \"  处理：重启手机 / 重开 Shizuku 后重试；root 激活可走 /data/media/0\"; exit 7; fi; rm -f \"$DST/.w_preflight\" 2>/dev/null\n");
        sb.append("if [ -n \"$BK\" ]; then if touch \"$BK/.w_test\" 2>/dev/null; then rm -f \"$BK/.w_test\" 2>/dev/null; else echo \"[$(date '+%H:%M:%S')] ⚠ 备份目录不可写（大概是 root 创建的），本次跳过备份: $BK\"; BK=''; BKM=''; fi; fi\n");
        // 目标目录的属主，拷完把文件 chown 回去，免得游戏读不到
        sb.append("OWNER=$(stat -c '%u:%g' \"$DST\" 2>/dev/null)\n");
        // 带重试的拷/建目录：FUSE 偶尔会 ENOTCONN，重试几次通常就过去了
        sb.append("BKM=\"${BK:+$BK/.manifest.tsv}\"\n");
        sb.append("cks() { cksum \"$1\" 2>/dev/null | awk '{print $1\" \"$2}'; }\n");
        sb.append("look() { awk -F'\t' -v k=\"$1\" '$1==k{print $2\" \"$3; exit}' \"$BKM\" 2>/dev/null; }\n");
        sb.append("putmani() { awk -F'\t' -v k=\"$1\" '$1!=k' \"$BKM\" 2>/dev/null > \"$BKM.t\"; printf '%s\t%s\t%s\\n' \"$1\" \"$2\" \"$3\" >> \"$BKM.t\"; mv \"$BKM.t\" \"$BKM\"; }\n");
        sb.append("cd '").append(stage).append("' || { echo \"[$(date '+%Y-%m-%d %H:%M:%S')] ✗ 无法进入解压目录\"; exit 2; }\n");
        sb.append("find . -type f > ./.plist 2>/dev/null\n");
        sb.append("n=0; ok=0; bad=0; wn=0\n");
        sb.append("while IFS= read -r rel; do\n");
        sb.append("  case \"$rel\" in ./.plist) continue;; esac\n");
        sb.append("  rel=${rel#./}\n");
        sb.append("  [ -z \"$rel\" ] && continue\n");
        sb.append("  n=$((n+1))\n");
        sb.append("  d=\"$DST/$rel\"\n");
        sb.append("  dd=\"${d%/*}\"\n");
        sb.append("  case \"$rel\" in *.txt|*.TXT|*.md|*.MD) skipbak=1;; *) skipbak=0;; esac\n");
        sb.append("  if [ \"$skipbak\" = 0 ] && [ -f \"$d\" ] && [ \"$(cks \"$d\")\" = \"$(cks \"$rel\")\" ]; then\n");
        sb.append("    echo \"[$(date '+%H:%M:%S')] - $rel 与补丁内容相同，不备份（不是原版）\"\n");
        sb.append("    skipbak=2\n");
        sb.append("  fi\n");
        sb.append("  if [ -n \"$BK\" ] && [ -f \"$d\" ] && [ \"$skipbak\" = 0 ]; then\n");
        sb.append("    bf=\"$BK/$rel\"\n");
        sb.append("    if [ -f \"$bf\" ]; then\n");
        sb.append("      have=$(look \"$rel\"); cur=$(cks \"$bf\")\n");
        sb.append("      if [ -n \"$have\" ] && [ \"$have\" != \"$cur\" ]; then\n");
        sb.append("        echo \"[$(date '+%H:%M:%S')] ⚠ 备份校验不符（疑似同名文件顶替），不改写索引，回滚时会拒绝这个文件: $rel\"\n");
        sb.append("        wn=$((wn+1))\n");
        sb.append("      elif [ -z \"$have\" ]; then\n");
        sb.append("        putmani \"$rel\" $cur\n");
        sb.append("        echo \"[$(date '+%H:%M:%S')] 备份已存在，补记校验索引: $rel\"\n");
        sb.append("      else\n");
        sb.append("        if [ \"$cur\" = \"$(cks \"$rel\")\" ]; then\n");
        sb.append("          echo \"[$(date '+%H:%M:%S')] \u26a0 备份内容和补丁一模一样，这份备份是假的（当初备份到的就是补丁），回滚它等于没回滚: $rel\"\n");
        sb.append("        else\n");
        sb.append("          echo \"[$(date '+%H:%M:%S')] 备份已有（校验一致，跳过）: $rel\"\n");
        sb.append("        fi\n");
        sb.append("      fi\n");
        sb.append("    else\n");
        sb.append("      case \"$rel\" in */*) bd=\"$BK/${rel%/*}\";; *) bd=\"$BK\";; esac\n");
        sb.append("      mkd \"$bd\" 2>/dev/null\n");
        sb.append("      if put \"$d\" \"$bf\"; then\n");
        sb.append("        putmani \"$rel\" $(cks \"$bf\")\n");
        sb.append("        echo \"[$(date '+%H:%M:%S')] 已备份原版 $rel\"\n");
        sb.append("      fi\n");
        sb.append("    fi\n");
        sb.append("  fi\n");
        sb.append("  if mkd \"$dd\" && put \"$rel\" \"$d\"; then\n");
        sb.append("    [ -n \"$OWNER\" ] && chown \"$OWNER\" \"$d\" 2>/dev/null\n");
        sb.append("    chmod 0664 \"$d\" 2>/dev/null\n");
        sb.append("    echo \"[$(date '+%H:%M:%S')] ✓ $rel\"\n");
        sb.append("    ok=$((ok+1))\n");
        sb.append("  elif switch_mount && { d=\"$DST/$rel\"; dd=\"${d%/*}\"; mkd \"$dd\" && put \"$rel\" \"$d\"; }; then\n");
        sb.append("    [ -n \"$OWNER\" ] && chown \"$OWNER\" \"$d\" 2>/dev/null\n");
        sb.append("    chmod 0664 \"$d\" 2>/dev/null\n");
        sb.append("    echo \"[$(date '+%H:%M:%S')] ✓ $rel（切换挂载点后成功）\"\n");
        sb.append("    ok=$((ok+1))\n");
        sb.append("  else\n");
        sb.append("    echo \"[$(date '+%H:%M:%S')] ✗ $rel（写入失败）\"\n");
        sb.append("    bad=$((bad+1))\n");
        sb.append("  fi\n");
        sb.append("done < ./.plist\n");
        sb.append("if [ \"$wn\" -gt 0 ]; then echo \"[$(date '+%H:%M:%S')] ⚠ 有 $wn 个文件的备份校验不符，已保留原索引，回滚会跳过它们\"; fi\n");
        sb.append("if [ \"$n\" -eq 0 ]; then echo \"[$(date '+%H:%M:%S')] ⚠ 这个补丁一个文件都没部署：zip 里可能没有 files/ 目录结构\"; fi\n");
        sb.append("echo \"[$(date '+%Y-%m-%d %H:%M:%S')] --- 合计 $n 个文件：成功 $ok，失败 $bad\"\n");
        sb.append("if [ \"$bad\" -gt 0 ]; then echo \"[$(date '+%H:%M:%S')] ⚠ 有 $bad 个文件没写进去（常见原因：挂载点中途失效）\"; exit 8; fi\n");
        sb.append("exit 0\n");
        return sb.toString();
    }

    /**
     * 一键清理备份：删除「备份文件」下的旧时间戳目录；keepOriginal=false 时连「原版」一起删。
     * 只删时间戳格式（yyyyMMdd_HHmmss）的目录，别的东西一律不碰。
     */
    static String buildCleanScript(File backupRoot, File logFile, boolean directDataPaths, boolean keepOriginal) {
        String bk = esc(directDataPaths ? toDirectPath(backupRoot.getAbsolutePath()) : backupRoot.getAbsolutePath());
        String log = esc(directDataPaths ? toDirectPath(logFile.getAbsolutePath()) : logFile.getAbsolutePath());

        StringBuilder sb = new StringBuilder();
        sb.append("#!/system/bin/sh\n");
        sb.append("# 酸败补丁助手 —— 一键清理备份\n");
        sb.append("LOG='").append(log).append("'\n");
        sb.append("BK='").append(bk).append("'\n");
        sb.append("exec >>\"$LOG\" 2>&1\n");
        sb.append("echo \"[$(date '+%Y-%m-%d %H:%M:%S')] === 清理备份（").append(keepOriginal ? "保留原版" : "全部删除").append("） ===\"\n");
        // 安全护栏：路径必须正好是 …/备份文件
        sb.append("case \"$BK\" in */备份文件) ;; *) echo \"[$(date '+%H:%M:%S')] ✗ 备份路径异常，已中止: $BK\"; exit 5;; esac\n");
        sb.append("if [ ! -d \"$BK\" ]; then echo \"[$(date '+%H:%M:%S')] 备份目录不存在，无需清理\"; exit 0; fi\n");
        sb.append("before=$(du -sk \"$BK\" 2>/dev/null | awk '{print $1}')\n");
        sb.append("n=0\n");
        sb.append("for d in \"$BK\"/*; do\n");
        sb.append("  [ -d \"$d\" ] || continue\n");
        sb.append("  b=$(basename \"$d\")\n");
        sb.append("  case \"$b\" in\n");
        sb.append("    [0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]_[0-9][0-9][0-9][0-9][0-9][0-9])\n");
        sb.append("      sz=$(du -sk \"$d\" 2>/dev/null | awk '{print $1}')\n");
        sb.append("      if rm -rf \"$d\"; then echo \"[$(date '+%H:%M:%S')] ✓ 已删除 $b（${sz} KB）\"; n=$((n+1)); fi\n");
        sb.append("      ;;\n");
        sb.append("    *)\n");
        if (keepOriginal) {
            sb.append("      echo \"[$(date '+%H:%M:%S')] - 保留 $b\"\n");
        } else {
            // 全部删除时也只认「原版」目录，别的东西不碰
            sb.append("      if [ \"$b\" = \"原版\" ]; then\n");
            sb.append("        sz=$(du -sk \"$d\" 2>/dev/null | awk '{print $1}')\n");
            sb.append("        if rm -rf \"$d\"; then echo \"[$(date '+%H:%M:%S')] ✓ 已删除 $b（${sz} KB）\"; n=$((n+1)); fi\n");
            sb.append("      else\n");
            sb.append("        echo \"[$(date '+%H:%M:%S')] - 保留 $b（不是备份目录）\"\n");
            sb.append("      fi\n");
        }
        sb.append("      ;;\n");
        sb.append("  esac\n");
        sb.append("done\n");
        sb.append("after=$(du -sk \"$BK\" 2>/dev/null | awk '{print $1}')\n");
        sb.append("freed=$((before-after))\n");
        sb.append("[ \"$freed\" -lt 0 ] && freed=0\n");
        sb.append("echo \"[$(date '+%Y-%m-%d %H:%M:%S')] --- 清理 $n 项，释放约 ${freed} KB，剩余 $(du -sk \"$BK\" 2>/dev/null | awk '{print $1}') KB\"\n");
        sb.append("exit 0\n");
        return sb.toString();
    }

    /**
     * 一键回滚原版：把「备份文件/原版」里记录的原始文件按原路径拷回游戏目录。
     * 有校验索引就逐个核对 cksum，对不上就跳过并报出来（防止同名文件乱入）。
     */
    static String buildRestoreScript(File gameFiles, File backupDir, File logFile, boolean directDataPaths) {
        String dstPublic = gameFiles.getAbsolutePath();
        String dst = esc(directDataPaths ? toDirectPath(dstPublic) : dstPublic);
        String pub = directDataPaths ? esc(dstPublic) : "";
        String bk = esc(directDataPaths ? toDirectPath(backupDir.getAbsolutePath()) : backupDir.getAbsolutePath());
        String log = esc(directDataPaths ? toDirectPath(logFile.getAbsolutePath()) : logFile.getAbsolutePath());

        StringBuilder sb = new StringBuilder();
        sb.append("#!/system/bin/sh\n");
        sb.append("# 酸败补丁助手 —— 一键回滚原版\n");
        sb.append("LOG='").append(log).append("'\n");
        sb.append("DST='").append(dst).append("'\n");
        sb.append("PUB='").append(pub).append("'\n");
        sb.append("BK='").append(bk).append("'\n");
        sb.append("exec >>\"$LOG\" 2>&1\n");
        sb.append("echo \"[$(date '+%Y-%m-%d %H:%M:%S')] === 回滚原版 ===\"\n");
        sb.append("echo \"[$(date '+%H:%M:%S')] 还原到: $DST (uid=$(id -u))\"\n");
        sb.append("echo \"[$(date '+%H:%M:%S')] 备份来源: $BK\"\n");
        sb.append("if [ ! -d \"$DST\" ] && [ -n \"$PUB\" ] && [ -d \"$PUB\" ]; then DST=\"$PUB\"; fi\n");
        sb.append("OWNER=$(stat -c '%u:%g' \"$DST\" 2>/dev/null)\n");
        sb.append("mkd() { mkdir -p \"$1\" && return 0; sleep 0.4; mkdir -p \"$1\" && return 0; sleep 1; mkdir -p \"$1\"; }\n");
        sb.append("put() { cp -f \"$1\" \"$2\" && return 0; sleep 0.4; cp -f \"$1\" \"$2\" && return 0; sleep 1; cp -f \"$1\" \"$2\"; }\n");
        sb.append("cks() { cksum \"$1\" 2>/dev/null | awk '{print $1\" \"$2}'; }\n");
        sb.append("if [ ! -d \"$DST\" ]; then echo \"[$(date '+%H:%M:%S')] ✗ 游戏目录不存在: $DST\"; exit 3; fi\n");
        sb.append("if [ ! -d \"$BK\" ]; then echo \"[$(date '+%H:%M:%S')] ✗ 备份目录不存在: $BK\"; exit 4; fi\n");
        sb.append("if ! touch \"$DST/.w_preflight\" 2>/dev/null; then echo \"[$(date '+%H:%M:%S')] ✗ 游戏目录当前不可写（挂载点失效），已中止，什么都没改: $DST\"; echo \"  处理：重启手机 / 重开 Shizuku 后重试\"; exit 7; fi; rm -f \"$DST/.w_preflight\" 2>/dev/null\n");
        sb.append("BKM=\"$BK/.manifest.tsv\"\n");
        sb.append("n=0; ok=0; bad=0; sk=0\n");
        sb.append("do_one() {\n");
        sb.append("  rel=\"$1\"\n");
        sb.append("  f=\"$BK/$rel\"; d=\"$DST/$rel\"\n");
        sb.append("  if [ ! -f \"$f\" ]; then echo \"[$(date '+%H:%M:%S')] ✗ 备份缺失: $rel\"; bad=$((bad+1)); return; fi\n");
        sb.append("  case \"$rel\" in *.txt|*.TXT|*.md|*.MD) echo \"[$(date '+%H:%M:%S')] ℹ 说明文件不还原: $rel\"; sk=$((sk+1)); return;; esac\n");
        sb.append("  n=$((n+1))\n");
        sb.append("  cur=$(cks \"$f\")\n");
        sb.append("  if [ -n \"$2\" ] && [ \"$cur\" != \"$2\" ]; then\n");
        sb.append("    echo \"[$(date '+%H:%M:%S')] ⚠ 校验不符，已跳过（防止覆盖成错的文件）: $rel\"\n");
        sb.append("    bad=$((bad+1)); return\n");
        sb.append("  fi\n");
        sb.append("  # 不再用\"内容一致就跳过\"：设备上出现过读缓存导致的假一致，宁可白拷一次\n");
        sb.append("  mkd \"${d%/*}\" 2>/dev/null\n");
        sb.append("  if put \"$f\" \"$d\"; then\n");
        sb.append("    [ -n \"$OWNER\" ] && chown \"$OWNER\" \"$d\" 2>/dev/null\n");
        sb.append("    chmod 0664 \"$d\" 2>/dev/null\n");
        sb.append("    echo \"[$(date '+%H:%M:%S')] ✓ 已还原 $rel\"\n");
        sb.append("    ok=$((ok+1))\n");
        sb.append("  else\n");
        sb.append("    echo \"[$(date '+%H:%M:%S')] ✗ 还原失败: $rel\"\n");
        sb.append("    bad=$((bad+1))\n");
        sb.append("  fi\n");
        sb.append("}\n");
        sb.append("if [ -f \"$BKM\" ]; then\n");
        sb.append("  T=$(printf '\\t')\n");
        sb.append("  while IFS=\"$T\" read -r rel mc ms; do\n");
        sb.append("    case \"$rel\" in ''|'#'*) continue;; esac\n");
        sb.append("    [ -z \"$mc\" ] && do_one \"$rel\" \"\" || do_one \"$rel\" \"$mc $ms\"\n");
        sb.append("  done < \"$BKM\"\n");
        sb.append("else\n");
        sb.append("  echo \"[$(date '+%H:%M:%S')] 没有校验索引，按备份目录里的文件整体还原\"\n");
        sb.append("  cd \"$BK\" || exit 2\n");
        sb.append("  find . -type f ! -name '.manifest.tsv' > ./.rlist 2>/dev/null\n");
        sb.append("  while IFS= read -r rel; do\n");
        sb.append("    rel=${rel#./}\n");
        sb.append("    [ -z \"$rel\" ] && continue\n");
        sb.append("    do_one \"$rel\" \"\"\n");
        sb.append("  done < ./.rlist\n");
        sb.append("  rm -f ./.rlist\n");
        sb.append("fi\n");
        sb.append("echo \"[$(date '+%Y-%m-%d %H:%M:%S')] --- 回滚结束：还原 $ok 个，跳过 $sk 个，失败 $bad 个\"\n");
        sb.append("if [ \"$bad\" -gt 0 ]; then echo \"[$(date '+%H:%M:%S')] ⚠ 有 $bad 个文件没还原成功\"; exit 8; fi\n");
        sb.append("exit 0\n");
        return sb.toString();
    }

    /**
     * 官方反和谐：只需要在游戏 files 下存在一个空的 localmode.txt。
     * 这个脚本只做这一件事（幂等：已存在就只报告）。
     */
    static String buildAntiScript(File gameFiles, File logFile, boolean directDataPaths) {
        String dstPublic = gameFiles.getAbsolutePath();
        String dst = esc(directDataPaths ? toDirectPath(dstPublic) : dstPublic);
        String pub = directDataPaths ? esc(dstPublic) : "";
        String log = esc(directDataPaths ? toDirectPath(logFile.getAbsolutePath()) : logFile.getAbsolutePath());

        StringBuilder sb = new StringBuilder();
        sb.append("#!/system/bin/sh\n");
        sb.append("# 酸败补丁助手 —— 反和谐安装（只建 localmode.txt）\n");
        sb.append("LOG='").append(log).append("'\n");
        sb.append("DST='").append(dst).append("'\n");
        sb.append("PUB='").append(pub).append("'\n");
        sb.append("exec >>\"$LOG\" 2>&1\n");
        sb.append("echo \"[$(date '+%Y-%m-%d %H:%M:%S')] === 反和谐安装 ===\"\n");
        sb.append("if [ ! -d \"$DST\" ] && [ -n \"$PUB\" ] && [ -d \"$PUB\" ]; then DST=\"$PUB\"; fi\n");
        sb.append("echo \"[$(date '+%H:%M:%S')] 游戏目录: $DST (uid=$(id -u))\"\n");
        sb.append("if [ ! -d \"$DST\" ]; then\n");
        sb.append("  echo \"[$(date '+%H:%M:%S')] ✗ 游戏目录不存在\"\n");
        sb.append("  exit 3\n");
        sb.append("fi\n");
        sb.append("F=\"$DST/localmode.txt\"\n");
        sb.append("if [ -f \"$F\" ]; then\n");
        sb.append("  echo \"[$(date '+%H:%M:%S')] 反和谐开关本来就在：$F（$(wc -c < \"$F\" 2>/dev/null) 字节）\"\n");
        sb.append("else\n");
        sb.append("  if : > \"$F\"; then\n");
        sb.append("    echo \"[$(date '+%H:%M:%S')] ✓ 已创建空文件：$F\"\n");
        sb.append("  else\n");
        sb.append("    echo \"[$(date '+%H:%M:%S')] ✗ 创建失败：$F\"\n");
        sb.append("    exit 4\n");
        sb.append("  fi\n");
        sb.append("fi\n");
        sb.append("OWNER=$(stat -c '%u:%g' \"$DST\" 2>/dev/null)\n");
        sb.append("if [ -n \"$OWNER\" ]; then chown \"$OWNER\" \"$F\" 2>/dev/null; fi\n");
        sb.append("chmod 0664 \"$F\" 2>/dev/null\n");
        sb.append("echo \"[$(date '+%H:%M:%S')] 结果: $(ls -l \"$F\" 2>/dev/null)\"\n");
        sb.append("exit 0\n");
        return sb.toString();
    }

    /** 把 srcDir 里的东西打进 zip，内部路径固定为 files/&lt;relPath&gt;/… */
    static int pack(File srcDir, String relPath, File outZip) throws IOException {
        String rel = sanitizeRel(relPath);
        if (rel.length() == 0) throw new IOException("路径无效，请填写例如 data/android/assetbundle/spines");
        OutputStream fos = new FileOutputStream(outZip);
        ZipOutputStream zos = new ZipOutputStream(fos);
        zos.setMethod(ZipOutputStream.DEFLATED);
        int count = 0;
        try {
            count = addDir(zos, srcDir, "files/" + rel + "/", 0);
            zos.finish();
        } finally {
            try {
                zos.close();
            } catch (IOException ignored) {
            }
        }
        return count;
    }

    private static int addDir(ZipOutputStream zos, File dir, String prefix, int count) throws IOException {
        File[] files = dir.listFiles();
        if (files == null) return count;
        for (File f : files) {
            if (f.isDirectory()) {
                count = addDir(zos, f, prefix + f.getName() + "/", count);
            } else {
                ZipEntry e = new ZipEntry(prefix + f.getName());
                e.setTime(f.lastModified());
                zos.putNextEntry(e);
                InputStream in = new FileInputStream(f);
                try {
                    copy(in, zos);
                } finally {
                    closeQuietly(in);
                }
                zos.closeEntry();
                count++;
            }
        }
        return count;
    }

    /** 路径消毒，与打包脚本里的 sed 规则一致。 */
    static String sanitizeRel(String raw) {
        if (raw == null) return "";
        String p = raw.trim().replace('\\', '/');
        p = p.replaceAll("^/storage/emulated/0/Android/data/[^/]+/files/", "");
        p = p.replaceAll("^/data/data/[^/]+/files/", "");
        p = p.replaceAll("^.*/files/", "");
        p = p.replaceAll("^/+", "");
        p = p.replaceAll("/+", "/");
        p = p.replaceAll("/+$", "");
        return p;
    }

    private static void copy(InputStream in, File out) throws IOException {
        OutputStream os = new FileOutputStream(out);
        try {
            copy(in, os);
        } finally {
            closeQuietly(os);
        }
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (IOException ignored) {
        }
    }

    private static String esc(String path) {
        return path.replace("'", "'\\''");
    }

    static String shQuote(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    interface Progress {
        void onProgress(String message);
    }
}