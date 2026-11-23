package com.lvshu.build;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 往二进制 AndroidManifest.xml 里整段插入元素。
 *
 * 目前只做一件事：给 <application> 里最末尾插一个 DocumentsProvider
 * （MT 管理器「注入文件提供器」的同一思路），让别的 App 能通过 SAF 读写这个包自己的
 * data 目录，不需要共享 UID，也不需要 root。
 */
public final class AxmlInject {

    private static final int RES_STRING_POOL_TYPE = 0x0001;
    private static final int RES_XML_START_ELEMENT_TYPE = 0x0102;
    private static final int RES_XML_END_ELEMENT_TYPE = 0x0103;
    private static final int RES_XML_RESOURCE_MAP_TYPE = 0x0180;

    private static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";

    /** android.R.attr.* 的资源 id，AXML 的属性名要靠 ResourceMap 对上它。 */
    private static final int ATTR_NAME = 0x01010003;
    private static final int ATTR_PERMISSION = 0x01010006;
    private static final int ATTR_EXPORTED = 0x01010010;
    private static final int ATTR_AUTHORITIES = 0x01010018;
    private static final int ATTR_GRANT_URI_PERMISSIONS = 0x0101001b;

    private static final int TYPE_STRING = 0x03;
    private static final int TYPE_INT_BOOLEAN = 0x12;
    private static final int NO_ENTRY = 0xFFFFFFFF;

    private AxmlInject() {
    }

