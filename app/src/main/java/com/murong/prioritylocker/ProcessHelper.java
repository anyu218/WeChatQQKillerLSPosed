package com.murong.prioritylocker;

import android.os.Process;
import android.system.Os;
import android.system.OsConstants;
import android.text.TextUtils;
import android.util.ArrayMap;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 进程辅助工具 — 扫描 /proc、获取前台包名、设置进程优先级
 *
 * 使用 Android Framework API 替代 renice 命令：
 * - Process.setProcessGroup() → CPU cgroup 控制（等效于 renice -n 19）
 * - Process.setThreadPriority() → 直接设置 nice 值
 * - android.system.Os.setpriority() → 直接系统调用（最高权限路径）
 */
public class ProcessHelper {

    private static final String TAG = "[PriorityLocker.Proc]";

    private ProcessHelper() {}

    /**
     * 获取当前前台应用包名
     * 通过读取 dumpsys window 输出（与 Magisk 版相同方式）
     */
    public static String getForegroundPackage() {
        try {
            java.lang.Process proc = Runtime.getRuntime().exec("dumpsys window");
            try (BufferedReader br = new BufferedReader(
                    new java.io.InputStreamReader(proc.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.contains("mCurrentFocus") || line.contains("mFocusedApp")) {
                        // 解析:  mCurrentFocus=Window{... com.example.app/com.example.MainActivity}
                        int idx = line.lastIndexOf(' ');
                        if (idx > 0) {
                            String part = line.substring(idx).trim();
                            // 去掉尾部的 }
                            if (part.endsWith("}")) {
                                part = part.substring(0, part.length() - 1);
                            }
                            // 取包名部分（/ 之前）
                            int slash = part.indexOf('/');
                            if (slash > 0) {
                                return part.substring(0, slash);
                            }
                            return part;
                        }
                    }
                }
            }
        } catch (IOException e) {
            ConfigManager.getInstance().log("获取前台包名失败: " + e.getMessage());
        }
        return "unknown";
    }

    /**
     * 扫描 /proc 构建进程 → 包名映射
     * 返回 Map<pid, cmdline>
     */
    public static Map<String, String> buildProcessMap() {
        Map<String, String> map = new HashMap<>();
        File[] procDirs = new File("/proc").listFiles();
        if (procDirs == null) return map;

        for (File f : procDirs) {
            if (!f.isDirectory()) continue;
            String name = f.getName();
            if (!name.matches("\\d+")) continue; // 只处理数字目录（PID）

            File cmdlineFile = new File(f, "cmdline");
            if (!cmdlineFile.canRead()) continue;

            try (BufferedReader br = new BufferedReader(new FileReader(cmdlineFile))) {
                String cmdline = br.readLine();
                if (TextUtils.isEmpty(cmdline)) continue;
                // cmdline 以 \0 分隔参数，取第一段
                String clean = cmdline.replace('\0', ' ').trim();
                if (!TextUtils.isEmpty(clean)) {
                    map.put(name, clean);
                }
            } catch (IOException ignored) {
            }
        }
        return map;
    }

