package com.lvshu.build;

/**
 * 游戏文件访问通道。
 *
 * 同一次操作选定通道后，探测、列目录、读取、写入和校验必须使用同一实现。
 * DIRECT 只代表当前应用进程确实能够访问目标目录，不能用自身私有目录
 * 可写来推断其他应用的 Android/data 目录可写。
 *
 * 路径为绝对路径。list 返回直属子项名称，不包含 "." 和 ".."；
 * 对普通文件返回 null；目录不存在、权限不足或执行失败时抛出异常。
 *
 * read/write 面向二进制数据，不进行字符编码转换。
 * rename 的目标必须不存在。delete 可递归删除目录。
 *
 * 为保留 Engine 原有公共方法签名，接口使用非受检 StorageException；
 * Engine 中原本声明 throws IOException 的入口会转换该异常。
 */
public interface GameFileSystem {

    enum Backend {
        DIRECT, ROOT, SHIZUKU
    }

    Backend backend();

    boolean exists(String path);

    String[] list(String path);

    byte[] read(String path);

    void write(String path, byte[] data);

    void mkdirs(String path);

    void delete(String path);

    void rename(String from, String to);

    /** 返回小写、64 位十六进制 SHA-256。 */
    String sha256(String path);

    final class StorageException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        public StorageException(String message) {
            super(message);
        }

        public StorageException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
