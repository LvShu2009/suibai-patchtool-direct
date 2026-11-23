package com.lvshu.build;

import android.view.View;

/** 一个页面。 */
public interface Pane {
    View view();

    void onShow();

    /** Activity 从后台恢复时调用，只刷新权限状态。 */
    default void onHostResume() {}
}