# 小爱隐藏功能解锁 (Xiaomi AI DevOptions Hook)

LSPosed 模块，解锁超级小爱 (com.miui.voiceassist) 的开发者选项，强制打开第三方 API 设置入口。

## 功能

通过 Hook 绕过以下门禁：
1. `ca1/a.isEnabled()` → 开发者选项总开关
2. `g1.isLogin()` → 小米账号登录检查
3. `DevOptionsScreen` → 权限控制视图

解锁后可以在超级小爱中看到：
- 开发者选项页面
- LLM 模型设置（API Key、Base URL、Model Name、Provider）
- 第三方 OpenAI 兼容 API 配置

## 环境要求

- 已 Root（SukiSU / Magisk）
- 已安装 LSPosed（IT 7846+）
- 已安装超级小爱 (com.miui.voiceassist)

## 编译方法

### 构建环境要求

| 组件 | 版本 | 说明 |
|------|------|------|
| JDK | 17 或 21 | Android Studio 自带的 JBR 即可 |
| Gradle | 8.11.1 | 已随项目提供 wrapper（`gradlew` + `gradle-wrapper.jar`） |
| AGP | 8.3.2 | 需要 Android Studio Iguana (2023.2.1) 或更新 |
| Android SDK | Platform 34 + Build-Tools | 命令行构建必须配置 `ANDROID_HOME` 或 `local.properties` 中的 `sdk.dir` |

> Gradle 8.2 及更早版本无法在 JDK 21 上运行，所以 wrapper 固定为 8.11.1。

### 方式 1：Android Studio（推荐）

1. 用 Android Studio 打开项目目录
2. Sync Gradle（首次会自动下载 Gradle 8.11.1）
3. Build → Build APK
4. 安装到手机

### 方式 2：命令行 Gradle

```bash
cd xiaomi-ai-devopt-hook
./gradlew assembleDebug
# APK 在 app/build/outputs/apk/debug/
```

若提示 `SDK location not found`，在项目根目录新建 `local.properties`：

```properties
sdk.dir=/path/to/Android/Sdk
```

### 依赖来源说明

Xposed API（`de.robv.android.xposed:api:82`）**不在 Maven Central**，官方仓库是
`api.xposed.info` 的根路径（注意没有 `/repo/` 这一段）。仓库配置写在 `settings.gradle`
的 `dependencyResolutionManagement` 里，并额外加了阿里云 jcenter 镜像作为冗余。
依赖用 `compileOnly` 声明，运行时由 LSPosed 提供，不会打进 APK。

## 安装使用

1. 安装编译好的 APK
2. 打开 LSPosed → 模块 → 找到「小爱隐藏功能解锁」
3. 启用模块，作用域勾选：
   - ✅ com.miui.voiceassist（超级小爱主程序）
   - ✅ com.miui.voiceassistProxy（代理启动器，可选）
4. 强制停止超级小爱，重新打开
5. 进入开发者选项 → LLM 设置 → 配置你的 OpenAI 兼容 API

## 配置说明

解锁后支持以下 OpenAI 兼容参数：

| 字段 | 说明 | 示例 |
|------|------|------|
| API Base URL | 接口地址（不含 /chat/completions） | `https://api.openai.com/v1` |
| API Key | 密钥 | `sk-xxxx` |
| Model Name | 模型名 | `gpt-4o` |
| Provider | 提供商标识 | `openai` |

兼容任何 OpenAI 格式的第三方 API（DeepSeek、Moonshot、通义千问、Ollama 等）。

## 技术细节

基于逆向分析发现的架构：

```
DeveloperOptionsActivity.onCreate
  ├─ ca1/a.isEnabled()    ← 门禁 1：开发者选项开关
  ├─ g1.isLogin()         ← 门禁 2：小米账号登录
  └─ DevOptionsScreen     ← UI 渲染

SettingsViewModel (eg, 138个方法)
  ├─ setApiKey(String)
  ├─ setOpenAIBaseUrl(String)
  ├─ setModelName(String)
  ├─ setLlmProvider(String)
  ├─ overwriteProfile(apiKey, modelName, openaiBaseUrl, llmProvider, ...)
  └─ saveCurrentAsProfile(name, apiKey, ...)

DataStore (SettingsDataStoreImpl)
  ├─ openai_base_url
  ├─ api_key
  ├─ model_name
  ├─ llm_provider
  └─ temperature
```

