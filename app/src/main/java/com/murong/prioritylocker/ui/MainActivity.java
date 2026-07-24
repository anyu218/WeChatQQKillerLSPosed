package com.murong.prioritylocker.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.murong.prioritylocker.ConfigManager;
import com.murong.prioritylocker.PriorityService;
import com.murong.prioritylocker.R;
import com.murong.prioritylocker.service.WebUIService;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Locale;

/**
 * PriorityLocker Pro 管理 Activity
 *
 * 显示运行状态 + 提供白名单/强制列表管理 + WebUI 入口
 */
public class MainActivity extends Activity {

    private TextView mStatusText;
    private TextView mLogText;
    private Button mToggleBtn;
    private Button mWebUIBtn;
    private Button mRefreshBtn;
    private Handler mHandler;
    private final Runnable mRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            refreshStatus();
            mHandler.postDelayed(this, 3000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mStatusText = findViewById(R.id.status_text);
        mLogText = findViewById(R.id.log_text);
        mToggleBtn = findViewById(R.id.toggle_btn);
        mWebUIBtn = findViewById(R.id.webui_btn);
        mRefreshBtn = findViewById(R.id.refresh_btn);

        mHandler = new Handler(Looper.getMainLooper());

        mToggleBtn.setOnClickListener(v -> toggleService());
        mWebUIBtn.setOnClickListener(v -> startWebUI());
        mRefreshBtn.setOnClickListener(v -> refreshStatus());

        // 首次刷新
        refreshStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mHandler.postDelayed(mRefreshRunnable, 3000);
        refreshStatus();
    }

    @Override
    protected void onPause() {
        super.onPause();
        mHandler.removeCallbacks(mRefreshRunnable);
    }

    private void refreshStatus() {
        ConfigManager config = ConfigManager.getInstance();
        PriorityService service = PriorityService.getInstance();

        boolean running = service.isRunning();
        String frontPkg = service.getFrontPkg();
        int runCount = service.getRunningCount();
        int supCount = service.getSuppressedCount();
        int wlSkip = service.getWhitelistSkipped();
        int sleep = service.getCurrentSleep();
        int wlCount = config.getWhitelist().size();
        int flCount = config.getForceList().size();

        String status = String.format(Locale.getDefault(),
                "● 状态: %s\n" +
                        "前台: %s\n" +
                        "运行中: %d 个 | 压制: %d 个\n" +
                        "白名单跳过: %d | 白名单: %d 项\n" +
                        "强制压制: %d 项\n" +
                        "检测间隔: %ds",
                running ? "✅ 运行中" : "⏹ 已停止",
                frontPkg != null ? frontPkg : "unknown",
                runCount, supCount,
                wlSkip, wlCount, flCount,
                sleep);

        mStatusText.setText(status);
        mToggleBtn.setText(running ? "⏹ 停止守护" : "▶ 启动守护");

        // 读取最近日志
        readRecentLog();
    }

    private void readRecentLog() {
        String logPath = ConfigManager.getInstance().getLogPath();
        File logFile = new File(logPath);
        if (!logFile.exists()) {
            mLogText.setText("(日志文件不存在)");
            return;
        }

        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new FileReader(logFile))) {
            String line;
            java.util.List<String> lines = new java.util.ArrayList<>();
            while ((line = br.readLine()) != null) {
                lines.add(line);
                if (lines.size() > 20) {
                    lines.remove(0);
                }
            }
            for (String l : lines) {
                sb.append(l).append("\n");
            }
        } catch (IOException e) {
            sb.append("读取日志失败: ").append(e.getMessage());
        }
        mLogText.setText(sb.toString());
    }

    private void toggleService() {
        PriorityService service = PriorityService.getInstance();
        if (service.isRunning()) {
            service.stop();
            Toast.makeText(this, "守护已停止", Toast.LENGTH_SHORT).show();
        } else {
            service.start();
            Toast.makeText(this, "守护已启动", Toast.LENGTH_SHORT).show();
        }
        refreshStatus();
    }

    private void startWebUI() {
        Intent intent = new Intent(this, WebUIService.class);
        startService(intent);
        Toast.makeText(this, "WebUI 已启动 (http://127.0.0.1:36150)", Toast.LENGTH_LONG).show();
    }
}
