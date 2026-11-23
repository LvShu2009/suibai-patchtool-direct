package com.lvshu.build;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 二进制 AndroidManifest.xml（AXML）的最小侵入式修改。
 *
 * 只做一件事：给根节点 &lt;manifest&gt; 加上或改写 android:sharedUserId。
 * 不改写其它任何节点，也不重排已有字符串索引，保证原包的其余属性逐字保留。
 */
public final class Axml {

    private static final int RES_XML_TYPE = 0x0003;
    private static final int RES_STRING_POOL_TYPE = 0x0001;
    private static final int RES_XML_RESOURCE_MAP_TYPE = 0x0180;
    private static final int RES_XML_START_ELEMENT_TYPE = 0x0102;

    /** android.R.attr.sharedUserId = 16842763 = 0x0101000B */
    private static final int ATTR_SHARED_USER_ID = 0x0101000B;
    private static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";
    private static final String ATTR_NAME = "sharedUserId";

    private Axml() {
    }

    static final class Pool {
        boolean utf8;
        int stylesStart;
        int styleCount;
        final List<String> strings = new ArrayList<String>();
        final List<Integer> styleOffsets = new ArrayList<Integer>();
        byte[] styleData;
    }

    static int u16(byte[] b, int o) {
        return (b[o] & 0xff) | ((b[o + 1] & 0xff) << 8);
    }

    static int u32(byte[] b, int o) {
        return (b[o] & 0xff) | ((b[o + 1] & 0xff) << 8) | ((b[o + 2] & 0xff) << 16) | ((b[o + 3] & 0xff) << 24);
    }

    static void put16(byte[] b, int o, int v) {
        b[o] = (byte) v;
        b[o + 1] = (byte) (v >> 8);
    }

    static void put32(byte[] b, int o, int v) {
        b[o] = (byte) v;
        b[o + 1] = (byte) (v >> 8);
        b[o + 2] = (byte) (v >> 16);
        b[o + 3] = (byte) (v >> 24);
    }

    /** 解析字符串池（只支持 styleCount 为 0 或可原样保留的情况）。 */
    static Pool parsePool(byte[] b, int off) {
        Pool p = new Pool();
        int count = u32(b, off + 8);
        int styleCount = u32(b, off + 12);
        int flags = u32(b, off + 16);
        int stringsStart = u32(b, off + 20);
        p.stylesStart = u32(b, off + 24);
        p.styleCount = styleCount;
        p.utf8 = (flags & 0x100) != 0;
        for (int i = 0; i < count; i++) {
            int so = off + stringsStart + u32(b, off + 28 + i * 4);
            p.strings.add(readString(b, so, p.utf8));
        }
        for (int i = 0; i < styleCount; i++) {
            p.styleOffsets.add(u32(b, off + 28 + count * 4 + i * 4));
        }
        if (styleCount > 0) {
            int chunkSize = u32(b, off + 4);
            int start = off + p.stylesStart;
            int end = off + chunkSize;
            if (start > 0 && end > start && end <= b.length) {
                p.styleData = new byte[end - start];
                System.arraycopy(b, start, p.styleData, 0, p.styleData.length);
            }
        }
        return p;
    }

    private static String readString(byte[] b, int o, boolean utf8) {
        if (utf8) {
            int len = b[o++] & 0xff;
            if ((len & 0x80) != 0) {
                len = ((len & 0x7f) << 8) | (b[o++] & 0xff);
            }
            int bytes = b[o++] & 0xff;
            if ((bytes & 0x80) != 0) {
                bytes = ((bytes & 0x7f) << 8) | (b[o++] & 0xff);
            }
            return new String(b, o, bytes, java.nio.charset.StandardCharsets.UTF_8);
        }
        int len = u16(b, o) & 0xffff;
        o += 2;
        if ((len & 0x8000) != 0) {
            len = ((len & 0x7fff) << 16) | (u16(b, o) & 0xffff);
            o += 2;
        }
        char[] cs = new char[len];
        for (int i = 0; i < len; i++) {
            cs[i] = (char) u16(b, o + i * 2);
        }
        return new String(cs);
    }

    private static void emitString(ByteArrayOutputStream out, String s, boolean utf8) {
        if (utf8) {
            byte[] d = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            int chars = s.codePointCount(0, s.length());
            if (chars > 0x7f) {
                out.write(0x80 | (chars >> 8));
            }
            out.write(chars & 0xff);
            if (d.length > 0x7f) {
                out.write(0x80 | (d.length >> 8));
            }
            out.write(d.length & 0xff);
            out.write(d, 0, d.length);
            out.write(0);
        } else {
            int n = s.length();
            if (n > 0x7fff) {
                out.write((n >> 16) | 0x80);
                out.write(n & 0xff);
                out.write((n >> 8) & 0xff);
            } else {
                out.write(n & 0xff);
                out.write((n >> 8) & 0xff);
            }
            for (int i = 0; i < n; i++) {
                char c = s.charAt(i);
                out.write(c & 0xff);
                out.write((c >> 8) & 0xff);
            }
            out.write(0);
            out.write(0);
        }
    }

