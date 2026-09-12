# 小爱隐藏功能解锁 (Xiaomi AI DevOptions Hook)

LSPosed 模块。针对 **超级小爱 8.2.10.2222（`com.miui.voiceassist`）** 重新逆向后重写：
放行开发者选项门禁，并把第三方 OpenAI 兼容 API 配置写进小爱自己的 LLM 设置里。

> 上一版模块的 Hook 目标是老版本残留，装到 8.2.10.2222 上只能打开一个「扫码调试」页面。
> 本版所有符号名都在真实 APK 里逐个确认过。

## 逆向结论（8.2.10.2222 实测）

小米自家开发者选项由**两道门禁**控制：

| # | 符号 | 说明 |
|---|------|------|
| 1 | `ca1.a.a.isEnabled()` | 单例 `ca1.a` 的**实例方法**，读 MMKV 键 `dev_options_enabled`（默认 false） |
| 2 | `com.xiaomi.voiceassistant.g1.isLogin()` | 静态方法，内部是 `new eb1.c().isLogin()` |

调用点（决定了「只拦 finish 会白屏」这件事）：

- `AboutSettingsActivity`（关于页）在 `onResume()` / 长按 Logo 5 秒后的 `m0()` 里判断
  `isLogin() && isEnabled()`，通过才把 `key_dev_options` 这一项加进设置列表；点击该项才启动
  `DeveloperOptionsActivity`。
- `DeveloperOptionsActivity.onCreate()` 里是同一组判断，**失败就 `finish(); return;`** ——
  `setContentView()` 根本不会执行。所以单纯 hook `finish()` 只会得到一个空白页面，
  必须让门禁本身返回 true。

**该版本开发者选项页面里只有「扫码调试」**（`activity_developer_options` 仅绑定了返回键 +
`ScanDebugActivity`）。第三方 LLM 配置已经搬到 MiClaw / 智能体（osbot）那一层：

| 用途 | 符号 |
|------|------|
| 开发者状态开关 | `com.aios.osbot.ui.settings.q8` = `DevOptionState`（`getUnlocked()` / `setUnlocked(boolean)`） |
| 设置 ViewModel | `com.aios.osbot.ui.settings.eg`（`getDevOptionState()`） |
| LLM 设置界面 | `com.aios.osbot.store.debug.DevOptionsScreen`（Compose） |
| 实验室界面 | `com.aios.osbot.ui.settings.LabScreen` |
| 配置存储 | `qk.m0` = `CoreSettingsDataStore`：`llm_provider` / `api_key` / `model_name` / `openai_base_url` / `temperature` / `anthropic_base_url` |

## 模块做了三件事

1. **放行小米门禁**：`ca1.a.isEnabled()` → true、`g1.isLogin()` → true，并让 `clear()` 变空操作。
2. **状态一致**：hook `com.xiaomi.voiceassist.baselibrary.utils.g1.getBoolean()`，对
   `dev_options_enabled` 直接返回 true —— 避免「门禁放行了但界面开关还显示关闭」。
3. **放行 osbot 开发者状态**：`DevOptionState.getUnlocked()` → true、
   `setUnlocked(false)` → 强制 true，让智能体/MiClaw 里的开发者入口出现。

另外还会把下面这个配置文件里的第三方 API 参数**写进 osbot DataStore**。

## 配置第三方 API

配置文件名：`xiaoai_llm.conf`，位于 App 自己的 files 目录：

```
/data/data/com.miui.voiceassist/files/xiaoai_llm.conf
```

模块首次运行会**自动生成模板**。有 root 的话直接编辑（Termux 里）：

```bash
su -c "vi /data/data/com.miui.voiceassist/files/xiaoai_llm.conf"
su -c "am force-stop com.miui.voiceassist"   # 改完必须强制停止再打开
```

字段：

| 字段 | 说明 | 示例 |
|------|------|------|
| `provider` | 供应商标识 | `openai` / `anthropic` / `gemini` / `custom` |
| `base_url` | OpenAI 兼容地址，**不要**带 `/chat/completions` | `https://api.deepseek.com/v1` |
| `api_key` | 密钥 | `sk-xxxx` |
| `model_name` | 模型名 | `deepseek-chat` |
| `provider_id` | 可选，网关的 `X-Model-Provider-Id` | |
| `anthropic_base_url` | 可选，Anthropic 兼容地址 | |

示例（DeepSeek）：

```ini
provider=openai
base_url=https://api.deepseek.com/v1
api_key=sk-你的密钥
model_name=deepseek-chat
```

写入动作通过 Kotlin suspend setter 完成（模块用动态代理构造 `Continuation`，
因此不依赖 kotlin 运行时）。

## 环境要求

