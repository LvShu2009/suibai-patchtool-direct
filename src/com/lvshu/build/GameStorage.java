package com.lvshu.build;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Arrays;

/**
 * 游戏存储通道选择器（直装版）。
 *
 * 直装包与游戏使用相同 sharedUserId，普通 java.io.File 对游戏 files 目录
 * 有完整读写权限，因此唯一通道就是 DIRECT 直读——不依赖 root，不依赖 Shizuku。
 * DIRECT 探测在实际目标目录进行列举、写入、读取、校验、重命名和删除。
 *
 * 同一 Scope 内缓存选定实例，使 ensureAccess 与 pick 使用同一通道。
 */
public final class GameStorage {

    private static final DirectFileSystem DIRECT = new DirectFileSystem();
    private static final ThreadLocal<Selection> SELECTION =
            new ThreadLocal<Selection>();

    private GameStorage() {
    }

    public static GameFileSystem pick(Context ctx) {
        if (ctx == null) {
            throw new IllegalArgumentException("Context 不能为空");
        }

        Selection selection = SELECTION.get();
        if (selection != null && selection.fs != null) {
            return selection.fs;
        }

        String target;
        if (selection != null) {
            target = selection.target;
        } else {
            String pkg = ctx.getSharedPreferences("cfg", 0)
                    .getString("game_pkg", Engine.DEFAULT_GAME);
            target = Engine.gameFiles(pkg);
        }

        StringBuilder errors = new StringBuilder();

        // 直装唯一通道：普通 File 直读（与游戏同 sharedUserId，天然有权限）。
        if (probe(DIRECT, target, errors)) {
            return remember(selection, DIRECT);
        }

        throw new GameFileSystem.StorageException(
                "无法读写游戏目录：" + target
                        + "\n请先启动游戏生成 files 目录，再重试。"
                        + "\n（直装包与游戏同 UID，无需 root 或 Shizuku）"
                        + errors);
    }

    public static void ensureAccess(Context ctx) {
        pick(ctx);
    }

    /**
     * 显式选择某个游戏路径，适用于安装项、历史清单中的包名
     * 与当前 UI 选择的包名不同的情况。
     */
    public static GameFileSystem pick(Context ctx, String path) {
        Scope scope = target(path);
        try {
            ensureAccess(ctx);
            return pick(ctx);
        } finally {
            scope.close();
        }
    }

    static GameFileSystem direct() {
        return DIRECT;
    }

    private static GameFileSystem remember(Selection selection,
                                          GameFileSystem fs) {
        if (selection != null) {
            selection.fs = fs;
        }
        return fs;
    }

    /**
     * 返回 Android/data/<pkg>/files 根路径；非游戏目录返回 null。
     * 仅做字符串解析，不通过普通 File 探测受限目录。
     */
    static String gameRoot(String raw) {
        String path = FsSupport.path(raw);
        String marker = "/Android/data/";
        int offset = path.indexOf(marker);
        if (offset < 0 || !isExternalPrefix(path.substring(0, offset))) {
            return null;
        }

        int packageStart = offset + marker.length();
        int packageEnd = path.indexOf('/', packageStart);
        if (packageEnd < 0) {
            return null;
        }

        String pkg = path.substring(packageStart, packageEnd);
        checkPackage(pkg);

        String rest = path.substring(packageEnd);
        if (!"/files".equals(rest) && !rest.startsWith("/files/")) {
            return null;
        }
        return path.substring(0, packageEnd) + "/files";
    }

    private static boolean isExternalPrefix(String prefix) {
        return "/sdcard".equals(prefix)
                || "/storage/self/primary".equals(prefix)
                || prefix.matches("/storage/emulated/[0-9]+")
                || prefix.matches("/data/media/[0-9]+")
                || prefix.matches("/mnt/pass_through/[0-9]+/emulated/[0-9]+")
                || prefix.matches("/mnt/user/[0-9]+/emulated/[0-9]+");
    }

    static void checkPackage(String pkg) {
        if (pkg == null
                || !pkg.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")) {
            throw new GameFileSystem.StorageException("游戏包名无效：" + pkg);
        }
    }

    /**
     * Engine 在入口建立目标作用域，然后显式调用 ensureAccess(ctx)。
     * ThreadLocal 防止不同后台任务相互覆盖目标目录。
     */
    static Scope target(String raw) {
        String path = FsSupport.path(raw);
        String root = gameRoot(path);
        Selection previous = SELECTION.get();
        SELECTION.set(new Selection(root == null ? path : root));
        return new Scope(previous);
    }

    private static boolean probe(GameFileSystem fs, String target,
                                 StringBuilder errors) {
        String first = target + "/.lvshu_probe_" + FsSupport.uniqueName();
        String second = first + "_renamed";
        boolean attemptedWrite = false;
        boolean renamed = false;

        try {
            if (fs.list(target) == null) {
                throw new GameFileSystem.StorageException("目标不是目录");
            }
            if (fs.exists(first) || fs.exists(second)) {
                throw new GameFileSystem.StorageException("探针名称冲突");
            }

            byte[] data = {0, 1, 10, 13, 39, 92, 127, (byte) 128, (byte) 255};
            attemptedWrite = true;
            fs.write(first, data);

            if (!Arrays.equals(data, fs.read(first))) {
                throw new GameFileSystem.StorageException("探针回读不一致");
            }
            if (!FsSupport.hash(data).equals(fs.sha256(first))) {
                throw new GameFileSystem.StorageException("探针 SHA-256 不一致");
            }

            fs.rename(first, second);
            renamed = true;
            if (fs.exists(first) || !fs.exists(second)) {
                throw new GameFileSystem.StorageException("探针重命名未生效");
            }

            fs.delete(second);
            renamed = false;
            if (fs.exists(second)) {
                throw new GameFileSystem.StorageException("探针删除未生效");
            }
            attemptedWrite = false;
            return true;
        } catch (RuntimeException e) {
            appendError(errors, fs.backend().name(), e);
            return false;
        } finally {
            if (attemptedWrite) {
                try {
                    fs.delete(first);
                } catch (RuntimeException ignored) {
                }
            }
            if (renamed || attemptedWrite) {
                try {
                    fs.delete(second);
                } catch (RuntimeException ignored) {
                }
            }
        }
    }

