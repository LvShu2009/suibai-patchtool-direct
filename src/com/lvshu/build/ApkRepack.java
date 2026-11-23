package com.lvshu.build;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * APK 级别的 zip 重建：
 * 逐条“原样搬运”压缩数据（不重新压缩 400MB 资源），只替换/追加指定的条目，
 * 同时按 zipalign 规则给未压缩条目补对齐填充，最后重写中央目录。
 */
public final class ApkRepack {

    public interface Progress {
        void onProgress(int percent, String message);
    }

    private static final int EOCD_SIG = 0x06054b50;
    private static final int CD_SIG = 0x02014b50;
    private static final int LFH_SIG = 0x04034b50;

    private static final class Entry {
        int versionMade, versionNeeded, flags, method, time, date;
        long crc, compSize, uncompSize, localOffset;
        int internalAttr, externalAttr;
        String name;
        byte[] extra;
        byte[] comment;
    }

    private ApkRepack() {
    }

    /** 精确统计已写字节数的输出流。 */
    private static final class Counting extends java.io.FilterOutputStream {
        long count;

        Counting(java.io.OutputStream o) {
            super(o);
        }

        @Override
        public void write(int b) throws IOException {
            out.write(b);
            count++;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
            count += len;
        }

        @Override
        public void flush() throws IOException {
            out.flush();
        }
    }

    private static int u16(byte[] b, int o) {
        return (b[o] & 0xff) | ((b[o + 1] & 0xff) << 8);
    }

    private static long u32(byte[] b, int o) {
        return (b[o] & 0xffL) | ((b[o + 1] & 0xffL) << 8) | ((b[o + 2] & 0xffL) << 16) | ((b[o + 3] & 0xffL) << 24);
    }

    private static void put16(byte[] b, int o, int v) {
        b[o] = (byte) v;
        b[o + 1] = (byte) (v >> 8);
    }

    private static void put32(byte[] b, int o, long v) {
        b[o] = (byte) v;
        b[o + 1] = (byte) (v >> 8);
        b[o + 2] = (byte) (v >> 16);
        b[o + 3] = (byte) (v >> 24);
    }

    public static boolean isSignatureEntry(String name) {
        String n = name.toUpperCase(java.util.Locale.ROOT);
        if (!n.startsWith("META-INF/")) {
            return false;
        }
        return n.endsWith(".SF") || n.endsWith(".RSA") || n.endsWith(".DSA") || n.endsWith(".EC")
                || n.equals("META-INF/MANIFEST.MF");
    }

