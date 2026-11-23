package com.lvshu.build;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 补丁引擎：扫描补丁存放目录、定位 assetbundle、
 * 备份/覆盖/还原。
 *
 * 游戏目录中的存在性检查、索引、读取、写入及校验全部走 GameFileSystem。
 * 私有工作目录、私有备份、历史记录和普通补丁源文件仍可使用本地 Java I/O。
 */
public final class Engine {

    public static final String EXT = "/storage/emulated/0";
    /** 补丁存放目录：与「酸败补丁助手」共用同一目录。 */
    public static final String PATCH_DIR = EXT + "/LvShu/酸败补丁/补丁存放位置（zip）";
    /** 酸败补丁助手的 CAB 索引根目录（两工具共用 /storage/emulated/0/LvShu/酸败补丁）。 */
    public static final String SUIBAI_INDEX_ROOT = EXT + "/LvShu/酸败补丁/缓存/资源索引";
    /** 游戏资源相对 files 的两个根（与酸败补丁助手一致：data 与 flies）。 */
    private static final String[] RES_ROOTS = {"data", "flies"};
    public static final String DEFAULT_GAME = "com.jumpw.mobile300";

    /**
     * 当前补丁安装的工作根目录（游戏 files 绝对路径）。
     * 由文件页在用户浏览到某个渠道服的 files 目录时设置；
     * 非空时 gameFiles() 直接返回它，使补丁装到用户当前浏览的目录。
     */
    private static volatile String workFilesRoot = null;
    private static final String PREF_WORK_ROOT = "work_files_root";

    /** 设置当前工作根目录；仅接受合法的游戏 files 路径，其它目录忽略；并持久化。 */
    public static void setWorkFilesRoot(String path) {
        if (path == null) {
            return;
        }
        String root = GameStorage.gameRoot(path);
        if (root != null) {
            workFilesRoot = root;
            persistWorkRoot(root);
        }
    }

    /** 清除工作根目录覆盖；切换目标客户端时调用，恢复按包名计算；并持久化。 */
    public static void clearWorkFilesRoot() {
        workFilesRoot = null;
        persistWorkRoot(null);
    }

    /** 当前是否已有工作根目录覆盖。 */
    public static boolean hasWorkFilesRoot() {
        return workFilesRoot != null;
    }

    /**
     * 应用启动时从偏好恢复上次的工作目录覆盖。
     * 必须在任何 gameFiles()/补丁安装之前调用；非法或失效的路径会被清除。
     */
    public static void restoreWorkRoot(Context ctx) {
        try {
            Context c = ctx != null ? ctx : storageContext;
            if (c == null) {
                return;
            }
            String saved = c.getSharedPreferences("cfg", 0)
                    .getString(PREF_WORK_ROOT, null);
            if (saved == null || saved.length() == 0) {
                workFilesRoot = null;
                return;
            }
            String root = GameStorage.gameRoot(saved);
            workFilesRoot = root; // 非法路径直接丢弃，不持久化残留
        } catch (Throwable ignored) {
        }
    }

    private static void persistWorkRoot(String path) {
        try {
            Context c = storageContext;
            if (c == null) {
                return;
            }
            android.content.SharedPreferences.Editor e =
                    c.getSharedPreferences("cfg", 0).edit();
            if (path == null) {
                e.remove(PREF_WORK_ROOT);
            } else {
                e.putString(PREF_WORK_ROOT, path);
            }
            e.apply();
        } catch (Throwable ignored) {
        }
    }

    public interface Progress {
        void on(int percent, String message);
    }

    public static final class Patch {
        public File dir;
        public String folder = "";
        public String name = "";
        public String desc = "";
        public String ver = "";
        public String author = "";
        public int count;
        public final List<String> rels = new ArrayList<String>();
    }

    public static final class Item {
        public File src;
        public String dst;
        public String name;

        public Item(File src, String dst, String name) {
            this.src = src;
            this.dst = dst;
            this.name = name;
        }
    }

    public static final class Hist {
        public String time = "";
        public String tag = "";
        public String name = "";
        public String dst = "";
        public String bak = "";
    }

    private final Context ctx;

    /*
     * 保留原有静态文件工具签名，供原 UI/其它类继续调用。
     * 静态工具收到游戏路径时，同样通过已绑定的应用 Context 选择通道。
     * 不保存 Activity，避免静态引用导致 Activity 泄漏。
     */
    private static volatile Context storageContext;

    public Engine(Context ctx) {
        if (ctx == null) {
            throw new IllegalArgumentException("Context 不能为空");
        }
        Context app = ctx.getApplicationContext();
        this.ctx = app == null ? ctx : app;
        storageContext = this.ctx;
    }

    // ---------------- 路径 ----------------

    public static String gameFiles(String pkg) {
        // 用户若在文件页浏览到了某个目录，补丁就装到那个目录（哪个渠道服由用户选）。
        String override = workFilesRoot;
        if (override != null) {
            return override;
        }
        GameStorage.checkPackage(pkg);
        return EXT + "/Android/data/" + pkg + "/files";
    }

    public static String bundleRoot(String pkg) {
        return gameFiles(pkg) + "/data/android/assetbundle";
    }

    public File workDir() {
        File dir = new File(ctx.getFilesDir(), ".patch/work");
        GameStorage.direct().mkdirs(dir.getAbsolutePath());
        return dir;
    }

    public File backupDir() {
        File dir = new File(ctx.getFilesDir(), ".patch/backups");
        GameStorage.direct().mkdirs(dir.getAbsolutePath());
        return dir;
    }

