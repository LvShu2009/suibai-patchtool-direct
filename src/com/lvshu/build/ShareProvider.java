package com.lvshu.build;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * 极简 FileProvider：把本地文件暴露成 content:// 供系统安装器 / 其他应用读取。
 * URI 形如 content://com.downm.fixing.direct.files/<urlencode(绝对路径)>
 */
public class ShareProvider extends ContentProvider {

    public static final String AUTHORITY = "com.downm.fixing.direct.files";

    public static Uri uriFor(File file) {
        return Uri.parse("content://" + AUTHORITY + "/" + Uri.encode(file.getAbsolutePath()));
    }

    private static File resolve(Uri uri) {
        String raw = uri.getPath();
        if (raw == null || raw.length() < 2) {
            return null;
        }
        File f = new File(Uri.decode(raw.substring(1)));
        String path = f.getAbsolutePath();
        if (!path.startsWith("/storage/emulated/0/") && !path.startsWith("/storage/emulated/0")) {
            return null;
        }
        return f;
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File f = resolve(uri);
        if (f == null || !f.isFile()) {
            throw new FileNotFoundException("不允许访问：" + uri);
        }
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        File f = resolve(uri);
        if (f == null) {
            return "*/*";
        }
        String n = f.getName().toLowerCase();
        if (n.endsWith(".apk")) {
            return "application/vnd.android.package-archive";
        }
        if (n.endsWith(".txt") || n.endsWith(".md")) {
            return "text/plain";
        }
        if (n.endsWith(".zip") || n.endsWith(".apk")) {
            return "application/zip";
        }
        return "*/*";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}