    /**
     * 重建 APK。
     *
     * @param src            原始 APK
     * @param dst            输出 APK（可与 src 相同，内部先写临时文件）
     * @param replace        需要替换/新增的条目名 → 新内容（manifest、补丁资源）
     * @param progress       进度回调，可为 null
     */
    public static void repack(File src, File dst, Map<String, byte[]> replace, Progress progress)
            throws IOException {
        RandomAccessFile raf = new RandomAccessFile(src, "r");
        try {
            long len = raf.length();
            int tail = (int) Math.min(len, 65557 + 22);
            byte[] buf = new byte[tail];
            raf.seek(len - tail);
            raf.readFully(buf);
            int eocd = -1;
            for (int i = tail - 22; i >= 0; i--) {
                if (u32(buf, i) == EOCD_SIG) {
                    eocd = i;
                    break;
                }
            }
            if (eocd < 0) {
                throw new IOException("找不到 zip 尾部记录，不是有效 APK");
            }
            long eocdAbs = len - tail + eocd;
            int totalEntries = u16(buf, eocd + 10);
            long cdSize = u32(buf, eocd + 12);
            long cdOffset = u32(buf, eocd + 16);
            if (totalEntries == 0xffff || cdOffset == 0xffffffffL) {
                throw new IOException("暂不支持 zip64 包");
            }

            byte[] cd = new byte[(int) cdSize];
            raf.seek(cdOffset);
            raf.readFully(cd);
            List<Entry> entries = new ArrayList<Entry>(totalEntries);
            int p = 0;
            for (int i = 0; i < totalEntries; i++) {
                if (u32(cd, p) != CD_SIG) {
                    throw new IOException("中央目录损坏 @" + p);
                }
                Entry e = new Entry();
                e.versionMade = u16(cd, p + 4);
                e.versionNeeded = u16(cd, p + 6);
                e.flags = u16(cd, p + 8);
                e.method = u16(cd, p + 10);
                e.time = u16(cd, p + 12);
                e.date = u16(cd, p + 14);
                e.crc = u32(cd, p + 16);
                e.compSize = u32(cd, p + 20);
                e.uncompSize = u32(cd, p + 24);
                int nameLen = u16(cd, p + 28);
                int extraLen = u16(cd, p + 30);
                int commentLen = u16(cd, p + 32);
                e.internalAttr = u16(cd, p + 36);
                e.externalAttr = (int) u32(cd, p + 38);
                e.localOffset = u32(cd, p + 42);
                e.name = new String(cd, p + 46, nameLen, java.nio.charset.StandardCharsets.UTF_8);
                e.extra = new byte[extraLen];
                System.arraycopy(cd, p + 46 + nameLen, e.extra, 0, extraLen);
                e.comment = new byte[commentLen];
                System.arraycopy(cd, p + 46 + nameLen + extraLen, e.comment, 0, commentLen);
                entries.add(e);
                p += 46 + nameLen + extraLen + commentLen;
            }

            Map<String, byte[]> pending = new LinkedHashMap<String, byte[]>(replace);
            List<Entry> outEntries = new ArrayList<Entry>(entries.size() + pending.size());
            for (Entry e : entries) {
                if (pending.containsKey(e.name)) {
                    continue;
                }
                if (isSignatureEntry(e.name)) {
                    continue;
                }
                outEntries.add(e);
            }

            File tmp = new File(dst.getAbsolutePath() + ".tmp");
            Counting out = new Counting(new BufferedOutputStream(new FileOutputStream(tmp), 1 << 16));
            try {
                byte[] copyBuf = new byte[1 << 16];
                int done = 0;
                for (Entry e : outEntries) {
                    // 本地头里的 name/extra 长度可能与中央目录不同，以本地头为准
                    byte[] lfh = new byte[30];
                    raf.seek(e.localOffset);
                    raf.readFully(lfh);
                    if (u32(lfh, 0) != LFH_SIG) {
                        throw new IOException("本地文件头损坏: " + e.name);
                    }
                    int lNameLen = u16(lfh, 26);
                    int lExtraLen = u16(lfh, 28);
                    long dataOffset = e.localOffset + 30 + lNameLen + lExtraLen;

                    byte[] nameBytes = e.name.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    long newOffset = out.count;
                    byte[] newExtra = alignExtra(e, nameBytes.length, newOffset);

                    // 中央目录与本地头共用同一份对齐 extra，避免两处长度/内容不一致
                    e.extra = newExtra;
                    byte[] newLfh = new byte[30];
                    System.arraycopy(lfh, 0, newLfh, 0, 30);
                    put16(newLfh, 6, u16(lfh, 6) & ~0x08);   // 去掉数据描述符标志
                    // 关键：清掉 bit 3 后数据描述符被丢弃，必须把中央目录里的真实 CRC
                    // 回填到本地头偏移 14。否则原本依赖描述符的条目本地头 CRC=0，
                    // 标准解压工具用中央目录 CRC 能过，但按本地头校验的读取器
                    // （游戏引擎 mmap 直读 assets）会判定资源损坏而拒绝加载。
                    put32(newLfh, 14, e.crc);
                    put16(newLfh, 26, nameBytes.length);
                    put16(newLfh, 28, newExtra.length);
                    put32(newLfh, 18, e.compSize);
                    put32(newLfh, 22, e.uncompSize);
                    out.write(newLfh, 0, 30);
                    out.write(nameBytes, 0, nameBytes.length);
                    out.write(newExtra, 0, newExtra.length);

                    raf.seek(dataOffset);
                    long remain = e.compSize;
                    while (remain > 0) {
                        int n = (int) Math.min(copyBuf.length, remain);
                        raf.readFully(copyBuf, 0, n);
                        out.write(copyBuf, 0, n);
                        remain -= n;
                    }
                    e.localOffset = newOffset;
                    done++;
                    if (progress != null && (done & 63) == 0) {
                        progress.onProgress(5 + (int) (done * 60L / outEntries.size()), "搬移 " + e.name);
                    }
                }

                // 替换/新增条目
                int idx = 0;
                for (Map.Entry<String, byte[]> pe : pending.entrySet()) {
                    Entry e = writeNewEntry(out, pe.getKey(), pe.getValue(), 0);
                    outEntries.add(e);
                    idx++;
                    if (progress != null) {
                        progress.onProgress(65 + (int) (idx * 15L / Math.max(1, pending.size())), "写入 " + pe.getKey());
                    }
                }

                // 中央目录
                long cdStart = out.count;
                for (Entry e : outEntries) {
                    byte[] nameBytes = e.name.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    byte[] rec = new byte[46 + nameBytes.length + e.extra.length + e.comment.length];
                    put32(rec, 0, CD_SIG);
                    put16(rec, 4, e.versionMade);
                    put16(rec, 6, e.versionNeeded);
                    put16(rec, 8, e.flags & ~0x08);
                    put16(rec, 10, e.method);
                    put16(rec, 12, e.time);
                    put16(rec, 14, e.date);
                    put32(rec, 16, e.crc);
                    put32(rec, 20, e.compSize);
                    put32(rec, 24, e.uncompSize);
                    put16(rec, 28, nameBytes.length);
                    put16(rec, 30, e.extra.length);
                    put16(rec, 32, e.comment.length);
                    put16(rec, 34, 0);
                    put16(rec, 36, e.internalAttr);
                    put32(rec, 38, e.externalAttr);
                    put32(rec, 42, e.localOffset);
                    System.arraycopy(nameBytes, 0, rec, 46, nameBytes.length);
                    System.arraycopy(e.extra, 0, rec, 46 + nameBytes.length, e.extra.length);
                    System.arraycopy(e.comment, 0, rec, 46 + nameBytes.length + e.extra.length, e.comment.length);
                    out.write(rec, 0, rec.length);
                }
                long cdBytes = out.count - cdStart;

                byte[] end = new byte[22];
                put32(end, 0, EOCD_SIG);
                put16(end, 8, outEntries.size());
                put16(end, 10, outEntries.size());
                put32(end, 12, cdBytes);
                put32(end, 16, cdStart);
                out.write(end, 0, 22);
                if (progress != null) {
                    progress.onProgress(82, "重建中央目录");
                }
            } finally {
                out.close();
            }
            if (dst.exists()) {
                dst.delete();
            }
            if (!tmp.renameTo(dst)) {
                throw new IOException("无法写出 " + dst);
            }
        } finally {
            raf.close();
        }
    }

