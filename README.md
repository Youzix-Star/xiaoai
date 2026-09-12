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
| 配置存储（智能体层） | `qk.m0` = `CoreSettingsDataStore`：`llm_provider` / `api_key` / `model_name` / `openai_base_url` / `temperature` / `anthropic_base_url` |
| **配置存储（语音层）** | `vu.q` = `VoiceSettingsDataStore`：`voice_provider` / `voice_api_base_url` / `voice_api_key` / `voice_model_name` —— **语音对话用的是这一套，不是上面那套** |
| **系统提示词存储** | `vu.q` = `VoiceSettingsDataStore`：`voice_system_prompt` / `voice_custom_system_prompt`（`setVoiceSystemPrompt` / `setVoiceCustomSystemPrompt`，同为 suspend setter） |
| 提示词文件覆盖开关 | `qk.m0.setPromptFileOverrideEnabled`，键 `prompt_file_override_enabled` |
| Agent 提示词 | `assets/agents/<agent-id>/prompt.md`（对应 `AgentDefinition.promptFile` 字段 `prompt_file`） |

## 模块做了五件事

1. **放行小米门禁**：`ca1.a.isEnabled()` → true、`g1.isLogin()` → true，并让 `clear()` 变空操作。
2. **状态一致**：hook `com.xiaomi.voiceassist.baselibrary.utils.g1.getBoolean()`，对
   `dev_options_enabled` 直接返回 true —— 避免「门禁放行了但界面开关还显示关闭」。
3. **放行 osbot 开发者状态**：`DevOptionState.getUnlocked()` → true、
   `setUnlocked(false)` → 强制 true，让智能体/MiClaw 里的开发者入口出现。

4. **写入系统提示词**：把配置里的提示词通过 `VoiceSettingsDataStore.setVoiceSystemPrompt()` /
   `setVoiceCustomSystemPrompt()` 写进小爱的提示词存储，并可开启 Agent 提示词文件覆盖。

5. **内置配置悬浮窗**：在小爱进程里挂一个悬浮球，点开即可改上面所有配置，
   保存后立即生效，**完全不依赖小爱的任何设置入口**。

以上写入都发生在 osbot 两个 DataStore 构造完成时（通过 hook 构造函数拿到实例）。

## 悬浮窗（改配置推荐用这个）

模块会在小爱进程里挂一个圆形悬浮球（显示 `AI`），**不需要进小爱的任何设置页面**：

| 操作 | 效果 |
|------|------|
| 拖动 | 移动悬浮球位置 |
| 点按 | 打开配置面板 |
| 长按 | 临时隐藏（本次进程内不再显示，重启小爱恢复） |

面板里可以直接改：API 地址、API Key、模型名、provider、provider_id、系统提示词、
以及「按文件覆盖 Agent 提示词」开关。点 **保存并生效** 后会：

1. 回写 `files/xiaoai_llm.conf`（保留你自己的注释）
2. **立刻**通过已缓存的 DataStore 实例写进小爱，**不用强制停止/重启**

前置条件：小爱要有悬浮窗权限。本机实测 `com.miui.voiceassist` 的
`SYSTEM_ALERT_WINDOW` 已是 `allow`（系统应用自带），无需额外授权。

不想要悬浮球就在配置里写：

```ini
floating_button=false
```

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

### 两层配置（重要）

小爱有两套并行的 LLM 配置，**只写一套是不生效的**：

| 层 | 存储 | 键 | 谁在用 |
|----|------|----|--------|
| 智能体层 | `CoreSettingsDataStore` | `api_key` / `openai_base_url` / `model_name` / `llm_provider` | MiClaw / 智能体链路（`com.aios.osbot.memory.claw...LlmService`） |
| 语音层 | `VoiceSettingsDataStore` | `voice_api_key` / `voice_api_base_url` / `voice_model_name` / `voice_provider` | 语音对话链路 |

