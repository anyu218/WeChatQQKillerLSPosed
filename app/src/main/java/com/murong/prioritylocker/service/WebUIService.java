package com.murong.prioritylocker.service;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

import com.murong.prioritylocker.ConfigManager;
import com.murong.prioritylocker.PriorityService;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 轻量级 HTTP 服务 — 提供 WebUI 管理界面
 * 监听 127.0.0.1:36150（与 Magisk 版相同端口）
 */
public class WebUIService extends Service {

    private static final int PORT = 36150;
    private static final String TAG = "[PriorityLocker.WebUI]";

    private final AtomicBoolean mRunning = new AtomicBoolean(false);
    private ExecutorService mExecutor;
    private ServerSocket mServerSocket;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!mRunning.get()) {
            mRunning.set(true);
            mExecutor = Executors.newSingleThreadExecutor();
            mExecutor.submit(this::runServer);
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        mRunning.set(false);
        if (mServerSocket != null) {
            try { mServerSocket.close(); } catch (IOException ignored) {}
        }
        if (mExecutor != null) {
            mExecutor.shutdownNow();
        }
        super.onDestroy();
    }

    private void runServer() {
        try {
            mServerSocket = new ServerSocket(PORT, 5,
                    java.net.InetAddress.getByName("127.0.0.1"));
            ConfigManager.getInstance().log("WebUI 已启动 (127.0.0.1:" + PORT + ")");

            while (mRunning.get() && !mServerSocket.isClosed()) {
                try {
                    Socket client = mServerSocket.accept();
                    mExecutor.submit(() -> handleClient(client));
                } catch (IOException e) {
                    if (mRunning.get()) {
                        ConfigManager.getInstance().log("WebUI accept 错误: " + e.getMessage());
                    }
                }
            }
        } catch (IOException e) {
            ConfigManager.getInstance().log("WebUI 启动失败: " + e.getMessage());
        }
    }

    private void handleClient(Socket socket) {
        try (socket) {
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream()));
            String requestLine = reader.readLine();
            if (requestLine == null) return;

            String path = "/";
            if (requestLine.startsWith("GET ")) {
                int start = 4;
                int end = requestLine.indexOf(' ', start);
                if (end > start) {
                    path = requestLine.substring(start, end);
                }
            }

            // 读取请求头
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {}

            // 响应
            String response;
            String contentType;

            if (path.equals("/") || path.equals("/index.html")) {
                response = getIndexHtml();
                contentType = "text/html; charset=utf-8";
            } else if (path.equals("/api/status")) {
                response = getStatusJson();
                contentType = "application/json; charset=utf-8";
            } else if (path.equals("/api/data.js")) {
                response = getDataJs();
                contentType = "application/javascript; charset=utf-8";
            } else if (path.equals("/api/log")) {
                response = getLog();
                contentType = "text/plain; charset=utf-8";
            } else if (path.startsWith("/api/whitelist")) {
                response = getWhitelistJson();
                contentType = "application/json; charset=utf-8";
            } else if (path.startsWith("/api/add?pkg=")) {
                String pkg = path.substring(path.indexOf("pkg=") + 4);
                response = addToWhitelist(pkg);
                contentType = "application/json; charset=utf-8";
            } else if (path.startsWith("/api/remove?pkg=")) {
                String pkg = path.substring(path.indexOf("pkg=") + 4);
                response = removeFromWhitelist(pkg);
                contentType = "application/json; charset=utf-8";
            } else {
                response = "{\"error\":\"not found\"}";
                contentType = "application/json";
            }

            byte[] respBytes = response.getBytes("UTF-8");
            String header = "HTTP/1.1 200 OK\r\n"
                    + "Content-Type: " + contentType + "\r\n"
                    + "Content-Length: " + respBytes.length + "\r\n"
                    + "Access-Control-Allow-Origin: *\r\n"
                    + "Connection: close\r\n"
                    + "\r\n";
            socket.getOutputStream().write(header.getBytes());
            socket.getOutputStream().write(respBytes);
            socket.getOutputStream().flush();
        } catch (IOException e) {
            // 单个客户端处理失败不影响整体
        }
    }

    private String getIndexHtml() {
        return "<!DOCTYPE html><html><head><meta charset='utf-8'>"
                + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<title>PriorityLocker Pro · WebUI</title>"
                + "<style>"
                + "body{font-family:-apple-system,sans-serif;background:#0b0b1a;color:#fff;padding:20px;max-width:480px;margin:0 auto}"
                + ".card{background:rgba(255,255,255,0.06);border-radius:16px;padding:16px;margin-bottom:14px;border:1px solid rgba(255,255,255,0.08)}"
                + "h2{color:#a78bfa;font-size:18px;margin:0 0 10px 0}"
                + ".status{font-size:14px;line-height:1.8}"
                + ".badge{display:inline-block;padding:2px 10px;border-radius:20px;font-size:12px;margin:2px}"
                + ".badge-green{background:rgba(34,197,94,0.2);color:#22c55e}"
                + ".badge-blue{background:rgba(96,165,250,0.2);color:#60a5fa}"
                + ".badge-red{background:rgba(239,68,68,0.2);color:#ef4444}"
                + "pre{font-size:11px;color:#aaa;max-height:200px;overflow:auto}"
                + "input{background:rgba(255,255,255,0.08);border:1px solid rgba(255,255,255,0.12);color:#fff;padding:8px 12px;border-radius:8px;width:60%}"
                + "button{background:#7c3aed;color:#fff;border:none;padding:8px 16px;border-radius:8px;cursor:pointer}"
                + "button:hover{background:#6d28d9}"
                + "</style></head><body>"
                + "<h2>🔒 PriorityLocker Pro</h2>"
                + "<div class='card'><div class='status' id='status'>加载中...</div></div>"
                + "<div class='card'><h2>白名单管理</h2>"
                + "<input type='text' id='pkgInput' placeholder='输入包名'> "
                + "<button onclick='addPkg()'>添加</button>"
                + "<div id='wlList' style='margin-top:8px;font-size:13px'></div></div>"
                + "<div class='card'><h2>📋 最近日志</h2><pre id='log'>加载中...</pre></div>"
                + "<script>"
                + "function refresh(){"
                + "fetch('/api/status').then(r=>r.json()).then(d=>{"
                + "document.getElementById('status').innerHTML="
                + "'● 状态: <span class=\\\\'badge badge-green\\\\'>运行中</span>' +"
                + "'<br>前台: ' + d.front_pkg +"
                + "'<br>运行中: ' + d.running + ' 个 | 压制: ' + d.suppressed + ' 个' +"
                + "'<br>白名单: ' + d.wl_count + ' 项 | 跳过: ' + d.wl_skipped +"
                + "'<br>检测间隔: ' + d.sleep + 's'"
                + "});"
                + "fetch('/api/whitelist').then(r=>r.json()).then(d=>{"
                + "let h='';d.forEach(p=>{h+='<span class=\\\\'badge badge-blue\\\\'>'+p+' ✕</span>'});"
                + "document.getElementById('wlList').innerHTML=h||'(空)'"
                + "});"
                + "fetch('/api/log').then(r=>r.text()).then(t=>{"
                + "document.getElementById('log').textContent=t"
                + "})"
                + "}"
                + "function addPkg(){"
                + "let p=document.getElementById('pkgInput').value.trim();if(!p)return;"
                + "fetch('/api/add?pkg='+encodeURIComponent(p)).then(r=>r.json()).then(()=>{refresh();document.getElementById('pkgInput').value=''})"
                + "}"
                + "setInterval(refresh,5000);refresh();"
                + "</script></body></html>";
    }

    private String getStatusJson() {
        PriorityService s = PriorityService.getInstance();
        ConfigManager c = ConfigManager.getInstance();
        return "{\"alive\":" + s.isRunning()
                + ",\"front_pkg\":\"" + (s.getFrontPkg() != null ? s.getFrontPkg() : "unknown") + "\""
                + ",\"running\":" + s.getRunningCount()
                + ",\"suppressed\":" + s.getSuppressedCount()
                + ",\"wl_count\":" + c.getWhitelist().size()
                + ",\"wl_skipped\":" + s.getWhitelistSkipped()
                + ",\"sleep\":" + s.getCurrentSleep()
                + "}";
    }

    private String getDataJs() {
        return "var gStatus=" + getStatusJson() + ";";
    }

    private String getLog() {
        String logPath = ConfigManager.getInstance().getLogPath();
        File f = new File(logPath);
        if (!f.exists()) return "(空)";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String line;
            java.util.List<String> lines = new java.util.ArrayList<>();
            while ((line = br.readLine()) != null) {
                lines.add(line);
                if (lines.size() > 50) lines.remove(0);
            }
            for (String l : lines) sb.append(l).append("\n");
        } catch (IOException e) {
            return "读取失败";
        }
        return sb.toString();
    }

    private String getWhitelistJson() {
        java.util.Set<String> wl = ConfigManager.getInstance().getWhitelist();
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (String pkg : wl) {
            if (!first) sb.append(",");
            sb.append("\"").append(pkg).append("\"");
            first = false;
        }
        sb.append("]");
        return sb.toString();
    }

    private String addToWhitelist(String pkg) {
        if (pkg == null || pkg.isEmpty()) return "{\"error\":\"empty\"}";
        try (FileWriter fw = new FileWriter(ConfigManager.getInstance().getWhitelistPath(), true)) {
            fw.write(pkg + "\n");
            ConfigManager.getInstance().log("白名单添加: " + pkg);
            return "{\"ok\":true}";
        } catch (IOException e) {
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    private String removeFromWhitelist(String pkg) {
        if (pkg == null || pkg.isEmpty()) return "{\"error\":\"empty\"}";
        try {
            File f = new File(ConfigManager.getInstance().getWhitelistPath());
            java.util.List<String> lines = new java.util.ArrayList<>();
            try (BufferedReader br = new BufferedReader(new FileReader(f))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (!line.trim().equals(pkg)) {
                        lines.add(line);
                    }
                }
            }
            try (FileWriter fw = new FileWriter(f)) {
                for (String l : lines) {
                    fw.write(l + "\n");
                }
            }
            ConfigManager.getInstance().log("白名单移除: " + pkg);
            return "{\"ok\":true}";
        } catch (IOException e) {
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }
}
