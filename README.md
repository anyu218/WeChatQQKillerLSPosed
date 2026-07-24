# PriorityLocker Pro — LSPosed 版

[![Build APK](https://github.com/MURONG-TEAM/WeChatQQKiller_LSPosed/actions/workflows/build.yml/badge.svg)](https://github.com/MURONG-TEAM/WeChatQQKiller_LSPosed/actions/workflows/build.yml)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

> 将原 Magisk 模块的全局第三方进程优先级压制逻辑改造为 LSPosed 模块，  
> 运行在 `system_server` 中，无需 root 即可使用 `Process.setProcessGroup()` 内核级压制。

| 原 Magisk 版 | 本 LSPosed 版 |
|-------------|--------------|
| `/data/adb/modules/WeChatQQKiller/` | 本项目 |
| 需要 root + Magisk | 需要 LSPosed 框架（无 root 也行） |
| `renice` shell 命令 | Android Framework API（内核级 cgroup） |

---

## 功能说明

完全复制 Magisk 版的全局第三方进程优先级压制逻辑。

### 核心压制策略

| 场景 | 主进程 | 通知子进程(:push/:remote/:msf等) | 其他子进程 |
|------|--------|----------------------------------|-----------|
| **前台 App** | ✓ 保留 | ✓ 保留 | ✗ 压制(nice=19) |
| **后台 App** | ✗ 压制(nice=19) | ✓ 保留 | ✗ 压制(nice=19) |

### 附加功能

- **白名单** (`config_suppress.txt`) — 加入的 App 完全不受压制
- **强制压制黑名单** (`config_force.txt`) — 默认压制 Google Play 服务
- **自适应检测间隔** — 前台变化后 15s 快速响应，稳定后逐渐降频到 90s
- **WebUI 管理** — `http://127.0.0.1:36150`（与 Magisk 版相同端口）
- **日志记录** — `/data/data/com.murong.prioritylocker/files/killer.log`

## 原理说明

Magisk 版使用 `renice -n 19 -p <pid>` 设置进程优先级。  
LSPosed 版使用等效的 Android Framework API（运行在 system_server 中）：

| Magisk（root shell） | LSPosed（system_server） |
|----------------------|--------------------------|
| `renice -n 19 -p PID` | `Process.setProcessGroup(pid, BACKGROUND)` — CPU cgroup |
| `renice -n 19 -p PID` | `Os.setpriority(PRIO_PROCESS, pid, 19)` — nice 值 |
| `renice -n 19 -p PID` | `Process.setThreadPriority(pid, LOWEST)` — 线程优先级 |

三种方式同时使用，确保最大兼容性。

---

## 构建方法

### 方式一：GitHub Actions（推荐 ⭐）

**一键云端构建，无需本地环境：**

1. Fork 或推送代码到你的 GitHub 仓库
2. 进入仓库 → **Actions** → **Build APK** → **Run workflow**
3. 选择 `release` 或 `debug`，等待 2-3 分钟
4. 在 Actions 运行结果页面下载 APK 产物

**自动触发：**
- 推送 `main`/`master` 分支 → 自动构建
- 创建 Pull Request → 自动构建验证
- 创建 Release → 自动构建并上传 APK 到 Release 附件

### 方式二：本地 Android Studio

```bash
# 前置条件
#   - Android Studio Hedgehog 2023.1+
#   - Java 11+
#   - Android SDK 34

# 1. 克隆
git clone https://github.com/你的用户名/WeChatQQKiller_LSPosed.git
cd WeChatQQKiller_LSPosed

# 2. 生成 Gradle Wrapper（首次需要）
gradle wrapper --gradle-version=8.5

# 3. 构建
./gradlew assembleRelease

# 4. 安装
adb install app/build/outputs/apk/release/app-release.apk

# 或者跳过 wrapper，直接使用系统 Gradle：
# gradle assembleRelease
```

或者在 Android Studio 中直接打开项目，点击 `Build → Build APK`。

### 方式三：命令行（无 Android Studio）

```bash
# 1. 安装 JDK 17
sudo apt install openjdk-17-jdk

# 2. 安装 Android SDK command-line tools
# 下载 cmdline-tools 后：
export ANDROID_HOME=~/Android/Sdk
yes | $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager \
  "platforms;android-34" \
  "build-tools;34.0.0"

# 3. 生成 Gradle Wrapper（首次）
gradle wrapper --gradle-version=8.5

# 4. 构建
./gradlew assembleRelease
```

---

## GitHub Actions 工作流说明

工作流文件：`.github/workflows/build.yml`

| 特性 | 说明 |
|------|------|
| **构建系统** | Gradle 8.5 + AGP 8.2.2 + JDK 17 |
| **SDK 版本** | compileSdk = 34, minSdk = 26, targetSdk = 34 |
| **缓存** | Gradle 依赖缓存加速二次构建 |
| **产物保存** | 30 天，可在 Actions 页面下载 |
| **Release 自动上传** | 打 tag 发布时自动附加 APK |

### 手动触发

1. 打开仓库 → **Actions** 标签
2. 左侧点击 **Build APK**
3. 点击 **Run workflow** 按钮
4. 选择构建类型（release/debug）
5. 点击 **Run workflow**
6. 等待完成后点击运行记录，在 **Artifacts** 处下载

---

## 安装激活

1. 安装 APK 后，打开 **LSPosed 管理器**
2. 在模块列表中找到 **PriorityLocker Pro**
3. 勾选启用
4. **作用域**：只需勾选 `系统框架 (system_server)`
5. 重启 SystemUI 或重启手机

## 验证运行

```bash
# 检查日志
adb shell cat /data/data/com.murong.prioritylocker/files/killer.log

# 检查 WebUI
adb shell "curl -s http://127.0.0.1:36150/status | head -20"

# 检查进程是否运行在 system_server 中
adb shell ps -A | grep system_server
```

## 文件结构

```
WeChatQQKiller_LSPosed/
├── .github/workflows/build.yml   # GitHub Actions 自动构建
├── .gitignore
├── build.gradle.kts              # 项目级构建 (AGP 8.2.2)
├── settings.gradle.kts           # 仓库配置 (含 Xposed API 源)
├── gradle.properties
└── app/
    ├── build.gradle.kts          # 模块构建 (API 82)
    └── src/main/
        ├── AndroidManifest.xml
        ├── assets/
        │   ├── xposed_init
        │   └── lsposed_scope
        ├── java/com/murong/prioritylocker/
        │   ├── MainHook.java
        │   ├── PriorityService.java
        │   ├── ProcessHelper.java
        │   ├── ConfigManager.java
        │   ├── ui/MainActivity.java
        │   └── service/WebUIService.java
        └── res/
```

## License

Apache 2.0
# CI build trigger
