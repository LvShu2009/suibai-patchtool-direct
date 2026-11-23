package com.lvshu.build;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.util.UUID;

/**
 * 纯本地文件/路径工具类。
 *
 * 原先定义在 RootFileSystem.java 内，但本身与 root/Shizuku 无关；
 * 直装版移除 root 通道后，把它独立成文件，供 DirectFileSystem 与
 * 备份/索引等本地 I/O 继续复用（访问链：同 UID 普通 java.io.File 直读）。
 */
final class FsSupport {

    static final Charset UTF8 = Charset.forName("UTF-8");

    private FsSupport() {
    }

    static String path(String raw) {
        if (raw == null || !raw.startsWith("/") || raw.indexOf('\0') >= 0) {
            throw new GameFileSystem.StorageException("必须使用有效的绝对路径：" + raw);
        }
        String[] parts = raw.split("/+");
        StringBuilder result = new StringBuilder();
        for (String part : parts) {
            if (part.length() == 0) {
                continue;
            }
            if (".".equals(part) || "..".equals(part)) {
                throw new GameFileSystem.StorageException("路径不能包含 . 或 ..：" + raw);
            }
            result.append('/').append(part);
        }
        return result.length() == 0 ? "/" : result.toString();
    }

    static String parent(String path) {
        String p = path(path);
        int slash = p.lastIndexOf('/');
        return slash <= 0 ? "/" : p.substring(0, slash);
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[65536];
        int n;
        while ((n = in.read(buffer)) != -1) {
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    static byte[] localRead(File file) throws IOException {
        InputStream in = new FileInputStream(file);
        try {
            return readAll(in);
        } finally {
            in.close();
        }
    }

    static void localWrite(File file, byte[] data) throws IOException {
        OutputStream out = new FileOutputStream(file);
        try {
            out.write(data);
            out.flush();
        } finally {
            out.close();
        }
    }

    static String localError(File file) {
        try {
            InputStream in = new FileInputStream(file);
            try {
                byte[] data = new byte[8192];
                int offset = 0;
                while (offset < data.length) {
                    int n = in.read(data, offset, data.length - offset);
                    if (n == -1) {
                        break;
                    }
                    offset += n;
                }
                return new String(data, 0, offset, UTF8).trim();
            } finally {
                in.close();
            }
        } catch (IOException e) {
            return "";
        }
    }

    static String hash(byte[] data) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new GameFileSystem.StorageException("SHA-256 不可用", e);
        }
    }

    static String hex(byte[] data) {
        char[] digits = "0123456789abcdef".toCharArray();
        char[] result = new char[data.length * 2];
        for (int i = 0; i < data.length; i++) {
            int value = data[i] & 255;
            result[i * 2] = digits[value >>> 4];
            result[i * 2 + 1] = digits[value & 15];
        }
        return new String(result);
    }

    static String uniqueName() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    static void removeLocal(File file) {
        if (file != null) {
            try {
                file.delete();
            } catch (SecurityException ignored) {
            }
        }
    }

    static void close(java.io.Closeable stream) {
        if (stream != null) {
            try {
                stream.close();
            } catch (IOException ignored) {
            }
        }
    }
}
