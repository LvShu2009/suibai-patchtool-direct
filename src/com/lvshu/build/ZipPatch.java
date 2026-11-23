package com.lvshu.build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 补丁压缩包安装管线：
 *  1) 解压到私有临时目录（UTF-8 / GBK 自适应）
 *  2) 若含 files/ 目录 → 按相对路径直接铺到游戏 files 目录
 *  3) 否则收集 .assetbundle → 五级定位 → 备份覆盖
 */
public final class ZipPatch {

    public interface Done {
        void onDone(String error, String summary);
    }

    private ZipPatch() {
    }

    public static void install(final Engine eng, final String pkg, final File zip, final String tag,
                               final Engine.Progress cb, final Done done) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                String err = null;
                String summary = "";
                try {
                    File ext = new File(eng.workDir(), "extract/" + Engine.stamp());
                    deleteTree(ext);
                    Engine.mkdirs(ext);
                    if (cb != null) {
                        cb.on(5, "解压中…");
                    }
                    unzip(zip, ext, cb);

                    // localmode.txt 不再当"短路信号"。酸败原版里反和谐是独立按钮，
                    // zip 安装时 localmode.txt 只是普通文件，跟其它补丁一起铺到游戏 files。
                    // 旧逻辑见到它就只建空文件并 return，导致"反和谐+内容"混合包里的
                    // assetbundle 全被跳过（只装了反和谐）。现在统一交给下面的 files/ 分支
                    // 按相对路径整体铺装，开关文件和 17 个 ab 都会正确安装。

                    File filesDir = findDir(ext, "files", 3);
                    if (filesDir != null) {
                        if (cb != null) {
                            cb.on(45, "安装补丁包…");
                        }
                        List<Engine.Item> items = new ArrayList<Engine.Item>();
                        String base = filesDir.getAbsolutePath();
                        List<String> rels = new ArrayList<String>();
                        Engine.walk(filesDir, "", rels);
                        for (String rel : rels) {
                            String name = rel.substring(rel.lastIndexOf('/') + 1);
                            items.add(new Engine.Item(new File(base, rel),
                                    Engine.gameFiles(pkg) + "/" + rel, name));
                        }
                        if (items.isEmpty()) {
                            err = "补丁包内没有文件";
                        } else {
                            err = eng.install(items, tag, null, cb);
                            summary = "已安装 " + items.size() + " 个文件";
                        }
                        deleteTree(ext);
                        done.onDone(err, summary);
                        return;
                    }

                    // 收集 assetbundle
                    List<String> all = new ArrayList<String>();
                    Engine.walk(ext, "", all);
                    List<String> bundles = new ArrayList<String>();
                    for (String rel : all) {
                        if (rel.toLowerCase(Locale.ROOT).endsWith(".assetbundle")) {
                            bundles.add(rel);
                        }
                    }
                    if (bundles.isEmpty()) {
                        // 纯反和谐开关包：包里只有 localmode.txt（在线「localmode」补丁即此类），
                        // 没有 files/ 目录也没有 assetbundle。此时只建开关空文件即可，不能报"没有 assetbundle"。
                        // 若包内确实带 localmode.txt，则建开关并成功返回。
                        boolean isLocalModeZip = findFileByName(ext, "localmode.txt") != null
                                || (zip.getName() != null
                                    && zip.getName().toLowerCase(Locale.ROOT).contains("localmode"));
                        if (isLocalModeZip) {
                            String[] localModeHints = new String[1];
                            boolean localModeInstalled = installLocalMode(eng, pkg, localModeHints);
                            deleteTree(ext);
                            if (localModeInstalled) {
                                done.onDone("", "反和谐开关已开启（localmode.txt）");
                            } else {
                                String reason = localModeHints[0];
                                if (reason == null || reason.trim().length() == 0) {
                                    reason = "无法写入 localmode.txt";
                                }
                                done.onDone("反和谐开关失败：" + reason, "");
                            }
                            return;
                        }
                        deleteTree(ext);
                        done.onDone("压缩包内没有 assetbundle 文件", "");
                        return;
                    }
                    if (cb != null) {
                        cb.on(40, "准备资源索引（优先复用酸败表，无表才建，请稍候）…");
                    }
                    Engine.Index idx = eng.buildIndex(pkg, cb);
                    // 索引 rel 相对游戏 files（与酸败表一致），目标绝对路径 = files + rel。
                    String root = Engine.gameFiles(pkg);
                    List<Engine.Item> items = new ArrayList<Engine.Item>();
                    int skip = 0;
                    int i = 0;
                    for (String rel : bundles) {
                        i++;
                        String name = rel.substring(rel.lastIndexOf('/') + 1);
                        // 传完整相对路径（统一 '/'），不再剥 common 前缀——
                        // 酸败的三级定位自带逐级剥皮，提前剥可能去掉真正能命中的目录。
                        String normRel = rel.replace('\\', '/');
                        String srcCab = null;
                        // CAB 只在同名多候选时需要读取；这里统一先读，代价仅是文件头 8K。
                        srcCab = Engine.cabOfSource(new File(ext, rel));
                        List<String> dstRels = Engine.locateTargets(normRel, idx, srcCab);
                        if (dstRels == null || dstRels.isEmpty()) {
                            skip++;
                            continue;
                        }
                        // 相对资源根路径 → 绝对路径；一源多目标生成多个 Item。
                        for (String dstRel : dstRels) {
                            String dstAbs = root + "/" + dstRel;
                            items.add(new Engine.Item(new File(ext, rel), dstAbs, name));
                        }
                        if (cb != null) {
                            cb.on(70 + Math.min(12, i * 12 / bundles.size()), "定位 " + name);
                        }
                    }
                    if (cb != null) {
                        cb.on(85, "安装补丁文件…");
                    }
                    if (items.isEmpty()) {
                        // 更精准的错误提示，区分常见原因，避免一律"没有定位到"。
                        if (idx == null || idx.total == 0) {
                            err = "游戏资源索引为空：未找到可定位的游戏资源目录，请先确认游戏已装好资源。";
                        } else {
                            err = "没有定位到可安装的文件：补丁与游戏资源不同名，或 CAB 身份不匹配（游戏资源索引 "
                                    + idx.total + " 个）。";
                        }
                    } else {
                        err = eng.install(items, tag, null, cb);
                        String cabSrc;
                        if (idx.cabIncremental > 0) {
                            cabSrc = "CAB 表增量 +" + idx.cabIncremental + " 条（复用 "
                                    + idx.cabFromShared + " 条）";
                        } else if (idx.cabFromShared > 0) {
                            cabSrc = "复用 CAB 表 " + idx.cabFromShared + " 条（无新增）";
                        } else {
                            cabSrc = "自建 CAB " + idx.cabSelfBuilt + " 条";
                        }
                        summary = "可装 " + items.size() + " · 跳过 " + skip + " · " + cabSrc;
                    }
                    deleteTree(ext);
                    done.onDone(err, summary);
                } catch (Throwable e) {
                    deleteTree(new File(eng.workDir(), "extract"));
                    done.onDone("安装异常：" + e, "");
                }
            }
        }, "zip-install").start();
    }

    public static void unzip(File zip, File out, Engine.Progress cb) throws IOException {
        Charset cs = Charset.forName("UTF-8");
        if (looksGbk(zip)) {
            cs = Charset.forName("GBK");
        }
        ZipFile zf;
        try {
            zf = new ZipFile(zip, cs);
        } catch (Exception e) {
            zf = new ZipFile(zip);
        }
        try {
            Enumeration<? extends ZipEntry> en = zf.entries();
            String root = out.getAbsolutePath() + "/";
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String name = e.getName();
                if (name.indexOf("..") >= 0) {
                    continue;
                }
                File f = new File(out, name);
                if (!f.getAbsolutePath().startsWith(root)) {
                    continue;
                }
                if (e.isDirectory()) {
                    Engine.mkdirs(f);
                    continue;
                }
                Engine.mkdirs(f.getParentFile());
                InputStream in = zf.getInputStream(e);
                try {
                    FileOutputStream fo = new FileOutputStream(f);
                    try {
                        byte[] buf = new byte[81920];
                        int n;
                        while ((n = in.read(buf)) > 0) {
                            fo.write(buf, 0, n);
                        }
                    } finally {
                        fo.close();
                    }
                } finally {
                    in.close();
                }
            }
        } finally {
            zf.close();
        }
    }

    /** 采样前若干条目，名字里出现替换符就按 GBK 再试。 */
    private static boolean looksGbk(File zip) {
        try {
            ZipFile zf = new ZipFile(zip, Charset.forName("UTF-8"));
            try {
                Enumeration<? extends ZipEntry> en = zf.entries();
                int n = 0;
                while (en.hasMoreElements() && n < 200) {
                    n++;
                    String name = en.nextElement().getName();
                    if (name.indexOf('\uFFFD') >= 0) {
                        return true;
                    }
                }
            } finally {
                zf.close();
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private static File findDir(File root, String name, int depth) {
        if (depth < 0) {
            return null;
        }
        File[] list = root.listFiles();
        if (list == null) {
            return null;
        }
        for (File f : list) {
            if (f.isDirectory() && f.getName().equalsIgnoreCase(name)) {
                return f;
            }
        }
        for (File f : list) {
            if (f.isDirectory()) {
                File hit = findDir(f, name, depth - 1);
                if (hit != null) {
                    return hit;
                }
            }
        }
        return null;
    }

    private static String commonFirst(List<String> rels) {
        Set<String> firsts = new HashSet<String>();
        String common = null;
        int count = 0;
        for (String rel : rels) {
            int slash = rel.indexOf('/');
            if (slash <= 0) {
                return null;
            }
            firsts.add(rel.substring(0, slash));
            count++;
        }
        if (firsts.size() == 1) {
            common = firsts.iterator().next();
        }
        return count == 0 ? null : common;
    }

    /** 在游戏 files 目录创建空的 localmode.txt（幂等），经 Engine 通道写入。 */
    private static boolean installLocalMode(Engine eng, String pkg, String[] hints) {
        try {
            Engine.writeText(new File(Engine.gameFiles(pkg), "localmode.txt"), "");
            return true;
        } catch (Throwable t) {
            if (hints != null && hints.length > 0) {
                String message = t.getMessage();
                hints[0] = (message == null || message.trim().length() == 0)
                        ? t.getClass().getSimpleName() : message;
            }
            return false;
        }
    }

    /** 递归查找指定文件名（忽略大小写，只匹配普通文件）。 */
    private static File findFileByName(File root, String name) {
        if (root == null || name == null) {
            return null;
        }
        if (root.isFile()) {
            return name.equalsIgnoreCase(root.getName()) ? root : null;
        }
        if (!root.isDirectory()) {
            return null;
        }
        File[] children = root.listFiles();
        if (children == null) {
            return null;
        }
        for (File child : children) {
            File match = findFileByName(child, name);
            if (match != null) {
                return match;
            }
        }
        return null;
    }

    public static void deleteTree(File f) {
        File[] list = f.listFiles();
        if (list != null) {
            for (File c : list) {
                deleteTree(c);
            }
        }
        f.delete();
    }
}