模块保存时会**把同一组值写进两层**（语音层可用 `voice_*` 键单独覆盖）：

```ini
# 通用值 → 同时写两层
provider=openai
base_url=https://api.deepseek.com
api_key=sk-xxx
model_name=deepseek-chat

# 可选：只覆盖语音层
# voice_provider=
# voice_api_base_url=
# voice_api_key=
# voice_model_name=
```

每次写入后模块会**回读校验**，日志里能看到两层各自的实际值：

```
[conf] 已写入 setVoiceApiKey ← api_key = sk-f***f0
[conf] 回读 语音层 provider = openai
[conf] 回读 语音层 base_url = https://api.deepseek.com
[conf] 回读 智能体层 model_name = deepseek-chat
```

写入动作通过 Kotlin suspend setter 完成（模块用动态代理构造 `Continuation`，
因此不依赖 kotlin 运行时）。

## 改系统提示词

这版本系统提示词存在 `VoiceSettingsDataStore`（混淆名 `vu.q`）里，键是
`voice_system_prompt` 和 `voice_custom_system_prompt`，模块直接用它的 suspend setter 写入，
不需要动 App 界面。

在同一个 `xiaoai_llm.conf` 里加：

```ini
# 主系统提示词（支持 \n 换行）
system_prompt=你是我的私人助理。回答前先给结论，语气简短直接。\n不知道就说不知道。

# 提示词太长就写到文件里，相对路径按配置文件所在目录解析
system_prompt_file=my_prompt.txt

# 自定义系统提示词（voice_custom_system_prompt）
custom_system_prompt=

# 按文件覆盖 Agent 提示词（assets/agents/<id>/prompt.md）
prompt_override=true
```

改完照样要 `su -c "am force-stop com.miui.voiceassist"` 再打开。

说明：

- `system_prompt` 写的是**语音/对话主链路**的系统提示词；`custom_system_prompt` 是它的自定义覆盖项，
  两者都在同一存储里，按需二选一或都写。
- `prompt_override` 对应 App 自己的开关 `prompt_file_override_enabled`，开启后 Agent 的提示词
  以文件为准（Agent 定义里 `prompt_file` 指向的 `prompt.md`）。
- 想要多行提示词，除了 `\n` 和 `*_file`，也可以直接把换行写进文件里再用 `*_file` 引用。

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

## 调试日志在哪看

三个地方随便挑，内容一样：

**1. 悬浮窗里直接看（最省事，不用 adb / 不用开 Termux）**

点悬浮球 → 面板右上角 **日志** → 显示最近 500 行；**刷新** 按钮重新拉取。
日志窗口还会显示日志文件路径。

**2. LSPosed 管理器 → 日志**

按模块名过滤即可，手机上就能看。

**3. Termux / 电脑上的 logcat**

```bash
# 本机（有 root，在 Termux 里）
su -c "/system/bin/logcat -v time" | grep AiDevOpt

# 只看模块相关 tag
su -c "/system/bin/logcat -s LSPosed-Bridge:I" | grep AiDevOpt

# 电脑上
adb logcat | grep AiDevOpt
```

**4. 日志文件（可以直接 cat / 发给我）**

```
/data/data/com.miui.voiceassist/files/AiDevOpt.log
```

```bash
su -c "cat /data/data/com.miui.voiceassist/files/AiDevOpt.log"
```

上限 256KB，超出自动滚动；内存里同时保留最近 500 行供悬浮窗展示。

### 关键日志

