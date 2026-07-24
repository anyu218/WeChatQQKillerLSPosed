package com.murong.prioritylocker;

import android.os.Process;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * PriorityLocker Pro - LSPosed 入口
 *
 * 功能：完全复制 Magisk 版的进程优先级压制逻辑
 * - 前台 App：仅压制子进程（保留主进程和通知推送进程）
 * - 后台 App：压制主进程 + 子进程（仅保留通知推送进程）
 * - 白名单豁免 + 强制压制黑名单
 * - 自适应检测间隔
 *
 * 原理：以 LSPosed 模块形式注入 system_server，
 * 使用 Android Framework API（setProcessGroup / setThreadPriority）
 * 替代 Magisk 版的 renice 命令，实现相同效果且无需 root。
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "[PriorityLocker]";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        // 目标：system_server 进程（在 Xposed 中包名为 "android"）
        if (!lpparam.packageName.equals("android")) {
            return;
        }

        // 确保我们确实在 system_server 中
        String processName = Process.myProcessName();
        if (processName == null || !processName.equals("system_server")) {
            XposedBridge.log(TAG + " 跳过进程: " + processName);
            return;
        }

        XposedBridge.log(TAG + " ✔ 注入 system_server 成功，启动 PriorityService...");

        // 在 system_server 中启动后台守护线程
        PriorityService.getInstance().start();
    }
}
