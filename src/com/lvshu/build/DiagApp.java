package com.lvshu.build;

import android.app.Application;
import android.content.Context;

public class DiagApp extends Application {
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        DiagLog.install(this);
        DiagLog.mark("进程启动 attachBaseContext");
    }

    @Override
    public void onCreate() {
        super.onCreate();
        DiagLog.mark("Application.onCreate");
        DiagLog.deviceInfo(this);
        DiagLog.checkLastCrash(this);
    }
}