    static byte[] buildPool(Pool p) {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        int[] offsets = new int[p.strings.size()];
        for (int i = 0; i < p.strings.size(); i++) {
            offsets[i] = data.size();
            emitString(data, p.strings.get(i), p.utf8);
        }
        while ((data.size() & 3) != 0) {
            data.write(0);
        }
        int stringsStart = 28 + p.strings.size() * 4 + p.styleCount * 4;
        byte[] styleBytes = (p.styleCount > 0 && p.styleData != null) ? p.styleData : new byte[0];
        int size = stringsStart + data.size() + styleBytes.length;
        byte[] out = new byte[size];
        put16(out, 0, RES_STRING_POOL_TYPE);
        put16(out, 2, 28);
        put32(out, 4, size);
        put32(out, 8, p.strings.size());
        put32(out, 12, p.styleCount);
        put32(out, 16, (p.utf8 ? 0x100 : 0));
        put32(out, 20, stringsStart);
        put32(out, 24, styleBytes.length > 0 ? stringsStart + data.size() : 0);
        int o = 28;
        for (int i = 0; i < p.strings.size(); i++) {
            put32(out, o, offsets[i]);
            o += 4;
        }
        for (int i = 0; i < p.styleCount; i++) {
            put32(out, o, p.styleOffsets.get(i));
            o += 4;
        }
        byte[] d = data.toByteArray();
        System.arraycopy(d, 0, out, o, d.length);
        o += d.length;
        if (styleBytes.length > 0) {
            System.arraycopy(styleBytes, 0, out, o, styleBytes.length);
        }
        return out;
    }