- 已 Root（SukiSU / Magisk）
- 已安装 LSPosed（IT 7846+）
- 超级小爱 `com.miui.voiceassist` 8.2.10.2222（其他版本符号名可能不同）

## 编译方法

| 组件 | 版本 |
|------|------|
| JDK | 17 或 21 |
| Gradle | 8.11.1（已带 wrapper） |
| AGP | 8.3.2（Android Studio Iguana / 2023.2.1 以上） |
| Android SDK | Platform 34 + Build-Tools |

```bash
./gradlew assembleDebug
# APK 在 app/build/outputs/apk/debug/
```

推送到 `main` 会自动触发 GitHub Actions 构建并上传 APK 产物。

### 依赖来源

Xposed API（`de.robv.android.xposed:api:82`）不在 Maven Central，官方仓库是
`api.xposed.info` 根路径（没有 `/repo/`），备用阿里云 jcenter 镜像。依赖用 `compileOnly`
声明，运行时由 LSPosed 提供。

## 安装使用

1. 安装 APK
2. LSPosed → 模块 → 启用「小爱隐藏功能解锁」，作用域勾选 `com.miui.voiceassist`
3. 强制停止超级小爱，重新打开
4. 「关于」页面 → 应出现「开发者选项」入口
5. 配置第三方 API：见上节 `xiaoai_llm.conf`

## 调试日志

```bash
adb logcat | grep AiDevOpt
```

关键行：

```
AiDevOpt | ✓ hook ca1.a.isEnabled() → true
AiDevOpt | ✓ hook com.xiaomi.voiceassistant.g1.isLogin() → true
AiDevOpt | ✓ hook DevOptionState.getUnlocked() → true
AiDevOpt | ✓ hook qk.m0 构造（用于写入 LLM 配置）
AiDevOpt | [conf] 已生成配置模板: /data/data/com.miui.voiceassist/files/xiaoai_llm.conf
AiDevOpt | [conf] 已写入 api_key = sk-1***ab
AiDevOpt | → DeveloperOptionsActivity.onCreate 进入（门禁已被放行）
AiDevOpt | ✓ setContentView(2131558469) 执行 —— 页面正常渲染
```

看到 `✗ 未找到 …` 说明该 App 版本的混淆名变了，需要重新确认符号（见下）。

## 修复记录

| # | 问题 | 处理 |
|---|------|------|
| 1 | `XC_MethodReplacement.returnTrue()` 在 Xposed API 82 中不存在 → 项目无法编译 | 改用 `returnConstant(Boolean.TRUE)` |
| 2 | `settings.gradle` 用了不存在的 `dependencyResolution` 块，且无任何依赖仓库 | 改为 `dependencyResolutionManagement` + 仓库 |
| 3 | `AndroidManifest.xml` 残留 `package=` 属性（AGP 8.x 直接报错） | 移除，命名空间只由 `namespace` 声明 |
| 4 | `Class.forName()` 传入斜杠类名，异常被静默吞掉 | 补 `replace('/', '.')` |
| 5 | 每次 onCreate 都重复 hook `Activity.finish()`，回调不断叠加 | 已整体移除「拦 finish」方案（会导致白屏，见上文） |
| 6 | 动态扫描在 `Application` 尚不存在时执行，兜底从不生效 | 已移除该启发式扫描（有明确符号后不再需要） |
| 7 | 门禁类名沿用老版本（`ca1.a` 静态方法、`com.xiaomi.voiceassistant.g1` 存疑） | 按 8.2.10.2222 实测结果重写，并补上 osbot 层 |
| 8 | 仓库缺少 Gradle wrapper，README 却让人跑 `./gradlew` | 补齐 wrapper（8.11.1） |
| 9 | 缺少 CI | 新增 GitHub Actions，构建并上传 debug APK |

## 已知限制

- **符号名会随版本变化**：`ca1.a`、`qk.m0`、`com.aios.osbot.ui.settings.q8` 都是混淆名。
  App 升级后需要重新确认；`✗ 未找到` 日志会直接指出是哪一类失效。
- **第三方 LLM 界面本身未解锁**：它属于 osbot 内部页面（`DevOptionsScreen` /
  `LabScreen`），本模块解锁的是它的状态开关（`DevOptionState`）。未在其宿主界面里
  找到稳定入口，因此额外提供配置文件写入的方式，保证参数能真正落到 DataStore。
- **只验证到「可编译 + 配置可解析」**：真机行为需要以日志为准，仓库内没有 App 样本。
- `provider_id` 等可选字段按需填写，留空则不写入。

## 卸载

在 LSPosed 中禁用/卸载模块，然后卸载 APK。若之前写过配置，删除
`/data/data/com.miui.voiceassist/files/xiaoai_llm.conf` 即可。