```
AiDevOpt | ======== 注入 com.miui.voiceassist ========
AiDevOpt | ✓ hook ca1.a.isEnabled() → true
AiDevOpt | ✓ hook com.xiaomi.voiceassistant.g1.isLogin() → true
AiDevOpt | ✓ hook DevOptionState.getUnlocked() → true
AiDevOpt | ✓ hook qk.m0 构造（用于写入 LLM 配置）
AiDevOpt | ✓ hook vu.q 构造（用于写入系统提示词）
AiDevOpt | ✓ 已注册配置悬浮窗
AiDevOpt | ✓ 配置悬浮球已显示（长按可隐藏）
AiDevOpt | [conf] 已生成配置模板: /data/data/com.miui.voiceassist/files/xiaoai_llm.conf
AiDevOpt | [conf] 已写入 api_key = sk-1***ab
AiDevOpt | [conf] 已写入 system_prompt = 你是我的私人助理。回答前先给结论…(48 字符)
AiDevOpt | → DeveloperOptionsActivity.onCreate 进入（门禁已被放行）
AiDevOpt | ✓ setContentView(2131558469) 执行 —— 页面正常渲染
```

看到 `✗ 未找到 …` 说明该 App 版本的混淆名变了，那一行会指出是哪一类符号失效。

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
| 10 | `findAndHookMethod(clazz, "setContentView", ...)` 对子类做 **exact 查找**，而该方法继承自 `Activity` → 抛 `NoSuchMethodError` 并**冲出 `handleLoadPackage`**，导致后面所有 hook（含悬浮窗）全部没注册 | 改为 hook `Activity.setContentView` 再按实例类型过滤；并给每个 hook 步骤加独立 try/catch，一步失败不再拖垮整串 |
| 11 | `Diag.init()` 之前的日志只进内存和 logcat，日志文件里缺了最关键的 hook 结果行 | `init()` 时把已缓冲的日志补写进文件 |
| 12 | CI 每次重新生成 debug keystore，产物签名每次都不同，升级安装会签名冲突 | 工作流固定并缓存 `~/.android/debug.keystore`，并打印签名指纹 |
| 13 | **只写了智能体层的 `api_key` 那套键**，漏了语音层的 `voice_api_key` / `voice_api_base_url` / `voice_model_name` / `voice_provider`，导致语音对话完全不生效（配置有落盘，但没被语音链路读取） | 同一组值同时写入两层，语音层支持 `voice_*` 覆盖，并加回读校验日志 |

## 已知限制

- **符号名会随版本变化**：`ca1.a`、`qk.m0`、`com.aios.osbot.ui.settings.q8` 都是混淆名。
  App 升级后需要重新确认；`✗ 未找到` 日志会直接指出是哪一类失效。
- **第三方 LLM 界面本身未解锁**：它属于 osbot 内部页面（`DevOptionsScreen` /
  `LabScreen`），本模块解锁的是它的状态开关（`DevOptionState`）。未在其宿主界面里
  找到稳定入口，因此额外提供配置文件写入的方式，保证参数能真正落到 DataStore。
- **悬浮窗依赖 App 的悬浮窗权限**：本机小爱已有该权限；若某些 ROM 把它收回，
  悬浮球会显示失败（日志有 `✗ 悬浮球显示失败`），此时改用 `xiaoai_llm.conf` 手改仍然可用。
- **只验证到「可编译 + 配置可解析」**：真机行为需要以日志为准，仓库内没有 App 样本。
- `provider_id` 等可选字段按需填写，留空则不写入；提示词同理，留空即保持 App 原值。
- **两层配置的边界**：智能体层（`api_key` 等）作用于 MiClaw / 智能体链路；语音层（`voice_api_key` 等）
  作用于语音对话。如果配完仍无第三方 API 流量，说明你测试的那条链路是**云端（小米服务端）完成**的，
  本地配置改不到它 —— 此时用智能体/MiClaw 链路测试，或给模块加 HTTP 层抓取来定位。
- **提示词的生效范围**：`voice_system_prompt` 作用于语音对话主链路；各 Agent（`assets/agents/*`）
  还有自己的 `prompt.md`，需要配 `prompt_override` 才会被文件覆盖。

## 卸载

在 LSPosed 中禁用/卸载模块，然后卸载 APK。若之前写过配置，删除
`/data/data/com.miui.voiceassist/files/xiaoai_llm.conf` 即可。