    /** zipalign：未压缩条目按 4 字节对齐，.so 按 4096 对齐。 */
    private static byte[] alignExtra(Entry e, int nameLen, long offset) {
        int align = 4;
        if (e.method == 0) {
            String n = e.name.toLowerCase(java.util.Locale.ROOT);
            if (n.endsWith(".so")) {
                align = 4096;
            }
        }
        if (align <= 4 && e.method != 0) {
            return e.extra;
        }
        long base = offset + 30 + nameLen + e.extra.length;
        int pad = (int) ((align - (base % align)) % align);
        if (pad == 0) {
            return e.extra;
        }
        while (pad < 4) {
            pad += align;
        }
        byte[] extra = new byte[e.extra.length + pad];
        System.arraycopy(e.extra, 0, extra, 0, e.extra.length);
        put16(extra, e.extra.length, 0xD935);
        put16(extra, e.extra.length + 2, pad - 4);
        return extra;
    }

    /** 写入一条全新条目（deflate），返回它的中央目录记录。 */
    private static Entry writeNewEntry(Counting out, String name, byte[] data, int mode) throws IOException {
        byte[] nameBytes = name.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        CRC32 crc = new CRC32();
        crc.update(data, 0, data.length);
        Deflater deflater = new Deflater(6, true);
        deflater.setInput(data);
        deflater.finish();
        byte[] comp = new byte[data.length + 1024];
        int compLen = 0;
        while (!deflater.finished()) {
            if (compLen + 4096 > comp.length) {
                byte[] bigger = new byte[comp.length * 2];
                System.arraycopy(comp, 0, bigger, 0, compLen);
                comp = bigger;
            }
            compLen += deflater.deflate(comp, compLen, comp.length - compLen);
        }
        deflater.end();

        boolean store = compLen >= data.length;
        byte[] body = store ? data : java.util.Arrays.copyOf(comp, compLen);
        int method = store ? 0 : 8;

        int align = name.toLowerCase(java.util.Locale.ROOT).endsWith(".so") && store ? 4096 : 4;
        long offset = out.count;
        long base = offset + 30 + nameBytes.length;
        int pad = (int) ((align - (base % align)) % align);
        while (pad > 0 && pad < 4) {
            pad += align;
        }
        byte[] extra = new byte[pad];
        if (pad > 0) {
            put16(extra, 0, 0xD935);
            put16(extra, 2, pad - 4);
        }

        Entry e = new Entry();
        e.name = name;
        e.method = method;
        e.flags = 0;
        e.crc = crc.getValue();
        e.compSize = body.length;
        e.uncompSize = data.length;
        e.extra = extra;
        e.comment = new byte[0];
        e.versionMade = 20;
        e.versionNeeded = 20;
        e.localOffset = offset;
        e.time = 0x21;
        e.date = 0x5021;
        e.externalAttr = 0;
        e.internalAttr = 0;

        byte[] lfh = new byte[30];
        put32(lfh, 0, LFH_SIG);
        put16(lfh, 4, 20);
        put16(lfh, 6, e.flags);
        put16(lfh, 8, e.method);
        put16(lfh, 10, e.time);
        put16(lfh, 12, e.date);
        put32(lfh, 14, e.crc);
        put32(lfh, 18, e.compSize);
        put32(lfh, 22, e.uncompSize);
        put16(lfh, 26, nameBytes.length);
        put16(lfh, 28, extra.length);
        out.write(lfh, 0, 30);
        out.write(nameBytes, 0, nameBytes.length);
        out.write(extra, 0, extra.length);
        out.write(body, 0, body.length);
        return e;
    }
}