    private static void appendError(StringBuilder errors, String label,
                                    Throwable error) {
        String message = error.getMessage();
        if (message == null || message.length() == 0) {
            message = error.getClass().getSimpleName();
        }
        if (message.length() > 320) {
            message = message.substring(0, 320);
        }
        errors.append('\n').append(label).append("：").append(message);
    }

    private static final class Selection {
        final String target;
        GameFileSystem fs;

        Selection(String target) {
            this.target = target;
        }
    }

    static final class Scope {
        private final Selection previous;
        private boolean closed;

        Scope(Selection previous) {
            this.previous = previous;
        }

        void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (previous == null) {
                SELECTION.remove();
            } else {
                SELECTION.set(previous);
            }
        }
    }

    /** 仅在当前进程实际有权限时使用的普通 Java 文件实现。 */
    private static final class DirectFileSystem implements GameFileSystem {

        @Override
        public Backend backend() {
            return Backend.DIRECT;
        }

        @Override
        public boolean exists(String path) {
            try {
                return new File(FsSupport.path(path)).exists();
            } catch (SecurityException e) {
                throw failure("检查文件", path, e);
            }
        }

        @Override
        public String[] list(String path) {
            // 直装版恢复历史「能访问」版本的普通 File 实现，不引入 NIO。
            File file = new File(FsSupport.path(path));
            try {
                String[] names = file.list();
                if (names != null) {
                    Arrays.sort(names);
                    return names;
                }
                if (file.isFile()) {
                    return null;
                }
                throw new StorageException("无法列举目录：" + path);
            } catch (SecurityException e) {
                throw failure("列目录", path, e);
            }
        }

        @Override
        public byte[] read(String path) {
            try {
                return FsSupport.localRead(new File(FsSupport.path(path)));
            } catch (IOException e) {
                throw failure("读取文件", path, e);
            } catch (SecurityException e) {
                throw failure("读取文件", path, e);
            }
        }

        @Override
        public void write(String path, byte[] data) {
            if (data == null) {
                throw new IllegalArgumentException("data 不能为空");
            }
            String p = FsSupport.path(path);
            mkdirs(FsSupport.parent(p));
            try {
                FsSupport.localWrite(new File(p), data);
            } catch (IOException e) {
                throw failure("写入文件", path, e);
            } catch (SecurityException e) {
                throw failure("写入文件", path, e);
            }
        }

        @Override
        public void mkdirs(String path) {
            File dir = new File(FsSupport.path(path));
            try {
                if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
                    throw new StorageException("无法创建目录：" + path);
                }
            } catch (SecurityException e) {
                throw failure("创建目录", path, e);
            }
        }

        @Override
        public void delete(String path) {
            String p = FsSupport.path(path);
            if ("/".equals(p)) {
                throw new StorageException("拒绝删除根目录");
            }
            try {
                deleteTree(new File(p), 0);
            } catch (IOException e) {
                throw failure("删除文件", path, e);
            } catch (SecurityException e) {
                throw failure("删除文件", path, e);
            }
        }

        private void deleteTree(File file, int depth) throws IOException {
            if (depth > 128) {
                throw new IOException("目录层级过深：" + file);
            }

            /*
             * 先尝试 unlink：普通文件及指向目录的符号链接可直接删除。
             * 只有未能直接删除的真实目录才递归，避免沿符号链接清空外部目录。
             */
            if (file.delete()) {
                return;
            }
            if (!file.exists()) {
                return;
            }
            if (!file.isDirectory()) {
                throw new IOException("无法删除：" + file);
            }

            File[] children = file.listFiles();
            if (children == null) {
                throw new IOException("无法列举待删除目录：" + file);
            }
            for (File child : children) {
                deleteTree(child, depth + 1);
            }
            if (!file.delete()) {
                throw new IOException("无法删除目录：" + file);
            }
        }

        @Override
        public void rename(String from, String to) {
            String a = FsSupport.path(from);
            String b = FsSupport.path(to);
            if ("/".equals(a) || "/".equals(b)) {
                throw new StorageException("拒绝重命名根目录");
            }
            if (a.equals(b)) {
                if (!exists(a)) {
                    throw new StorageException("源路径不存在：" + from);
                }
                return;
            }
            try {
                File source = new File(a);
                File target = new File(b);
                if (target.exists()) {
                    throw new StorageException("重命名目标已存在：" + to);
                }
                if (!source.renameTo(target)) {
                    throw new StorageException("重命名失败：" + from + " -> " + to);
                }
            } catch (SecurityException e) {
                throw failure("重命名", from + " -> " + to, e);
            }
        }

        @Override
        public String sha256(String path) {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                InputStream in = new FileInputStream(
                        new File(FsSupport.path(path)));
                try {
                    byte[] buffer = new byte[65536];
                    int n;
                    while ((n = in.read(buffer)) != -1) {
                        digest.update(buffer, 0, n);
                    }
                } finally {
                    in.close();
                }
                return FsSupport.hex(digest.digest());
            } catch (Exception e) {
                throw failure("计算 SHA-256", path, e);
            }
        }

        private StorageException failure(String operation, String path,
                                         Exception cause) {
            return new StorageException(
                    "DIRECT " + operation + "失败：" + path, cause);
        }
    }
}