    /**
     * 给根节点 manifest 写入 android:sharedUserId。
     *
     * @param in    原始 AXML
     * @param value 要写入的值，例如 "xx.yy.zz"
     * @return 修改后的 AXML（新数组，不改动入参）
     */
    public static byte[] setSharedUserId(byte[] in, String value) {
        if (in == null || in.length < 8) {
            throw new IllegalArgumentException("manifest 太小");
        }
        if (u16(in, 0) != RES_XML_TYPE) {
            throw new IllegalArgumentException("不是二进制 AndroidManifest.xml");
        }
        int total = u32(in, 4);

        int poolOff = -1;
        int poolSize = 0;
        int mapOff = -1;
        int mapSize = 0;
        int p = 8;
        while (p + 8 <= total) {
            int type = u16(in, p);
            int size = u32(in, p + 4);
            if (size <= 0) {
                break;
            }
            if (type == RES_STRING_POOL_TYPE && poolOff < 0) {
                poolOff = p;
                poolSize = size;
            } else if (type == RES_XML_RESOURCE_MAP_TYPE && mapOff < 0) {
                mapOff = p;
                mapSize = size;
            }
            p += size;
        }
        if (poolOff < 0) {
            throw new IllegalArgumentException("找不到字符串池");
        }

        Pool pool = parsePool(in, poolOff);
        String[] strings = pool.strings.toArray(new String[0]);
        int androidNs = -1;
        int uidName = -1;
        int uidValue = -1;
        for (int i = 0; i < strings.length; i++) {
            if (androidNs < 0 && ANDROID_NS.equals(strings[i])) {
                androidNs = i;
            } else if (uidName < 0 && ATTR_NAME.equals(strings[i])) {
                uidName = i;
            } else if (uidValue < 0 && value.equals(strings[i])) {
                uidValue = i;
            }
        }
        if (androidNs < 0) {
            throw new IllegalArgumentException("manifest 缺少 android 命名空间");
        }
        int extra = 0;
        if (uidName < 0) {
            uidName = pool.strings.size() + extra;
            pool.strings.add(ATTR_NAME);
            extra++;
        }
        if (uidValue < 0) {
            uidValue = pool.strings.size();
            pool.strings.add(value);
        }
        byte[] newPool = buildPool(pool);

        int[] resIds = new int[0];
        if (mapOff >= 0) {
            int n = (mapSize - 8) / 4;
            resIds = new int[n];
            for (int i = 0; i < n; i++) {
                resIds[i] = u32(in, mapOff + 8 + i * 4);
            }
        }
        int need = uidName + 1;
        if (resIds.length < need) {
            int[] bigger = new int[need];
            System.arraycopy(resIds, 0, bigger, 0, resIds.length);
            resIds = bigger;
        }
        resIds[uidName] = ATTR_SHARED_USER_ID;
        int mapBytes = 8 + resIds.length * 4;
        if ((mapBytes & 3) != 0) {
            mapBytes += 4 - (mapBytes & 3);
        }
        byte[] newMap = new byte[mapBytes];
        put16(newMap, 0, RES_XML_RESOURCE_MAP_TYPE);
        put16(newMap, 2, 8);
        put32(newMap, 4, mapBytes);
        for (int i = 0; i < resIds.length; i++) {
            put32(newMap, 8 + i * 4, resIds[i]);
        }

        // 定位 <manifest> 起始标签，在它的属性表末尾插入一条属性
        int afterMap = (mapOff >= 0) ? mapOff + mapSize : poolOff + poolSize;
        int manifestTag = -1;
        int manifestNameIdx = -1;
        p = afterMap;
        while (p + 16 <= total) {
            int type = u16(in, p);
            int size = u32(in, p + 4);
            if (size <= 0) {
                break;
            }
            if (type == RES_XML_START_ELEMENT_TYPE) {
                int nameIdx = u32(in, p + 20);
                if (manifestTag < 0) {
                    manifestTag = p;
                    manifestNameIdx = nameIdx;
                    break;
                }
            }
            p += size;
        }
        if (manifestTag < 0) {
            throw new IllegalArgumentException("找不到 <manifest> 节点");
        }
        if (manifestNameIdx < 0 || manifestNameIdx >= strings.length || !"manifest".equals(strings[manifestNameIdx])) {
            manifestNameIdx = -1;
            for (int i = 0; i < strings.length; i++) {
                if ("manifest".equals(strings[i])) {
                    manifestNameIdx = i;
                    break;
                }
            }
        }

        int headerSize = u16(in, manifestTag + 2);
        int tagSize = u32(in, manifestTag + 4);
        int attrStart = u16(in, manifestTag + headerSize + 8);
        int attrSize = u16(in, manifestTag + headerSize + 10);
        int attrCount = u16(in, manifestTag + headerSize + 12);
        int attrBase = manifestTag + headerSize + attrStart;
        if (attrSize != 20) {
            throw new IllegalStateException("不支持的属性尺寸 " + attrSize);
        }
        // 若已存在同名属性，直接改值
        for (int i = 0; i < attrCount; i++) {
            int a = attrBase + i * 20;
            if (u32(in, a + 4) == uidName && u32(in, a) == androidNs) {
                byte[] out0 = in.clone();
                put32(out0, a + 8, uidValue);
                put32(out0, a + 16, uidValue);
                return out0;
            }
        }

        // 组装：XML 头 + 新池 + 新 map + 其余 chunk（<manifest> 标签扩容 20 字节）
        ByteArrayOutputStream out = new ByteArrayOutputStream(in.length + 256);
        byte[] xmlHeader = new byte[8];
        System.arraycopy(in, 0, xmlHeader, 0, 8);
        out.write(xmlHeader, 0, 8);
        out.write(newPool, 0, newPool.length);
        out.write(newMap, 0, newMap.length);
        // map 之后到 <manifest> 之前还有 namespace 起始等 chunk，必须原样带上，
        // 否则属性会丢失 android: 命名空间（sharedUserId 就白写了）。
        out.write(in, afterMap, manifestTag - afterMap);

        // 插到属性表最前面（第 1 个属性）：真机上发现写在末尾时系统可能读不到
        // sharedUserId，放到最前与能正常工作的旧包结构一致。
        int insertAt = attrBase;
        int newTagSize = tagSize + 20;
        byte[] tag = new byte[newTagSize];
        // 先原样复制 <manifest> 标签头（不含属性区）
        System.arraycopy(in, manifestTag, tag, 0, insertAt - manifestTag);
        put32(tag, 4, newTagSize);
        put16(tag, headerSize + 12, attrCount + 1);
        int a = insertAt - manifestTag;
        put32(tag, a, androidNs);          // ns
        put32(tag, a + 4, uidName);        // name
        put32(tag, a + 8, uidValue);       // raw
        put16(tag, a + 12, 8);             // typedValue.size
        tag[a + 14] = 0;                   // res0
        tag[a + 15] = 3;                   // TYPE_STRING
        put32(tag, a + 16, uidValue);      // typedValue.data
        // 原有属性整体后移 20 字节，新的 sharedUserId 占据第 1 位
        System.arraycopy(in, insertAt, tag, a + 20, manifestTag + tagSize - insertAt);
        out.write(tag, 0, tag.length);

        int rest = manifestTag + tagSize;
        out.write(in, rest, total - rest);

        byte[] result = out.toByteArray();
        put32(result, 4, result.length);
        return result;
    }

