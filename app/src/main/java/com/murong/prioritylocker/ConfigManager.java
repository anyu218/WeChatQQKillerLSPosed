package com.murong.prioritylocker;

import android.text.TextUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 配置管理 — 白名单 / 强制压制黑名单
 *
 * 文件存储路径：/data/data/com.murong.prioritylocker/files/
 * 兼容原 Magisk 模块的 config_suppress.txt / config_force.txt 格式
 */
public class ConfigManager {

    private static final String TAG = "[PriorityLocker.Config]";

    // 配置文件存储目录（模块私有目录，无需 root）
    private static final String CONFIG_DIR = "/data/data/com.murong.prioritylocker/files";

    private static final String WHITELIST_FILE = CONFIG_DIR + "/config_suppress.txt";
    private static final String FORCELIST_FILE = CONFIG_DIR + "/config_force.txt";
    private static final String LOG_FILE = CONFIG_DIR + "/killer.log";

    private static ConfigManager sInstance;

    private final Set<String> mWhitelist = new HashSet<>();
    private final Set<String> mForceList = new HashSet<>();
    private long mLastWhitelistLoad = 0;
    private long mLastForceLoad = 0;

    // 缓存刷新间隔（毫秒）
    private static final long CACHE_TTL_MS = 10_000;

    private ConfigManager() {
        ensureConfigDir();
        loadConfigs();
    }

    public static synchronized ConfigManager getInstance() {
        if (sInstance == null) {
            sInstance = new ConfigManager();
        }
        return sInstance;
    }

    private void ensureConfigDir() {
        File dir = new File(CONFIG_DIR);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        // 确保默认配置文件存在
        ensureFile(WHITELIST_FILE);
        ensureFile(FORCELIST_FILE);
    }

    private void ensureFile(String path) {
        File f = new File(path);
        if (!f.exists()) {
            try {
                f.createNewFile();
                if (path.endsWith("config_force.txt")) {
                    // 写入默认强制压制列表（GMS）
                    try (FileWriter w = new FileWriter(f)) {
                        w.write("com.android.vending\n");
                        w.write("com.google.android.gms\n");
                    }
                }
            } catch (IOException e) {
                log("创建配置文件失败: " + path + " " + e.getMessage());
            }
        }
    }

    /**
     * 加载白名单和强制列表（带缓存）
     */
    public synchronized void loadConfigs() {
        long now = System.currentTimeMillis();
        if (now - mLastWhitelistLoad > CACHE_TTL_MS) {
            mWhitelist.clear();
            loadListFromFile(WHITELIST_FILE, mWhitelist);
            mLastWhitelistLoad = now;
        }
        if (now - mLastForceLoad > CACHE_TTL_MS) {
            mForceList.clear();
            loadListFromFile(FORCELIST_FILE, mForceList);
            mLastForceLoad = now;
        }
    }

    private void loadListFromFile(String path, Set<String> target) {
        File f = new File(path);
        if (!f.exists()) return;
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (TextUtils.isEmpty(line) || line.startsWith("#")) {
                    continue;
                }
                target.add(line);
            }
        } catch (IOException e) {
            log("读取配置文件失败: " + path + " " + e.getMessage());
        }
    }

    /**
     * 检查包名是否在白名单中
     */
    public boolean isWhitelisted(String pkg) {
        loadConfigs();
        return mWhitelist.contains(pkg);
    }

    /**
     * 检查包名是否在强制压制黑名单中
     */
    public boolean isForceListed(String pkg) {
        loadConfigs();
        return mForceList.contains(pkg);
    }

    public Set<String> getWhitelist() {
        loadConfigs();
        return new HashSet<>(mWhitelist);
    }

    public Set<String> getForceList() {
        loadConfigs();
        return new HashSet<>(mForceList);
    }

    public String getWhitelistPath() {
        return WHITELIST_FILE;
    }

    public String getForceListPath() {
        return FORCELIST_FILE;
    }

    public String getLogPath() {
        return LOG_FILE;
    }

    // ========== 日志 ==========

    public void log(String msg) {
        String line = "[" + java.text.SimpleDateFormat.getDateTimeInstance()
                .format(new java.util.Date()) + "] " + msg;
        XposedBridge.log(TAG + " " + msg);
        try (FileWriter fw = new FileWriter(LOG_FILE, true)) {
            fw.write(line + "\n");
        } catch (IOException ignored) {
        }
    }
}