    /**
     * 获取系统中所有第三方 App 包名
     */
    public static Set<String> getThirdPartyPackages() {
        Set<String> pkgs = new HashSet<>();
        try {
            java.lang.Process proc = Runtime.getRuntime().exec("pm list packages -3");
            try (BufferedReader br = new BufferedReader(
                    new java.io.InputStreamReader(proc.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) {
                    // pm 输出格式: "package:com.example.app"
                    if (line.startsWith("package:")) {
                        pkgs.add(line.substring(8).trim());
                    }
                }
            }
        } catch (IOException e) {
            ConfigManager.getInstance().log("获取第三方包名失败: " + e.getMessage());
        }
        return pkgs;
    }

    /**
     * 获取系统中所有已安装包名（含系统应用）
     */
    public static Set<String> getAllPackages() {
        Set<String> pkgs = new HashSet<>();
        try {
            java.lang.Process proc = Runtime.getRuntime().exec("pm list packages");
            try (BufferedReader br = new BufferedReader(
                    new java.io.InputStreamReader(proc.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.startsWith("package:")) {
                        pkgs.add(line.substring(8).trim());
                    }
                }
            }
        } catch (IOException e) {
            ConfigManager.getInstance().log("获取所有包名失败: " + e.getMessage());
        }
        return pkgs;
    }

    /**
     * 获取指定包名的 PID（取第一个）
     */
    public static String getPidForPackage(String pkg, Map<String, String> procMap) {
        for (Map.Entry<String, String> entry : procMap.entrySet()) {
            String cmdline = entry.getValue();
            if (cmdline.equals(pkg) || cmdline.startsWith(pkg + ":")) {
                // 主进程优先
                if (cmdline.equals(pkg)) {
                    return entry.getKey();
                }
            }
        }
        // 如果没有找到主进程，返回第一个匹配的子进程
        for (Map.Entry<String, String> entry : procMap.entrySet()) {
            String cmdline = entry.getValue();
            if (cmdline.equals(pkg) || cmdline.startsWith(pkg + ":")) {
                return entry.getKey();
            }
        }
        return null;
    }

    /**
     * 获取某个包名的所有进程 PID
     */
    public static List<String> getPidsForPackage(String pkg, Map<String, String> procMap) {
        List<String> pids = new ArrayList<>();
        for (Map.Entry<String, String> entry : procMap.entrySet()) {
            String cmdline = entry.getValue();
            if (cmdline.equals(pkg) || cmdline.startsWith(pkg + ":")) {
                pids.add(entry.getKey());
            }
        }
        return pids;
    }

    /**
     * 判断进程 cmdline 是否是通知/推送相关子进程
     */
    public static boolean isNotificationProcess(String cmdline) {
        return cmdline.contains(":push")
                || cmdline.contains(":remote")
                || cmdline.contains(":msf")
                || cmdline.contains(":MSF")
                || cmdline.contains(":notification")
                || cmdline.contains(":fcm")
                || cmdline.contains(":channel")
                || cmdline.contains(":hms");
    }

    /**
     * 判断进程 cmdline 是否是该包名的主进程
     */
    public static boolean isMainProcess(String cmdline, String pkg) {
        return cmdline.equals(pkg);
    }

    /**
     * 设置进程优先级（等效于 renice -n 19）
     *
     * 使用多种方式来确保生效：
     * 1. Process.setProcessGroup() → CPU cgroup 控制
     * 2. Os.setpriority() → 直接 nice 值设置（系统调用）
     * 3. Process.setThreadPriority() → 线程优先级
     *
     * @return true 如果成功设置了优先级
     */
    public static boolean setLowPriority(int pid) {
        boolean success = false;

        try {
            // 方法 1：设置进程组为 BACKGROUND（限制 CPU 使用率 ~10%）
            // THREAD_GROUP_BACKGROUND = 0
            android.os.Process.setProcessGroup(pid, 0);
            success = true;
        } catch (Exception e) {
            ConfigManager.getInstance().log("setProcessGroup(" + pid + ") 失败: " + e.getMessage());
        }

        try {
            // 方法 2：直接系统调用 setpriority(PRIO_PROCESS, pid, 19)
            // 等效于 renice -n 19 -p pid
            // PRIO_PROCESS = 0 (POSIX 标准值)
            Os.setpriority(/* PRIO_PROCESS */ 0, pid, 19);
            success = true;
        } catch (Exception e) {
            ConfigManager.getInstance().log("Os.setpriority(" + pid + ") 失败: " + e.getMessage());
        }

        try {
            // 方法 3：setThreadPriority（对主线程有效）
            android.os.Process.setThreadPriority(pid, android.os.Process.THREAD_PRIORITY_LOWEST);
            success = true;
        } catch (Exception e) {
            ConfigManager.getInstance().log("setThreadPriority(" + pid + ") 失败: " + e.getMessage());
        }

        return success;
    }

    /**
     * 读取进程的当前 nice 值
     */
    public static int getNiceValue(int pid) {
        try {
            // 从 /proc/pid/stat 读取第 19 个字段（nice 值）
            File statFile = new File("/proc/" + pid + "/stat");
            if (!statFile.canRead()) return -1;
            try (BufferedReader br = new BufferedReader(new FileReader(statFile))) {
                String line = br.readLine();
                if (line == null) return -1;
                String[] parts = line.split(" ");
                if (parts.length >= 19) {
                    return Integer.parseInt(parts[18]); // nice 值是第 19 个字段（0-indexed: 18）
                }
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    /**
     * 获取 OOM Score Adj 值
     */
    public static int getOomScoreAdj(int pid) {
        try {
            File f = new File("/proc/" + pid + "/oom_score_adj");
            if (!f.canRead()) return -1;
            try (BufferedReader br = new BufferedReader(new FileReader(f))) {
                return Integer.parseInt(br.readLine().trim());
            }
        } catch (Exception ignored) {
        }
        return -1;
    }
}