    /**
     * 注入 DocumentsProvider。
     *
     * @param in             原始 manifest
     * @param providerClass  提供器类名（必须已经在 dex 里）
     * @param authority      content authority，建议 <包名>.lvshubridge
     * @param permission     访问它需要的系统权限，null 表示不设（不安全）
     */
    public static byte[] injectDocumentsProvider(byte[] in, String providerClass,
                                                 String authority, String permission) {
        int total = Axml.u32(in, 4);
        int poolOff = -1;
        int poolSize = 0;
        int p = 8;
        while (p + 8 <= total) {
            int type = Axml.u16(in, p);
            int size = Axml.u32(in, p + 4);
            if (size <= 0) {
                break;
            }
            if (type == RES_STRING_POOL_TYPE && poolOff < 0) {
                poolOff = p;
                poolSize = size;
            }
            p += size;
        }
        if (poolOff < 0) {
            throw new IllegalArgumentException("manifest 没有字符串池");
        }
        int mapOff = -1;
        int mapSize = 0;
        p = poolOff + poolSize;
        while (p + 8 <= total) {
            int type = Axml.u16(in, p);
            int size = Axml.u32(in, p + 4);
            if (size <= 0) {
                break;
            }
            if (type == RES_XML_RESOURCE_MAP_TYPE) {
                mapOff = p;
                mapSize = size;
                break;
            }
            if (type != RES_STRING_POOL_TYPE) {
                break;
            }
            p += size;
        }
        int afterMap = mapOff >= 0 ? mapOff + mapSize : poolOff + poolSize;

        Axml.Pool pool = Axml.parsePool(in, poolOff);
        List<String> strings = pool.strings;

        int androidNs = indexOf(strings, ANDROID_NS);
        if (androidNs < 0) {
            throw new IllegalArgumentException("manifest 缺少 android 命名空间");
        }

        // ---- 需要用的字符串先全部登记，顺便记录属性名的资源 id ----
        List<int[]> resIdFix = new ArrayList<int[]>();

        int idxProvider = name(strings, "provider", resIdFix, 0);
        int idxIntentFilter = name(strings, "intent-filter", resIdFix, 0);
        int idxAction = name(strings, "action", resIdFix, 0);
        int idxClass = value(strings, providerClass);
        int idxAuthority = value(strings, authority);
        int idxActionName = value(strings, "android.content.action.DOCUMENTS_PROVIDER");
        int idxTrue = value(strings, "true");

        int aName = name(strings, "name", resIdFix, ATTR_NAME);
        int aExport = name(strings, "exported", resIdFix, ATTR_EXPORTED);
        int aGrant = name(strings, "grantUriPermissions", resIdFix, ATTR_GRANT_URI_PERMISSIONS);
        int aAuth = name(strings, "authorities", resIdFix, ATTR_AUTHORITIES);
        int aPerm = name(strings, "permission", resIdFix, ATTR_PERMISSION);

        // ---- 找 </application> 的位置 ----
        int insertAt = findApplicationEnd(in, afterMap, total, strings);
        if (insertAt < 0) {
            throw new IllegalArgumentException("manifest 里找不到 <application>");
        }

        // ---- 拼元素 ----
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        List<int[]> attrs = new ArrayList<int[]>();
        attrs.add(strAttr(androidNs, aName, idxClass));
        attrs.add(strAttr(androidNs, aAuth, idxAuthority));
        attrs.add(boolAttr(androidNs, aExport, true, idxTrue));
        attrs.add(boolAttr(androidNs, aGrant, true, idxTrue));
        if (permission != null && permission.length() > 0) {
            attrs.add(strAttr(androidNs, aPerm, value(strings, permission)));
        }
        writeElement(body, NO_ENTRY, idxProvider, attrs, false);

        List<int[]> filterAttrs = new ArrayList<int[]>();
        writeElement(body, NO_ENTRY, idxIntentFilter, filterAttrs, false);
        List<int[]> actionAttrs = new ArrayList<int[]>();
        actionAttrs.add(strAttr(androidNs, aName, idxActionName));
        writeElement(body, NO_ENTRY, idxAction, actionAttrs, false);
        writeEnd(body, NO_ENTRY, idxAction);
        writeEnd(body, NO_ENTRY, idxIntentFilter);
        writeEnd(body, NO_ENTRY, idxProvider);
        byte[] insert = body.toByteArray();

        // ---- 重建字符串池 / 资源映射 ----
        byte[] newPool = Axml.buildPool(pool);
        int maxIdx = strings.size();
        for (int[] f : resIdFix) {
            if (f[1] + 1 > maxIdx) {
                maxIdx = f[1] + 1;
            }
        }
        int[] resIds = new int[maxIdx];
        if (mapOff >= 0) {
            int n = (mapSize - 8) / 4;
            for (int i = 0; i < n && i < resIds.length; i++) {
                resIds[i] = Axml.u32(in, mapOff + 8 + i * 4);
            }
        }
        for (int[] f : resIdFix) {
            resIds[f[1]] = f[0] != 0 ? f[0] : resIds[f[1]];
        }
        int mapBytes = 8 + resIds.length * 4;
        byte[] newMap = new byte[mapBytes];
        Axml.put16(newMap, 0, RES_XML_RESOURCE_MAP_TYPE);
        Axml.put16(newMap, 2, 8);
        Axml.put32(newMap, 4, mapBytes);
        for (int i = 0; i < resIds.length; i++) {
            Axml.put32(newMap, 8 + i * 4, resIds[i]);
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream(total + newPool.length + insert.length + 512);
        out.write(in, 0, 8);
        out.write(newPool, 0, newPool.length);
        out.write(newMap, 0, newMap.length);
        out.write(in, afterMap, insertAt - afterMap);
        out.write(insert, 0, insert.length);
        out.write(in, insertAt, total - insertAt);
        byte[] result = out.toByteArray();
        Axml.put32(result, 4, result.length);
        return result;
    }

    // ---------------- 辅助 ----------------

    private static int indexOf(List<String> list, String s) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).equals(s)) {
                return i;
            }
        }
        return -1;
    }

    private static int value(List<String> strings, String s) {
        int i = indexOf(strings, s);
        if (i >= 0) {
            return i;
        }
        strings.add(s);
        return strings.size() - 1;
    }

    /** 属性名：登记字符串，并记下它对应的 android.R.attr 资源 id。 */
    private static int name(List<String> strings, String s, List<int[]> fix, int resId) {
        int i = indexOf(strings, s);
        if (i < 0) {
            strings.add(s);
            i = strings.size() - 1;
        }
        if (resId != 0) {
            fix.add(new int[]{resId, i});
        }
        return i;
    }

    private static int[] strAttr(int ns, int name, int valueIdx) {
        return new int[]{ns, name, valueIdx, TYPE_STRING, valueIdx};
    }

    private static int[] boolAttr(int ns, int name, boolean v, int rawIdx) {
        return new int[]{ns, name, v ? 0xFFFFFFFF : 0, TYPE_INT_BOOLEAN, v ? 0xFFFFFFFF : 0};
    }

    private static void writeElement(ByteArrayOutputStream out, int ns, int nameIdx,
                                     List<int[]> attrs, boolean ignored) {
        int n = attrs.size();
        int size = 16 + 20 + 20 * n;
        byte[] b = new byte[size];
        Axml.put16(b, 0, RES_XML_START_ELEMENT_TYPE);
        Axml.put16(b, 2, 16);
        Axml.put32(b, 4, size);
        Axml.put32(b, 8, 1);
        Axml.put32(b, 12, NO_ENTRY);
        Axml.put32(b, 16, ns);
        Axml.put32(b, 20, nameIdx);
        Axml.put16(b, 24, 20);
        Axml.put16(b, 26, 20);
        Axml.put16(b, 28, n);
        Axml.put16(b, 30, 0);
        Axml.put16(b, 32, 0);
        Axml.put16(b, 34, 0);
        int o = 36;
        for (int[] a : attrs) {
            Axml.put32(b, o, a[0]);
            Axml.put32(b, o + 4, a[1]);
            Axml.put32(b, o + 8, a[2]);
            Axml.put16(b, o + 12, 8);
            b[o + 14] = 0;
            b[o + 15] = (byte) a[3];
            Axml.put32(b, o + 16, a[4]);
            o += 20;
        }
        out.write(b, 0, b.length);
    }

    private static void writeEnd(ByteArrayOutputStream out, int ns, int nameIdx) {
        byte[] b = new byte[24];
        Axml.put16(b, 0, RES_XML_END_ELEMENT_TYPE);
        Axml.put16(b, 2, 16);
        Axml.put32(b, 4, 24);
        Axml.put32(b, 8, 1);
        Axml.put32(b, 12, NO_ENTRY);
        Axml.put32(b, 16, ns);
        Axml.put32(b, 20, nameIdx);
        out.write(b, 0, b.length);
    }

    /** 返回 </application> 那个 chunk 的起始偏移。 */
    private static int findApplicationEnd(byte[] in, int from, int total, List<String> strings) {
        int p = from;
        int depth = 0;
        boolean inApp = false;
        while (p + 8 <= total) {
            int type = Axml.u16(in, p);
            int size = Axml.u32(in, p + 4);
            if (size <= 0) {
                break;
            }
            if (type == RES_XML_START_ELEMENT_TYPE) {
                int nameIdx = Axml.u32(in, p + 20);
                String name = (nameIdx >= 0 && nameIdx < strings.size()) ? strings.get(nameIdx) : "";
                if (!inApp && "application".equals(name)) {
                    inApp = true;
                    depth = 1;
                } else if (inApp) {
                    depth++;
                }
            } else if (type == RES_XML_END_ELEMENT_TYPE && inApp) {
                depth--;
                if (depth == 0) {
                    return p;
                }
            }
            p += size;
        }
        return -1;
    }
}