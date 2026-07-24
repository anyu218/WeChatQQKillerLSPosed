package com.murong.prioritylocker;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * PriorityLocker Pro 核心守护服务
 *
 * 运行在 system_server 进程中，使用后台线程定期检测和压制第三方 App 进程优先级。
 * 完全复制 Magisk 版的逻辑：
 *
 * - 前台 App：主进程 + 通知子进程保留，其他子进程压制到 nice=19
 * - 后台 App：主进程 + 非通知子进程全部压制，仅通知子进程保留
 * - 白名单豁免（config_suppress.txt）
 * - 强制压制黑名单（config_force.txt，默认含 GMS 服务）
 * - 自适应变间隔：15s → 25s → 40s → 90s
 * - 日志记录
 * - 实时状态导出（兼容 WebUI）
 */
public class PriorityService implements Runnable {

    private static final String TAG = "[PriorityLocker.Service]";

    private static PriorityService sInstance;

    private final AtomicBoolean mRunning = new AtomicBoolean(false);
    private final ConfigManager mConfig;

    private Thread mDaemonThread;

    // 状态数据（供 WebUI 读取）
    private volatile String mFrontPkg = "unknown";
    private volatile int mRunningCount = 0;
    private volatile int mSuppressedCount = 0;
    private volatile int mWhitelistSkipped = 0;
    private volatile int mCurrentSleep = 15;
    private volatile long mHb = 0;

    // 自适应间隔状态
    private String mPrevFrontPkg = "";
    private long mLastChangeTs = 0;

    // 缓存计数器
    private int mCacheCycle = 0;

    // 汇总计数器
    private long mSummaryTs = 0;
    private int mSummaryActions = 0;

    // 第三方包名缓存
    private Set<String> mThirdPartyCache = null;
    private long mThirdPartyCacheTime = 0;

    public static synchronized PriorityService getInstance() {
        if (sInstance == null) {
            sInstance = new PriorityService();
        }
        return sInstance;
    }

    private PriorityService() {
        mConfig = ConfigManager.getInstance();
    }

    /**
     * 启动守护线程（幂等）
     */
    public synchronized void start() {
        if (mRunning.get()) {
            mConfig.log("守护已在运行，跳过重复启动");
            return;
        }
        mConfig.log("▶ 启动 PriorityService (运行在 system_server)");
        mDaemonThread = new Thread(this, "PriorityLocker-Daemon");
        mDaemonThread.setDaemon(true);
        mDaemonThread.start();
    }

    /**
     * 停止守护
     */
    public synchronized void stop() {
        if (!mRunning.get()) return;
        mRunning.set(false);
        if (mDaemonThread != null) {
            mDaemonThread.interrupt();
            mDaemonThread = null;
        }
        mConfig.log("⏹ 守护已停止");
    }

    public boolean isRunning() {
        return mRunning.get();
    }

    // ========== 状态读取（供 UI/WebUI） ==========

    public String getFrontPkg() { return mFrontPkg; }
    public int getRunningCount() { return mRunningCount; }
    public int getSuppressedCount() { return mSuppressedCount; }
    public int getWhitelistSkipped() { return mWhitelistSkipped; }
    public int getCurrentSleep() { return mCurrentSleep; }
    public long getHb() { return mHb; }

    // ========== 主循环 ==========