    /** 清理全部备份快照，返回删除的备份目录数。 */
    public int clearBackups() {
        int deleted = 0;
        try {
            java.io.File root = backupDir();
            java.io.File[] children = root.listFiles();
            if (children != null) {
                for (java.io.File child : children) {
                    try {
                        if (!child.isDirectory()) {
                            continue;
                        }
                        ZipPatch.deleteTree(child);
                        if (!child.exists()) {
                            deleted++;
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return deleted;
    }

    /**
     * 撤销某补丁：读取其最新备份的 manifest，删除所有安装时写入的游戏文件。
     * 不调用 Shizuku/SU，走 fileSystem(target) 对应的直装通道。
     */
    public int deleteInstalled(String folder) {
        int deleted = 0;
        java.io.InputStream input = null;
        java.io.BufferedReader reader = null;
        try {
            if (folder == null || folder.length() == 0
                    || ".".equals(folder) || "..".equals(folder)
                    || folder.indexOf('/') >= 0 || folder.indexOf('\\') >= 0) {
                return 0;
            }
            java.io.File snapshot = latestBackup(folder);
            if (snapshot == null) {
                return 0;
            }
            java.io.File manifest = new java.io.File(snapshot, "manifest.txt");
            input = new java.io.FileInputStream(manifest);
            reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(input, "UTF-8"));
            String line;
            while ((line = reader.readLine()) != null) {
                try {
                    String[] columns = line.split("\t", -1);
                    if (columns.length != 3) {
                        continue;
                    }
                    String target = columns[2];
                    if (target.length() == 0
                            || !new java.io.File(target).isAbsolute()) {
                        continue;
                    }
                    fileSystem(target).delete(target);
                    deleted++;
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        } finally {
            try {
                if (reader != null) {
                    reader.close();
                } else if (input != null) {
                    input.close();
                }
            } catch (Throwable ignored) {
            }
        }
        return deleted;
    }

    /** 备份目录总字节数。 */
    public long backupSize() {
        long total = 0L;
        try {
            total = sizeOf(new File(backupDir().getCanonicalPath()));
        } catch (Throwable ignored) {
        }
        return total;
    }

    private static long sizeOf(java.io.File entry) {
        long total = 0L;
        try {
            if (entry.isFile()) {
                return entry.length();
            }
            if (entry.isDirectory()) {
                java.io.File[] kids = entry.listFiles();
                if (kids != null) {
                    for (java.io.File k : kids) {
                        total += sizeOf(k);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return total;
    }

    public File historyFile() {
        return new File(ctx.getFilesDir(), ".patch/history.txt");
    }

    public static String stamp() {
        return new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT)
                .format(new Date());
    }

    // ---------------- 通道 ----------------

    /**
     * 所有游戏文件入口共用的选择逻辑。
     *
     * 先绑定实际目标，再 ensureAccess(ctx)，最后 pick(ctx)。
     * Scope 内的两个调用返回同一个经目标目录实测通过的实现。
     */
    private static GameFileSystem select(Context context, String path) {
        GameStorage.Scope scope = GameStorage.target(path);
        try {
            GameStorage.ensureAccess(context);
            return GameStorage.pick(context);
        } finally {
            scope.close();
        }
    }

    private GameFileSystem gameFs(String path) {
        requireGamePath(path);
        return select(ctx, path);
    }

    private static GameFileSystem fileSystem(String path) {
        if (GameStorage.gameRoot(path) == null) {
            return GameStorage.direct();
        }
        Context context = storageContext;
        if (context == null) {
            throw new GameFileSystem.StorageException(
                    "尚未初始化 Engine，无法选择游戏文件访问通道");
        }
        return select(context, path);
    }

    private static String requireGamePath(String raw) {
        String path = FsSupport.path(raw);
        if (GameStorage.gameRoot(path) == null) {
            throw new GameFileSystem.StorageException(
                    "不是有效的游戏 files 路径：" + raw);
        }
        return path;
    }

    private static IOException io(RuntimeException cause) {
        return new IOException(cause.getMessage(), cause);
    }

    private static void checkManifestField(String text, String label)
            throws IOException {
        if (text == null || text.indexOf('\t') >= 0
                || text.indexOf('\r') >= 0 || text.indexOf('\n') >= 0
                || text.indexOf('\0') >= 0) {
            throw new IOException(label + "包含无效字符");
        }
    }

    private static void checkBackupName(String name) throws IOException {
        checkManifestField(name, "文件名");
        if (name.length() == 0 || name.indexOf('/') >= 0
                || name.indexOf('\\') >= 0
                || ".".equals(name) || "..".equals(name)) {
            throw new IOException("备份文件名无效：" + name);
        }
    }

    private static String relative(String rel) {
        if (rel == null || rel.length() == 0 || rel.startsWith("/")
                || rel.indexOf('\\') >= 0 || rel.indexOf('\0') >= 0
                || rel.indexOf('\t') >= 0 || rel.indexOf('\r') >= 0
                || rel.indexOf('\n') >= 0) {
            throw new GameFileSystem.StorageException("相对路径无效：" + rel);
        }
        String[] parts = rel.split("/", -1);
        for (String part : parts) {
            if (part.length() == 0 || ".".equals(part) || "..".equals(part)) {
                throw new GameFileSystem.StorageException("相对路径无效：" + rel);
            }
        }
        return rel;
    }

    // ---------------- 文件工具：保留原公共签名 ----------------

    public static void mkdirs(File dir) {
        if (dir != null) {
            fileSystem(dir.getAbsolutePath()).mkdirs(dir.getAbsolutePath());
        }
    }

    public static void copy(File src, File dst) throws IOException {
        try {
            String source = FsSupport.path(src.getAbsolutePath());
            String target = FsSupport.path(dst.getAbsolutePath());

            /*
             * 普通本地文件保持流式复制，避免 APK/ZIP 在内存中整体展开。
             * 任一端是游戏路径时，必须通过对应 GameFileSystem。
             */
            if (GameStorage.gameRoot(source) == null
                    && GameStorage.gameRoot(target) == null) {
                GameStorage.direct().mkdirs(FsSupport.parent(target));
                InputStream in = new FileInputStream(src);
                try {
                    OutputStream out = new FileOutputStream(dst);
                    try {
                        byte[] buffer = new byte[81920];
                        int n;
                        while ((n = in.read(buffer)) != -1) {
                            out.write(buffer, 0, n);
                        }
                        out.flush();
                    } finally {
                        out.close();
                    }
                } finally {
                    in.close();
                }
                return;
            }

            GameFileSystem sourceFs = fileSystem(source);
            GameFileSystem targetFs = fileSystem(target);
            byte[] data = sourceFs.read(source);
            targetFs.write(target, data);
            if (!FsSupport.hash(data).equals(targetFs.sha256(target))) {
                throw new IOException("复制后校验失败：" + target);
            }
        } catch (RuntimeException e) {
            throw io(e);
        }
    }

    public static void writeText(File file, String text) throws IOException {
        try {
            String path = file.getAbsolutePath();
            fileSystem(path).write(path, text.getBytes(FsSupport.UTF8));
        } catch (RuntimeException e) {
            throw io(e);
        }
    }

    public static String readText(File file) {
        String path = file.getAbsolutePath();

        /*
         * 游戏目录读取失败不能伪装成空文本，否则会再次掩盖权限断链。
         * 非游戏文件保持原来的“失败返回空串”行为。
         */
        if (GameStorage.gameRoot(path) != null) {
            return new String(fileSystem(path).read(path), FsSupport.UTF8);
        }
        try {
            return new String(GameStorage.direct().read(path), FsSupport.UTF8);
        } catch (RuntimeException e) {
            return "";
        }
    }

    /**
     * 名称归一化：只保留字母/数字并转小写，但必须保留中文。
     * 移植自酸败补丁助手：旧的 replaceAll("[^a-z0-9]","") 会把中文全删，
     * 导致「李白.assetbundle」「韩信.assetbundle」都归一成 "assetbundle" 而互相错位。
     * Character.isLetterOrDigit 对 CJK 返回 true，故中文得以保留。
     */
    public static String norm(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString();
    }

    /** 返回实际完成目标目录读写探测的通道名称。 */
    public String accessMode(String pkg) {
        try {
            GameFileSystem fs = gameFs(gameFiles(pkg));
            switch (fs.backend()) {
                case DIRECT:
                    return "直连";
                case ROOT:
                    return "root";
                case SHIZUKU:
                    return "Shizuku";
                default:
                    return "不可用";
            }
        } catch (RuntimeException e) {
            return "不可用";
        }
    }

    // ---------------- 补丁扫描 ----------------

    public List<Patch> listPatches() {
        List<Patch> out = new ArrayList<Patch>();
        File root = new File(Paths.patchDir(ctx));
        File[] subs = root.listFiles();
        if (subs == null) {
            return out;
        }
        for (File dir : subs) {
            if (!dir.isDirectory() || dir.getName().startsWith(".")) {
                continue;
            }
            File files = new File(dir, "files");
            if (!files.isDirectory()) {
                continue;
            }
            Patch patch = new Patch();
            patch.dir = dir;
            patch.folder = dir.getName();
            patch.name = dir.getName();
            readInfo(patch);
            walk(files, "", patch.rels);
            patch.count = patch.rels.size();
            if (patch.count > 0) {
                out.add(patch);
            }
        }
        Collections.sort(out, new java.util.Comparator<Patch>() {
            @Override
            public int compare(Patch a, Patch b) {
                return a.folder.compareToIgnoreCase(b.folder);
            }
        });
        return out;
    }

    private void readInfo(Patch patch) {
        File file = new File(patch.dir, "info.txt");
        if (!file.isFile()) {
            return;
        }
        String text = readText(file);
        for (String line : text.split("[\r\n]+")) {
            int equal = line.indexOf('=');
            if (equal <= 0) {
                continue;
            }
            String key = line.substring(0, equal).trim()
                    .toLowerCase(Locale.ROOT);
            String value = line.substring(equal + 1).trim();
            if ("name".equals(key)) {
                patch.name = value;
            } else if ("desc".equals(key)) {
                patch.desc = value;
            } else if ("ver".equals(key)) {
                patch.ver = value;
            } else if ("author".equals(key)) {
                patch.author = value;
            }
        }
    }

    public static void walk(File dir, String base, List<String> acc) {
        String path = dir.getAbsolutePath();
        if (GameStorage.gameRoot(path) != null) {
            walkRelative(fileSystem(path), path, base, acc, 0);
            return;
        }
        walkLocal(dir, base, acc, 0);
    }

    private static void walkLocal(File dir, String base,
                                  List<String> acc, int depth) {
        if (depth > 128) {
            throw new GameFileSystem.StorageException("目录层级过深：" + dir);
        }
        File[] list = dir.listFiles();
        if (list == null) {
            return;
        }
        for (File file : list) {
            if (file.isDirectory()) {
                walkLocal(file, base + file.getName() + "/", acc, depth + 1);
            } else {
                acc.add(base + file.getName());
            }
        }
    }

    private static void walkRelative(GameFileSystem fs, String path,
                                     String base, List<String> acc, int depth) {
        if (depth > 128) {
            throw new GameFileSystem.StorageException("目录层级过深：" + path);
        }
        String[] names = fs.list(path);
        if (names == null) {
            throw new GameFileSystem.StorageException("不是目录：" + path);
        }
        for (String name : names) {
            String child = path + "/" + name;
            String[] children = fs.list(child);
            if (children == null) {
                acc.add(base + name);
            } else {
                walkRelativeKnown(fs, child, children,
                        base + name + "/", acc, depth + 1);
            }
        }
    }

    private static void walkRelativeKnown(GameFileSystem fs, String path,
                                          String[] names, String base,
                                          List<String> acc, int depth) {
        if (depth > 128) {
            throw new GameFileSystem.StorageException("目录层级过深：" + path);
        }
        for (String name : names) {
            String child = path + "/" + name;
            String[] children = fs.list(child);
            if (children == null) {
                acc.add(base + name);
            } else {
                walkRelativeKnown(fs, child, children,
                        base + name + "/", acc, depth + 1);
            }
        }
    }

    // ---------------- 安装 / 备份 ----------------

    public List<Item> itemsOf(Patch patch, String pkg) {
        List<Item> items = new ArrayList<Item>();
        String base = gameFiles(pkg);
        for (String raw : patch.rels) {
            String rel = relative(raw);
            File src = new File(patch.dir, "files/" + rel);
            String name = rel.substring(rel.lastIndexOf('/') + 1);
            items.add(new Item(src, base + "/" + rel, name));
        }
        return items;
    }

    /**
     * 备份后覆盖安装。
     * 私有备份仍使用旧目录和三列 manifest 格式，兼容原有历史与还原入口。
     */
    public String install(List<Item> items, String tag, String folder,
                          Progress cb) throws IOException {
        if (items.isEmpty()) {
            return "没有可安装的文件";
        }

        try {
            /*
             * 写入任何游戏文件前，先为全部实际目标选择通道。
             * 按目标根目录缓存本次操作实例，循环中不退回 DIRECT。
             */
            Map<String, GameFileSystem> channels =
                    new HashMap<String, GameFileSystem>();
            for (Item item : items) {
                if (item == null || item.src == null) {
                    throw new IOException("安装项缺少源文件");
                }
                String target = requireGamePath(item.dst);
                checkManifestField(target, "目标路径");
                checkBackupName(item.name);
                String root = GameStorage.gameRoot(target);
                if (!channels.containsKey(root)) {
                    channels.put(root, gameFs(target));
                }
            }

            File bak = new File(backupDir(),
                    (folder == null || folder.length() == 0
                            ? "" : folder + "/")
                            + tag + "_" + stamp());
            GameFileSystem local = GameStorage.direct();
            local.mkdirs(bak.getAbsolutePath());

            File manifestFile = new File(bak, "manifest.txt");
            StringBuilder manifest = new StringBuilder();
            local.write(manifestFile.getAbsolutePath(), new byte[0]);

            int i = 0;
            for (Item item : items) {
                i++;
                String target = requireGamePath(item.dst);
                GameFileSystem fs = channels.get(GameStorage.gameRoot(target));

                byte[] replacement = fileSystem(item.src.getAbsolutePath())
                        .read(item.src.getAbsolutePath());

                if (fs.exists(target)) {
                    if (fs.list(target) != null) {
                        throw new IOException("目标是目录，不能覆盖：" + target);
                    }

                    byte[] original = fs.read(target);
                    File backup = new File(bak, "b" + i + "_" + item.name);
                    local.write(backup.getAbsolutePath(), original);

                    manifest.append(i).append('\t')
                            .append(item.name).append('\t')
                            .append(target).append('\n');

                    /*
                     * 在覆盖前持久化清单；后续安装失败时，
                     * 已备份文件仍可通过旧还原入口恢复。
                     */
                    local.write(manifestFile.getAbsolutePath(),
                            manifest.toString().getBytes(FsSupport.UTF8));
                }

                fs.write(target, replacement);
                if (!FsSupport.hash(replacement).equals(fs.sha256(target))) {
                    throw new IOException("安装后 SHA-256 校验失败：" + target);
                }

                if (cb != null) {
                    cb.on(Math.min(99, i * 100 / items.size()),
                            "安装 " + item.name);
                }
            }

            addHist(tag, folder == null ? tag : folder,
                    gameFilesOf(items), bak.getAbsolutePath());
            if (cb != null) {
                cb.on(100, "完成");
            }
            return "";
        } catch (RuntimeException e) {
            throw io(e);
        }
    }

    private String gameFilesOf(List<Item> items) {
        return items.isEmpty() ? "" : items.get(0).dst;
    }

    /** 从私有备份目录读取旧清单，经目标游戏对应通道还原。 */
    public String restore(File bak) throws IOException {
        File manifestFile = new File(bak, "manifest.txt");
        if (!manifestFile.isFile()) {
            return "该备份没有还原清单";
        }

        try {
            GameFileSystem local = GameStorage.direct();
            String text = new String(
                    local.read(manifestFile.getAbsolutePath()), FsSupport.UTF8);

            List<String[]> entries = new ArrayList<String[]>();
            Map<String, GameFileSystem> channels =
                    new HashMap<String, GameFileSystem>();

            /*
             * 先解析并探测全部目标，不能用当前 UI 包名替代旧备份里的包名。
             */
            for (String line : text.split("[\r\n]+")) {
                String[] fields = line.split("\t", -1);
                if (fields.length < 3) {
                    continue;
                }
                if (!fields[0].matches("[0-9]+")) {
                    throw new IOException("备份清单序号无效");
                }
                checkBackupName(fields[1]);
                checkManifestField(fields[2], "还原目标");

                String target = requireGamePath(fields[2]);
                String root = GameStorage.gameRoot(target);
                if (!channels.containsKey(root)) {
                    channels.put(root, gameFs(target));
                }

                File source = new File(
                        bak, "b" + fields[0] + "_" + fields[1]);
                if (!source.isFile()) {
                    throw new IOException("备份文件缺失：" + source.getName());
                }
                entries.add(new String[]{source.getAbsolutePath(), target});
            }

            for (String[] entry : entries) {
                byte[] data = local.read(entry[0]);
                GameFileSystem fs = channels.get(
                        GameStorage.gameRoot(entry[1]));
                fs.write(entry[1], data);
                if (!FsSupport.hash(data).equals(fs.sha256(entry[1]))) {
                    throw new IOException("还原后 SHA-256 校验失败：" + entry[1]);
                }
            }

            removeHist(bak.getAbsolutePath());
            return "";
        } catch (RuntimeException e) {
            throw io(e);
        }
    }

    /** 安装单个 .assetbundle：先经实际通道定位，再备份覆盖。 */
    public String installBundle(File src, String name, String tag,
                                String pkg, Progress cb) throws IOException {
        try {
            if (cb != null) {
                cb.on(35, "准备资源索引（优先复用酸败表，请稍候）…");
            }
            Index index = buildIndex(pkg, cb);
            String[] hit = locate(pkg, index, name, null);
            if (hit[0] == null) {
                return "未定位到 " + name + "（" + hit[1] + "）";
            }
            List<Item> items = new ArrayList<Item>();
            items.add(new Item(src, hit[0], name));
            return install(items, tag, null, cb);
        } catch (RuntimeException e) {
            throw io(e);
        }
    }

    public File latestBackup(String folder) {
        File dir = new File(backupDir(), folder);
        File[] list = dir.listFiles();
        if (list == null) {
            return null;
        }
        File best = null;
        for (File file : list) {
            if (!file.isDirectory()) {
                continue;
            }
            if (best == null
                    || file.getName().compareTo(best.getName()) > 0) {
                best = file;
            }
        }
        return best;
    }

    // ---------------- 历史 ----------------

    public void addHist(String tag, String name, String dst, String bak) {
        try {
            String line = new SimpleDateFormat(
                    "yyyy-MM-dd HH:mm", Locale.ROOT).format(new Date())
                    + "\t" + tag + "\t" + name + "\t" + dst
                    + "\t" + bak + "\n";
            File file = historyFile();
            GameStorage.direct().mkdirs(
                    file.getParentFile().getAbsolutePath());
            FileOutputStream out = new FileOutputStream(file, true);
            try {
                out.write(line.getBytes(FsSupport.UTF8));
            } finally {
                out.close();
            }
        } catch (IOException ignored) {
        } catch (GameFileSystem.StorageException ignored) {
        }
    }

    public List<Hist> history() {
        List<Hist> out = new ArrayList<Hist>();
        File file = historyFile();
        if (!file.isFile()) {
            return out;
        }
        for (String line : readText(file).split("[\r\n]+")) {
            String[] fields = line.split("\t");
            if (fields.length < 4) {
                continue;
            }
            Hist hist = new Hist();
            hist.time = fields[0];
            hist.tag = fields[1];
            hist.name = fields[2];
            hist.dst = fields[3];
            hist.bak = fields.length > 4 ? fields[4] : "";
            out.add(hist);
        }
        Collections.reverse(out);
        return out;
    }

    public void removeHist(String bak) {
        File file = historyFile();
        if (!file.isFile()) {
            return;
        }
        StringBuilder keep = new StringBuilder();
        for (String line : readText(file).split("[\r\n]+")) {
            if (line.trim().isEmpty()) {
                continue;
            }
            String[] fields = line.split("\t");
            String backup = fields.length > 4 ? fields[4] : "";
            if (!backup.equals(bak)) {
                keep.append(line).append('\n');
            }
        }
        try {
            writeText(file, keep.toString());
        } catch (IOException ignored) {
        }
    }

    /** 目标包在系统里的 uid；取不到返回 -1。 */
    public static int uidOf(Context ctx, String pkg) {
        try {
            return ctx.getPackageManager().getApplicationInfo(pkg, 0).uid;
        } catch (Throwable e) {
            return -1;
        }
    }

    /** 本应用自己声明的 sharedUserId。 */
    public static String selfSharedUserId(Context ctx) {
        try {
            return ctx.getPackageManager()
                    .getPackageInfo(ctx.getPackageName(), 0).sharedUserId;
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * 保留诊断调用点。游戏目录使用实际选中的访问通道，
     * 不再用普通 File.listFiles 的失败否定 root/Shizuku 通道。
     */
    public static boolean canList(File dir) {
        if (dir == null) {
            return false;
        }
        try {
            String path = dir.getAbsolutePath();
            return fileSystem(path).list(path) != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ---------------- 游戏资源索引（移植自酸败补丁助手的定位体系）----------------

    /**
     * 资源索引。所有路径均为【相对游戏 files 目录的相对路径】，用 '/' 分隔，
     * 形如 data/android/assetbundle/xxx.assetbundle —— 与酸败补丁助手的 index.tsv 完全一致。
     * - rels：游戏内所有资源文件的相对路径集合（用于精确路径剥皮命中）。
     * - byName：文件名 → 相对路径列表（用于同名定位）。
     * - cabMap：CAB 身份键 → 相对路径列表（用于同名多候选消歧）。
     */
    public static final class Index {
        public final java.util.Set<String> rels =
                new java.util.HashSet<String>();
        public final Map<String, List<String>> byName =
                new HashMap<String, List<String>>();
        public final Map<String, List<String>> cabMap =
                new HashMap<String, List<String>>();
        public int total;
        /** CAB 表来源：>0 表示直接复用了酸败补丁助手已建好的表（免建表）。 */
        public int cabFromShared;
        /** 自建表时实际读到的 CAB 条数（复用酸败表时为 0）。 */
        public int cabSelfBuilt;
        /** 增量追加的 CAB 条数（旧表基础上补的新资源）。 */
        public int cabIncremental;
        /** 旧表已收录的相对路径集合（相对 files），用于判断哪些是新增资源。 */
        public final java.util.Set<String> oldRels =
                new java.util.HashSet<String>();
    }

    /** 保留 byNorm 字段仅为兼容旧引用；新定位不再使用它做模糊匹配。 */
    /** 建立索引（无进度回调，兼容旧调用）。 */
    public Index buildIndex(String pkg) {
        return buildIndex(pkg, null);
    }

    /**
     * 建立索引：扫描 files/data 与 files/flies 两棵子树，索引键为相对游戏 files 的路径。
     *
     * CAB 表来源优先级：
     *  ① 两工具共用 /storage/emulated/0/LvShu/酸败补丁 根目录。若用户此前用酸败
     *     补丁助手建过表（缓存/资源索引/<包名>/index.tsv），直接复用，免去重扫——
     *     这正是“目录共享、不另建一份”的本土化做法。
     *  ② 没有现成表时才自建：直装与游戏同 sharedUserId，普通 java.io.File 有读权限，
     *     只读每个 ab 的前 8192 字节（等价酸败 head -c 8192），
     *     不 fork shell、不读整个几十 MB 的文件（那会卡死/OOM）。
     * 建表是重活，必须弹提示并报进度。
     */
    public Index buildIndex(String pkg, Progress cb) {
        String files = gameFiles(pkg);
        Index index = new Index();
        String filesPrefix = files.endsWith("/") ? files : files + "/";

        // 直装与游戏同 sharedUserId，普通 java.io.File 就有读权限（与文件页同一访问链）。
        // 绝不再走 GameStorage.pick() 的 root/Shizuku 探针通道：那会在已 root 的设备上
        // 被 root 通道接手、漏列目录，导致只扫到几千个文件（实际约 26184 个 assetbundle）。
        // 扫描 data 与 flies 两棵子树下的所有文件（与酸败一致），路径相对 files。
        List<String> paths = new ArrayList<String>();
        for (String sub : RES_ROOTS) {
            File root = new File(filesPrefix + sub);
            if (!root.isDirectory()) {
                continue;
            }
            walkLocal(root, paths, 0);
        }
        int total = paths.size();
        int done = 0;
        for (String abs : paths) {
            String rel = abs.startsWith(filesPrefix) ? abs.substring(filesPrefix.length()) : abs;
            if (rel.length() == 0) {
                continue;
            }
            index.rels.add(rel);
            String name = rel.substring(rel.lastIndexOf('/') + 1);
            put(index.byName, name, rel);
            done++;
            if (cb != null && (done % 500 == 0 || done == total)) {
                cb.on(40 + Math.min(6, done * 6 / Math.max(1, total)),
                        "扫描游戏资源 " + done + "/" + total + "…");
            }
        }
        index.total = total;
        if (total == 0) {
            return index;
        }

        // 从实际游戏目录提取真实包名（兼容文件页切到其它渠道服）。
        String realPkg = pkgFromGamePath(files);
        if (realPkg == null) {
            realPkg = pkg;
        }

        // ① 读旧表（酸败建的，或本工具上次建的）。修掉大小写 bug 后这里能正确命中。
        //    同时把旧表原文读进 oldTsv、把已收录 rel 记进 index.oldRels，供增量比对。
        StringBuilder oldTsv = new StringBuilder(256 * 1024);
        int shared = loadSharedCabIndex(realPkg, index, oldTsv);
        if (shared > 0) {
            index.cabFromShared = shared;

            // 找出"当前游戏里有、但旧表没收录"的 ab 文件 = 新增资源。
            List<String> newAbs = new ArrayList<String>();
            for (String abs : paths) {
                String rel = abs.startsWith(filesPrefix) ? abs.substring(filesPrefix.length()) : abs;
                String name = abs.substring(abs.lastIndexOf('/') + 1);
                if (!name.toLowerCase(Locale.ROOT).endsWith(".assetbundle")) {
                    continue;
                }
                if (!index.oldRels.contains(rel)) {
                    newAbs.add(abs);
                }
            }

            if (newAbs.isEmpty()) {
                // 资源没变化：整表沿用，绝不重建、绝不落盘（meta.txt 不动），秒开。
                if (cb != null) {
                    cb.on(70, "复用 CAB 表 " + shared + " 条，资源无变化");
                }
                return index;
            }

            // 有新增：只对新增文件读 CAB 头，追加到旧表末尾后整体重写落盘（增量，不全量重建）。
            int added = 0;
            int ndone = 0;
            for (String abs : newAbs) {
                String rel = abs.startsWith(filesPrefix) ? abs.substring(filesPrefix.length()) : abs;
                String cab = cabOfSource(new File(abs));
                if (cab != null) {
                    put(index.cabMap, cab, rel);
                    // 与酸败格式一致：CAB- 大写 + 十六进制大写。
                    oldTsv.append(cab.toUpperCase(Locale.ROOT)).append('\t').append(rel).append('\n');
                    added++;
                }
                ndone++;
                if (cb != null && (ndone % 200 == 0 || ndone == newAbs.size())) {
                    cb.on(60 + Math.min(10, ndone * 10 / Math.max(1, newAbs.size())),
                            "CAB 表增量更新 " + ndone + "/" + newAbs.size() + "…");
                }
            }
            index.cabIncremental = added;
            if (added > 0) {
                saveSharedCabIndex(realPkg, files, oldTsv.toString(), shared + added);
            }
            if (cb != null) {
                cb.on(70, "CAB 表更新：复用 " + shared + " 条 + 新增 " + added + " 条");
            }
            return index;
        }

        // ② 完全没有旧表 → 全量自建，并落盘到同一目录，供两工具共享。
        // 直装同 UID，普通 File 有读权限；只读每个 ab 前 8K（等价 head -c 8192）。
        StringBuilder tsv = new StringBuilder(256 * 1024);
        done = 0;
        for (String abs : paths) {
            String rel = abs.startsWith(filesPrefix) ? abs.substring(filesPrefix.length()) : abs;
            String name = abs.substring(abs.lastIndexOf('/') + 1);
            if (name.toLowerCase(Locale.ROOT).endsWith(".assetbundle")) {
                String cab = cabOfSource(new File(abs));
                if (cab != null) {
                    put(index.cabMap, cab, rel);
                    index.cabSelfBuilt++;
                    // 落盘用酸败格式：CAB- 大写 + 十六进制大写，便于酸败工具也能读。
                    tsv.append(cab.toUpperCase(Locale.ROOT)).append('\t').append(rel).append('\n');
                }
            }
            done++;
            if (cb != null && (done % 200 == 0 || done == total)) {
                cb.on(46 + Math.min(24, done * 24 / Math.max(1, total)),
                        "建立 CAB 表 " + done + "/" + total + "…");
            }
        }
        // 落盘：与酸败同格式、同位置。缓存/资源索引/<包名>/index.tsv
        if (index.cabSelfBuilt > 0) {
            saveSharedCabIndex(realPkg, files, tsv.toString(), index.cabSelfBuilt);
        }
        return index;
    }

    /** 从游戏 files 绝对路径里取真实包名：…/Android/data/<pkg>/files → <pkg>。 */
    private static String pkgFromGamePath(String gamePath) {
        if (gamePath == null) {
            return null;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("/Android/data/([^/]+)").matcher(gamePath);
        return m.find() ? m.group(1) : null;
    }

    /**
     * 读取酸败补丁助手已建好的 CAB 表：<SUIBAI_INDEX_ROOT>/<包名>/index.tsv。
     * 每行 "CAB-xxx<TAB>相对游戏files路径"，如
     *   CAB-abc...\tdata/android/assetbundle/role/xxx.assetbundle
     * 本工具索引同样以 files 为根，故路径可直接用，无需转换。
     * CAB 统一转小写，与 cabOfBytes/cabOfSource 的键保持一致。
     * 【重要】校验前缀必须用小写 "cab-"（cab 已 toLowerCase），否则每行都被跳过、
     * 永远返回 0，导致每次装补丁都误判"无表"而全量重建——这是曾经的真机 bug。
     *
     * @param oldTsv 非 null 时，把规范化后的旧表原文逐行写回（CAB 大写），供增量追加后整体落盘。
     *               同时把每行 rel 记入 index.oldRels，用于判断哪些资源是新增。
     * 返回读入条数；0 表示无表/表失效，调用方回退全量自建。
     */
    private static int loadSharedCabIndex(String pkg, Index index, StringBuilder oldTsv) {
        if (pkg == null || pkg.length() == 0) {
            return 0;
        }
        File tsv = new File(new File(SUIBAI_INDEX_ROOT, pkg), "index.tsv");
        if (!tsv.isFile()) {
            return 0;
        }
        int count = 0;
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(
                    new FileInputStream(tsv), FsSupport.UTF8));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.length() == 0) {
                    continue;
                }
                int tab = line.indexOf('\t');
                if (tab <= 0) {
                    continue;
                }
                String cab = line.substring(0, tab).trim().toLowerCase(Locale.ROOT);
                String rel = line.substring(tab + 1).trim().replace('\\', '/');
                // 修复：cab 已转小写，前缀校验也必须用小写，否则整表被跳过。
                if (!cab.startsWith("cab-") || rel.length() == 0) {
                    continue;
                }
                put(index.cabMap, cab, rel);
                if (oldTsv != null) {
                    index.oldRels.add(rel);
                    // 保留旧表原文（统一 CAB 大写 + tab + rel），增量时在其后追加新行。
                    oldTsv.append(cab.toUpperCase(Locale.ROOT)).append('\t').append(rel).append('\n');
                }
                count++;
            }
        } catch (Throwable ignored) {
            return 0;
        } finally {
            if (reader != null) {
                try { reader.close(); } catch (Throwable ignored) { }
            }
        }
        return count;
    }

    /** 自建 CAB 表落盘：与酸败同目录同格式，附带 meta.txt 便于判定失效。 */
    private static void saveSharedCabIndex(String pkg, String files, String tsv, int count) {
        if (pkg == null || pkg.length() == 0) {
            return;
        }
        File dir = new File(SUIBAI_INDEX_ROOT, pkg);
        try {
            GameStorage.direct().mkdirs(dir.getAbsolutePath());
            writeLocal(new File(dir, "index.tsv"), tsv);
            long mtime = dirMtimeLocal(files + "/data") + dirMtimeLocal(files + "/flies");
            String meta = "pkg=" + pkg + "\ngame=" + files
                    + "\nmtime=" + mtime + "\ncount=" + count
                    + "\ntime=" + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
                            .format(new Date()) + "\n";
            writeLocal(new File(dir, "meta.txt"), meta);
        } catch (Throwable ignored) {
        }
    }

    private static long dirMtimeLocal(String path) {
        try {
            File f = new File(path);
            return f.isDirectory() ? f.lastModified() : 0L;
        } catch (Throwable e) {
            return 0L;
        }
    }

    private static void writeLocal(File file, String text) throws IOException {
        FileOutputStream out = new FileOutputStream(file);
        try {
            out.write(text.getBytes(FsSupport.UTF8));
        } finally {
            out.close();
        }
    }

    private static void put(Map<String, List<String>> map,
                            String key, String value) {
        List<String> list = map.get(key);
        if (list == null) {
            list = new ArrayList<String>();
            map.put(key, list);
        }
        list.add(value);
    }

    /**
     * 从文件头字节里提取 UnityFS 的 CAB 身份键：找 "CAB-" 后跟连续十六进制，
     * 取 32 位（不足则取实际≥20 位）。ISO-8859-1 保证字节 1:1 映成 char，不因编码丢 ASCII。
     * 移植自酸败补丁助手 cabOfFile。
     */
    private static String cabOfBytes(byte[] buf) {
        if (buf == null || buf.length == 0) {
            return null;
        }
        String s = new String(buf, Charset.forName("ISO-8859-1"));
        int i = s.indexOf("CAB-");
        while (i >= 0) {
            int e = i + 4;
            while (e < s.length() && isHexChar(s.charAt(e))) {
                e++;
            }
            int len = e - i - 4;
            if (len >= 32) {
                return s.substring(i, i + 4 + 32).toLowerCase(Locale.ROOT);
            }
            if (len >= 20) {
                return s.substring(i, e).toLowerCase(Locale.ROOT);
            }
            i = s.indexOf("CAB-", i + 4);
        }
        return null;
    }

    private static boolean isHexChar(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    /** 补丁源文件（本地普通 File）的 CAB 身份键，供同名多候选消歧用。 */
    public static String cabOfSource(File f) {
        if (f == null || !f.isFile()) {
            return null;
        }
        java.io.FileInputStream in = null;
        try {
            in = new java.io.FileInputStream(f);
            byte[] buf = new byte[8192];
            int n = 0;
            while (n < buf.length) {
                int r = in.read(buf, n, buf.length - n);
                if (r <= 0) {
                    break;
                }
                n += r;
            }
            byte[] head = new byte[n];
            System.arraycopy(buf, 0, head, 0, n);
            return cabOfBytes(head);
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (in != null) {
                try { in.close(); } catch (Throwable ignored) { }
            }
        }
    }

    /**
     * 逐级剥皮精确路径：rel 本身命中则直接用；否则从最左逐级去掉一层目录再拼文件名试探。
     * 解决补丁包多包一层（如 flies/data/... 或 李白/role/x.assetbundle）的问题。
     * 移植自酸败补丁助手 exactRel。
     */
    public static String exactRel(String rel, java.util.Set<String> all) {
        if (rel == null || all == null) {
            return null;
        }
        if (all.contains(rel)) {
            return rel;
        }
        int cut = rel.lastIndexOf('/');
        String name = rel.substring(cut + 1);
        String d = cut < 0 ? "" : rel.substring(0, cut);
        while (true) {
            int p = d.indexOf('/');
            d = (p < 0) ? "" : d.substring(p + 1);
            String probe = (d.length() == 0) ? name : (d + "/" + name);
            if (all.contains(probe)) {
                return probe;
            }
            if (d.length() == 0) {
                break;
            }
        }
        return null;
    }

    /**
     * 说明/预览类文件（png/md/txt 等，localmode.txt 例外）：不是游戏资源，
     * 定位不到时不要往游戏目录里塞。移植自酸败补丁助手 isSideFile。
     */
    public static boolean isSideFile(String rel) {
        if (rel == null) {
            return false;
        }
        String n = rel.toLowerCase(Locale.ROOT);
        String[] exts = {".png", ".jpg", ".jpeg", ".gif", ".webp", ".bmp",
                ".md", ".htm", ".html", ".url", ".log", ".pdf", ".rar", ".7z", ".mp4"};
        for (String ext : exts) {
            if (n.endsWith(ext)) {
                return true;
            }
        }
        if (n.endsWith(".txt")) {
            return !n.endsWith("localmode.txt");
        }
        return false;
    }

    /**
     * 三级定位，返回命中目标的【相对资源根路径】列表（相对路径用 '/'），未命中返回空列表。
     * ① 逐级剥皮精确路径；② 同名唯一；③ 同名多个 + CAB 身份校验（可一源多目标）。
     * 不同名不装、CAB 不符不装，绝不猜。移植自酸败补丁助手 locateTarget。
     *
     * @param rel    补丁文件相对路径（统一 '/'）
     * @param index  游戏资源索引
     * @param srcCab 补丁源文件的 CAB（仅同名多候选时需要，可为 null）
     */
    public static List<String> locateTargets(String rel, Index index, String srcCab) {
        List<String> empty = new ArrayList<String>();
        if (rel == null) {
            return empty;
        }
        rel = rel.replace('\\', '/');

        // ① 逐级剥皮精确路径
        String exact = exactRel(rel, index.rels);
        if (exact != null) {
            List<String> r = new ArrayList<String>();
            r.add(exact);
            return r;
        }

        // assets/android/assetbundle/ 是游戏 APK 内置资源，不是热更目录，不装。
        if (("/" + rel.toLowerCase(Locale.ROOT)).contains("/assets/android/assetbundle/")) {
            return empty;
        }

        String name = rel.substring(rel.lastIndexOf('/') + 1);
        List<String> same = index.byName.get(name);
        if (same == null || same.isEmpty()) {
            return empty;
        }

        // ② 同名唯一
        if (same.size() == 1) {
            return same;
        }

        // ③ 同名多个：CAB 双条件校验，命中全部目标（游戏可能复用同一资源）
        if (srcCab != null) {
            List<String> cabPaths = index.cabMap.get(srcCab);
            if (cabPaths != null) {
                List<String> verified = new ArrayList<String>();
                for (String cand : same) {
                    if (cabPaths.contains(cand)) {
                        verified.add(cand);
                    }
                }
                if (!verified.isEmpty()) {
                    return verified;
                }
            }
        }
        // 无 CAB 表 / CAB 不符 → 不猜
        return empty;
    }

    /**
     * 兼容旧的单文件安装路径（installBundle）：只有文件名，按相对路径索引做同名定位，
     * 返回 {绝对路径, how}。ZIP 安装路径已改用 locateTargets，不走这里。
     */
    public String[] locate(String pkg, Index index, String name, String rel) {
        String root = gameFiles(pkg);
        // rel 不为空时先按逐级剥皮精确路径定位
        if (rel != null && rel.length() > 0) {
            String exact = exactRel(rel.replace('\\', '/'), index.rels);
            if (exact != null) {
                return new String[]{root + "/" + exact, "结构匹配"};
            }
        }
        List<String> same = index.byName.get(name);
        if (same != null && same.size() == 1) {
            return new String[]{root + "/" + same.get(0), "同名定位"};
        }
        if (same != null && same.size() > 1) {
            return new String[]{root + "/" + same.get(0), "同名多处(取第1)"};
        }
        return new String[]{null, "游戏内未找到同名文件"};
    }

    /**
     * 普通 File 递归（直装同 UID 直读，与文件页同一访问链），不走 pick/root/Shizuku。
     * 收集绝对路径字符串，供 buildIndex 转相对 files 的路径。
     */
    private static void walkLocal(File dir, List<String> acc, int depth) {
        if (depth > 128) {
            return;
        }
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                walkLocal(child, acc, depth + 1);
            } else {
                acc.add(FsSupport.path(child.getAbsolutePath()));
            }
        }
    }

    private static void walkAbs(GameFileSystem fs, String path,
                                List<String> acc, int depth) {
        if (depth > 128) {
            throw new GameFileSystem.StorageException("目录层级过深：" + path);
        }
        String[] names = fs.list(path);
        if (names == null) {
            throw new GameFileSystem.StorageException("资源根路径不是目录：" + path);
        }
        walkAbsKnown(fs, path, names, acc, depth);
    }

    private static void walkAbsKnown(GameFileSystem fs, String path,
                                     String[] names, List<String> acc,
                                     int depth) {
        if (depth > 128) {
            throw new GameFileSystem.StorageException("目录层级过深：" + path);
        }
        for (String name : names) {
            String child = path + "/" + name;
            String[] children = fs.list(child);
            if (children == null) {
                acc.add(child);
            } else {
                walkAbsKnown(fs, child, children, acc, depth + 1);
            }
        }
    }
}