## 修复记录

| # | 问题 | 修复 |
|---|------|------|
| 1 | `XC_MethodReplacement.returnTrue()` 在 Xposed API 82 中并不存在 → **整个项目编译不过** | 统一改用 `XC_MethodReplacement.returnConstant(Boolean.TRUE)` |
| 2 | `settings.gradle` 里 `dependencyResolution {...}` 不是合法配置块，且完全没有依赖仓库 | 改为 `dependencyResolutionManagement` + `google()/mavenCentral()/api.xposed.info` |
| 3 | `AndroidManifest.xml` 仍带 `package="..."`，AGP 8.x 会直接构建失败 | 移除该属性，命名空间只由 `app/build.gradle` 的 `namespace` 声明 |
| 4 | `Class.forName(entry, …)` 传入的是 `com/xiaomi/xxx` 斜杠形式，异常被 `catch` 静默吞掉 → 动态扫描永远无效 | 补上 `entry.replace('/', '.')` |
| 5 | 每次 `onCreate` 都再 Hook 一次 `Activity.finish()`，Hook 回调不断叠加；且用 `lpparam.classLoader` 查 `android.app.Activity` | 全局只 Hook 一次，按实例类型 + 界面是否已显示来判断 |
| 6 | 动态扫描在 `handleLoadPackage` 阶段取 `currentApplication()`，此时通常为 null → 兜底逻辑从不执行 | 延后到 `Application.onCreate`，并放到后台线程，避免拖慢启动 |
| 7 | 登录门禁按 `com.xiaomi.voiceassistant.g1` 查找，但分析结论是默认包的 `g1` | 同时尝试默认包与带包名的两种形式 |
| 8 | README 让用户执行 `./gradlew`，但仓库里没有 wrapper | 补齐 `gradlew` / `gradlew.bat` / `gradle-wrapper.jar`（Gradle 8.11.1） |
| 9 | `compileOnly '…:api:82:sources'` 是源码包，放进编译 classpath 没有意义 | 移除 |

## 已知限制

- **finish 拦截的边界**：只在 `DeveloperOptionsActivity` 首次 `onResume`（界面真正显示）之前拦截
  `finish()`。界面正常显示之后，返回键和页面跳转都能正常工作；但如果门禁是在界面显示之后
  通过异步任务（例如延迟 Runnable）再调用 `finish()`，这种退出不会被拦住。
- **动态扫描是启发式**：它会把 `com.xiaomi.**` 下所有名字含 `login` / `logged` 的无参 boolean
  方法都改成返回 true，并且要加载成千上万个类，因此只在精确 Hook 失败时才触发。如果目标
  App 出现异常行为，建议把日志里命中的类名固化进 `hookKnownGates()` 候选列表，再把
  `ENABLE_DYNAMIC_SCAN` 改成 `false`。
- **门禁类名来自逆向分析**：`ca1.a.isEnabled()` / `g1.isLogin()` 的类名会随 App 版本变化，
  本仓库没有 App 样本，是否命中以真机日志为准。
- 本仓库已验证到「源码可编译 + Gradle 配置可解析 + 依赖可解析」；APK 最终效果需要真机验证。

## 调试日志

模块所有日志都带 `AiDevOpt` 前缀：

```bash
adb logcat -s LSPosed-Bridge | grep AiDevOpt
# 或
adb logcat | grep AiDevOpt
```

正常命中时会看到类似：

```
AiDevOpt | ✓ hook: ca1.a.isEnabled() → true
AiDevOpt | ✓ hook: g1.isLogin() → true
AiDevOpt | ✓ DeveloperOptionsActivity hooked
AiDevOpt | 精确 Hook 全部命中，跳过动态扫描
```

如果看到 `✗ 未能 Hook 门禁方法`，说明该版本 App 的混淆名变了，此时会触发动态扫描
（日志里会有 `动态扫描完成: 扫描 N 个类…`），把新类名补进 `hookKnownGates()` 的候选列表即可。

## 卸载

直接在 LSPosed 中禁用/卸载模块，然后卸载 APK 即可。