    @Override
    public void run() {
        mRunning.set(true);
        mLastChangeTs = System.currentTimeMillis();
        mSummaryTs = System.currentTimeMillis() - 240_000;

        // 初始等待（等待系统就绪）
        try { Thread.sleep(3000); } catch (InterruptedException e) { return; }

        while (mRunning.get() && !Thread.currentThread().isInterrupted()) {
            try {
                oneLoop();
            } catch (Exception e) {
                mConfig.log("循环异常: " + e.getMessage() + " (继续运行)");
            }

            // 自适应睡眠
            try {
                Thread.sleep(mCurrentSleep * 1000L);
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    private void oneLoop() {
        // 1. 获取前台包名
        mFrontPkg = ProcessHelper.getForegroundPackage();

        // 2. 自适应变间隔（与 Magisk 版一致）
        long now = System.currentTimeMillis();
        if (!"unknown".equals(mFrontPkg) && !mFrontPkg.equals(mPrevFrontPkg)) {
            mLastChangeTs = now;
            mCurrentSleep = 15;
            mPrevFrontPkg = mFrontPkg;
        } else {
            long stableTime = (now - mLastChangeTs) / 1000;
            if (stableTime < 30) {
                mCurrentSleep = 15;
            } else if (stableTime < 90) {
                mCurrentSleep = 25;
            } else if (stableTime < 300) {
                mCurrentSleep = 40;
            } else {
                mCurrentSleep = 90;
            }
        }

        // 3. 获取第三方包名列表（带缓存，每 10 轮刷新一次）
        Set<String> thirdParty = getThirdPartyList();
        mCacheCycle++;
        if (mCacheCycle >= 10) mCacheCycle = 0;

        // 合并强制压制黑名单
        Set<String> forceList = mConfig.getForceList();
        Set<String> targetPackages = new HashSet<>(thirdParty);
        targetPackages.addAll(forceList);

        // 4. 扫描进程映射
        Map<String, String> procMap = ProcessHelper.buildProcessMap();

        // 5. 构建"进程中活跃的第三方包名"索引
        // 从 procMap 反向索引哪些 targetPackages 确实有进程在运行
        Set<String> livePackages = new HashSet<>();
        Map<String, List<String>> pkgPidsMap = new HashMap<>();
        for (Map.Entry<String, String> entry : procMap.entrySet()) {
            String cmdline = entry.getValue();
            String basePkg = cmdline.contains(":") ? cmdline.substring(0, cmdline.indexOf(':')) : cmdline;
            if (targetPackages.contains(basePkg)) {
                livePackages.add(basePkg);
                pkgPidsMap.computeIfAbsent(basePkg, k -> new ArrayList<>()).add(entry.getKey());
            }
        }

        // 6. 压制逻辑（与 Magisk 版完全一致）
        mSuppressedCount = 0;
        mRunningCount = 0;
        mWhitelistSkipped = 0;

        List<String> suppressedList = new ArrayList<>();

        for (String pkg : livePackages) {
            // 白名单检查
            if (mConfig.isWhitelisted(pkg)) {
                mWhitelistSkipped++;
                continue;
            }

            List<String> pids = pkgPidsMap.get(pkg);
            if (pids == null || pids.isEmpty()) continue;

            mRunningCount++;
            boolean isForeground = pkg.equals(mFrontPkg);
            int changed = 0;
            int alreadySuppressed = 0;

            for (String pidStr : pids) {
                int pid;
                try { pid = Integer.parseInt(pidStr); } catch (NumberFormatException e) { continue; }

                String cmdline = procMap.get(pidStr);
                if (cmdline == null) continue;

                // 读取当前 nice 值
                int currentNice = ProcessHelper.getNiceValue(pid);
                if (currentNice == 19) {
                    alreadySuppressed++;
                    continue;
                }

                boolean shouldSuppress;
                if (isForeground) {
                    // 前台 App：仅压制非主进程 + 非通知的子进程
                    shouldSuppress = !ProcessHelper.isMainProcess(cmdline, pkg)
                            && !ProcessHelper.isNotificationProcess(cmdline);
                } else {
                    // 后台 App：压制主进程 + 所有非通知子进程
                    shouldSuppress = !ProcessHelper.isNotificationProcess(cmdline);
                }

                if (shouldSuppress) {
                    boolean ok = ProcessHelper.setLowPriority(pid);
                    if (ok) {
                        changed++;
                    }
                }
            }

            int totalSuppressed = changed + alreadySuppressed;
            if (totalSuppressed > 0) {
                mSuppressedCount++;
                suppressedList.add(pkg);
            }
            mSummaryActions += totalSuppressed;
        }

        // 7. 状态输出日志（每 360 轮 ≈ 每 ~1-6 小时，取决于间隔）
        mHb++;
        if (mHb >= 360) {
            mConfig.log("♥ 运行中 前台=" + mFrontPkg
                    + " 运行=" + mRunningCount
                    + " 压制=" + mSuppressedCount
                    + " 白名单=" + mConfig.getWhitelist().size());
            mHb = 0;
        }

        // 8. 每 5 分钟输出时间段汇总
        if ((System.currentTimeMillis() - mSummaryTs) >= 300_000) {
            mConfig.log("📊 汇总：处理 " + mSummaryActions + " 个进程"
                    + " | 维持 " + mSuppressedCount + " 个应用"
                    + " | 跳过白名单 " + mWhitelistSkipped
                    + " | 间隔 " + mCurrentSleep + "s");
            mSummaryTs = System.currentTimeMillis();
            mSummaryActions = 0;
        }

        // 9. 写状态 JSON（供 WebUI/Activity 读取）
        writeStatusJson(suppressedList);
    }

    /**
     * 获取第三方包名列表（带缓存）
     */
    private Set<String> getThirdPartyList() {
        long now = System.currentTimeMillis();
        if (mThirdPartyCache == null || (now - mThirdPartyCacheTime) > 60_000) {
            mThirdPartyCache = ProcessHelper.getThirdPartyPackages();
            mThirdPartyCacheTime = now;
        }
        return mThirdPartyCache;
    }

    /**
     * 写入状态 JSON（供 WebUI 读取）
     */
    private void writeStatusJson(List<String> suppressedList) {
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
            String ts = sdf.format(new Date());
            Set<String> wl = mConfig.getWhitelist();
            Set<String> fl = mConfig.getForceList();

            StringBuilder sb = new StringBuilder();
            sb.append("{\n");
            sb.append("  \"alive\": true,\n");
            sb.append("  \"ts\": \"").append(ts).append("\",\n");
            sb.append("  \"front_pkg\": \"").append(mFrontPkg).append("\",\n");
            sb.append("  \"wl_count\": ").append(wl.size()).append(",\n");
            sb.append("  \"running\": ").append(mRunningCount).append(",\n");
            sb.append("  \"suppressed\": ").append(mSuppressedCount).append(",\n");
            sb.append("  \"wl_skipped\": ").append(mWhitelistSkipped).append(",\n");
            sb.append("  \"sleep\": ").append(mCurrentSleep).append(",\n");
            sb.append("  \"force_count\": ").append(fl.size()).append("\n");
            sb.append("}\n");

            String jsonPath = mConfig.getLogPath().replace("killer.log", "status.json");
            try (java.io.FileWriter fw = new java.io.FileWriter(jsonPath)) {
                fw.write(sb.toString());
            }
        } catch (Exception e) {
            // 写状态文件失败不影响主逻辑
        }
    }
}
