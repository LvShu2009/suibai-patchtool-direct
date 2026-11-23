package com.lvshu.build;

import android.app.Activity;

/** 面板与主界面之间的最小契约。 */
public interface Host {
    Ui ui();

    Activity act();

    Engine engine();

    void log(String s);

    void toast(String s);

    String gamePkg();

    void setGamePkg(String pkg);

    interface FilePick {
        void onPicked(java.io.File file, String name);
    }

    /** 选一个本地文件并复制到私有临时目录。 */
    void pickFile(FilePick cb);
}