    /**
     * 中和 manifest 根节点里会阻止共享 UID 的属性（原地改 typedValue.data，不动字符串池）：
     * - android:targetSandboxVersion="2"  → data 改为 1（sandbox 1 允许 UID 共享）
     * - android:sharedUserMaxSdkVersion=N → data 改为 99（高于任何现有 Android SDK，使 sharedUserId 继续生效）
     *
     * 游戏原包若带这两个属性，即使我们写入了正确的 sharedUserId、签名也一致，
     * Android 13+ / sandbox2 仍会忽略共享 UID、分配独立 UID，导致读不到游戏目录。
     * 只改数值、不改长度与索引，天然兼容所有包。
     */
    public static byte[] neutralizeUidBlockers(byte[] in) {
        if (in == null || in.length < 8 || u16(in, 0) != RES_XML_TYPE) {
            return in;
        }
        int total = u32(in, 4);
        int poolOff = -1;
        int p = 8;
        while (p + 8 <= total) {
            int type = u16(in, p);
            int size = u32(in, p + 4);
            if (size <= 0) {
                break;
            }
            if (type == RES_STRING_POOL_TYPE && poolOff < 0) {
                poolOff = p;
            }
            p += size;
        }
        if (poolOff < 0) {
            return in;
        }
        Pool pool = parsePool(in, poolOff);
        int tsIdx = -1, maxSdkIdx = -1, andNs = -1;
        for (int i = 0; i < pool.strings.size(); i++) {
            String s = pool.strings.get(i);
            if ("targetSandboxVersion".equals(s)) {
                tsIdx = i;
            } else if ("sharedUserMaxSdkVersion".equals(s)) {
                maxSdkIdx = i;
            } else if (ANDROID_NS.equals(s)) {
                andNs = i;
            }
        }
        if (tsIdx < 0 && maxSdkIdx < 0) {
            return in;
        }
        int manifestTag = -1;
        p = 8;
        while (p + 16 <= total) {
            int type = u16(in, p);
            int size = u32(in, p + 4);
            if (size <= 0) {
                break;
            }
            if (type == RES_XML_START_ELEMENT_TYPE) {
                manifestTag = p;
                break;
            }
            p += size;
        }
        if (manifestTag < 0) {
            return in;
        }
        int headerSize = u16(in, manifestTag + 2);
        int attrStart = u16(in, manifestTag + headerSize + 8);
        int attrSize = u16(in, manifestTag + headerSize + 10);
        int attrCount = u16(in, manifestTag + headerSize + 12);
        if (attrSize != 20) {
            return in;
        }
        int attrBase = manifestTag + headerSize + attrStart;
        byte[] out = in.clone();
        for (int i = 0; i < attrCount; i++) {
            int a = attrBase + i * 20;
            int ns = u32(in, a);
            int name = u32(in, a + 4);
            if (andNs >= 0 && ns != andNs) {
                continue;
            }
            if (name == tsIdx) {
                put32(out, a + 16, 1);   // targetSandboxVersion -> 1
            } else if (name == maxSdkIdx) {
                put32(out, a + 16, 99);  // sharedUserMaxSdkVersion -> 99
            }
        }
        return out;
    }

    /** 读出 manifest 里现有的 sharedUserId（不存在返回 null），便于界面展示。 */
    public static String getSharedUserId(byte[] in) {
        try {
            int total = u32(in, 4);
            int p = 8;
            int poolOff = -1;
            int mapOff = -1;
            int mapSize = 0;
            while (p + 8 <= total) {
                int type = u16(in, p);
                int size = u32(in, p + 4);
                if (size <= 0) {
                    break;
                }
                if (type == RES_STRING_POOL_TYPE && poolOff < 0) {
                    poolOff = p;
                }
                if (type == RES_XML_RESOURCE_MAP_TYPE) {
                    mapOff = p;
                    mapSize = size;
                }
                p += size;
            }
            if (poolOff < 0) {
                return null;
            }
            Pool pool = parsePool(in, poolOff);
            int after = (mapOff >= 0) ? mapOff + mapSize : total;
            p = after;
            while (p + 16 <= total) {
                int type = u16(in, p);
                int size = u32(in, p + 4);
                if (size <= 0) {
                    break;
                }
                if (type == RES_XML_START_ELEMENT_TYPE) {
                    int headerSize = u16(in, p + 2);
                    int attrStart = u16(in, p + headerSize + 8);
                    int attrSize = u16(in, p + headerSize + 10);
                    int attrCount = u16(in, p + headerSize + 12);
                    for (int i = 0; i < attrCount; i++) {
                        int a = p + headerSize + attrStart + i * attrSize;
                        if (ATTR_NAME.equals(pool.strings.get(u32(in, a + 4)))) {
                            int data = u32(in, a + 16);
                            if (data >= 0 && data < pool.strings.size()) {
                                return pool.strings.get(data);
                            }
                        }
                    }
                    return null;
                }
                p += size;
            }
        } catch (RuntimeException ignored) {
        }
        return null;
    }
}