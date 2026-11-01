<div align="center">

<img src="docs/media/logo.png" alt="Solace" width="120" />

# Solace · 小满

**说一句话，让手机替你把事办完。**

Solace 在后台替你操作 App 和网页，你的屏幕始终归你自己用。它可以选择两种执行引擎，并且对银行类 App、付款页和系统设置有明确的拦截与确认策略。

An Android personal assistant that turns one sentence into a finished task. Solace works in the background, keeps your screen free, supports two execution engines, and adds explicit safety gates before anything important happens.

<a href="https://github.com/CottonChou/Solace/releases/latest"><img src="https://img.shields.io/github/v/release/CottonChou/Solace?include_prereleases&label=version" alt="version" /></a>
<img src="https://img.shields.io/badge/engine-Solace%20%2B%20DSH-7C5CFF" alt="engines" />
<img src="https://img.shields.io/badge/Android-13%2B-3DDC84" alt="android" />
<img src="https://img.shields.io/badge/license-Apache--2.0-lightgrey" alt="license" />

[下载](https://github.com/CottonChou/Solace/releases/latest) · [开始用](#开始用) · [两种引擎](#两种执行引擎) · [与原项目的差别](#solace-与原项目的差别) · [参与建设](#参与建设)

简体中文 | [English](README.en.md)

</div>

> English: Solace is an on-device Android agent. It operates apps on a hidden background screen, drives a built-in browser, runs scripts in a local Linux sandbox, and can execute a task with either the Solace engine or the DeepSeek Harness engine. Bring your own model API key; there is no Solace server. See [README.en.md](README.en.md) for the full English document.

## 为什么叫小满

小满是二十四节气之一，意思是「小得盈满」：麦子开始灌浆，还没有完全成熟。它很适合这个项目的现在——一个已经能干不少事、但仍在快速成长的私人助理。

## 它能做什么

Solace 的能力不靠逐个 App 适配。它像一个真正坐在你手机旁边的人：看界面、理解内容、点按和输入，把任务推进到结束。

| 能力 | 说明 |
|---|---|
| **App 操作** | 在一块独立的后台屏里打开任意 App，读界面、点击、输入、滚动；长聊天记录和长列表可以逐屏读完。读不出控件树的 App 会退回到随 APK 自带的文字识别；配了看图模型后还能根据截图定位按钮。 |
| **网页操作** | 用内置浏览器以电脑版方式打开网站，真实点击和输入；登录态保留，支持下载和上传，会对连续访问限速，识别风控页后交还给你处理。 |
| **屏幕分享** | 悬浮胶囊常驻侧边，双击可以把当前屏幕交给 Solace，例如「这句怎么回」或「帮我记下来」。 |
| **定时任务** | 支持一次性提醒、每天检查天气这类周期任务，以及「下雨才告诉我」这类条件任务；结果会回到原对话。 |
| **本地记忆** | 记录你明确说过的偏好和做法，可以查看、修改、删除；密码、验证码、卡号等敏感信息不入记忆。 |
| **Linux 沙箱** | 每个对话有独立工作区，可跑 Python、Node 和 shell，默认断网，只在 arm64 设备上可用。 |
| **随时接管** | 登录、验证码、付款这类只有你能做的事会提醒你接管；你处理完交还后，Solace 会先核实再继续。 |

## 两种执行引擎

一个对话从第一条消息开始就固定使用一种引擎，切换后只影响新对话。

| | **Solace 引擎**（默认） | **DeepSeek Harness（DSH）** |
|---|---|---|
| 执行方式 | Solace 主循环直接驱动手机、浏览器和沙箱 | DSH 引擎独立运行，经桥接层操作设备 |
| 适合场景 | 日常任务，行为最稳定 | 想测试另一套 Agent 执行栈，或对比两个引擎的表现 |
| 安全策略 | 内建的动作风险分级与付款确认 | 经过宿主级 HostSafetyPolicy，再进入 DSH 自身审批 |
| 接管 | 通知接管，交还后继续 | 支持 `hand_to_user` 真实接管，10 分钟无人处理则按超时结束 |

## Solace 与原项目的差别

Solace 基于 Apache-2.0 的 Solace 项目和 DeepSeek Harness 构建合并而成。以下能力是 Solace 侧新增或重新实现的，而不是原项目的简单改名。

### 宿主级安全闸门

DSH 的每次设备能力调用先经过 `HostSafetyPolicy`，再进入引擎自己的审批：

- 银行类 App 直接硬拒绝，不提供开关。
- 通讯类 App 的写入操作每次要求确认。
- 系统设置相关包默认只读，确实需要时按任务级授权解锁。
- 任务级授权不会延续到下一个任务；新任务开始时旧授权自动失效。

### 真实任务接管

`hand_to_user` 不再只是提示，而是一个完整的接管流程：任务暂停、发出带跳转的通知、App 移到主屏、你处理完后交还，Solace 核实现场再继续。超过 10 分钟无人处理，任务按超时收尾并如实汇报。

### 引擎崩溃分类

DSH 引擎启动失败、早期崩溃和后期崩溃会分别记录。连续异常两次进入 SafeMode，保留最后一次可用配置；宿主可以据此提示你回退到 Solace 引擎，而不是反复重启一个注定失败的后端。

### ColorOS 保活入口

针对 OPPO / OnePlus / realme 提供三个入口：后台运行与电池、自启动、后台弹出界面。入口按候选页面顺序尝试，失败时回退到应用详情页；返回 Solace 后会重新检查状态。

### Node 资源预算

DSH 引擎限制 Node 的内存与线程预算，按设备分四档。温度偏高时进入轻量档，降低并发并拉长启动间隔，避免后台任务把手机拖热。

### 通知加固

外部引擎任务的通知槽位固定，进度、提醒和输入分开，不会互相顶掉；锁屏只显示固定标题，不泄露任务内容；审批通知 5 分钟无人处理按拒绝收尾；多个任务合并为分组摘要。

### 其他变化

- 全新图标与「小满」视觉身份。
- DSH 任务在任务中心、胶囊和通知里统一展示。
- 新增系统能力自检页，集中管理无障碍、通知使用权、文件、短信、联系人、日历等授权。

## 开始用

### 要求

- Android 11 起可以安装；执行任务需要 Android 13 及以上，Android 14 及以上体验更好。
- Linux 沙箱仅支持 arm64 设备。
- 一个支持工具调用的大模型 API Key；也支持接入本地或局域网模型。

### 步骤

1. 从 [Releases](https://github.com/CottonChou/Solace/releases/latest) 下载 APK 并安装。
2. 首次启动按向导添加模型；预置了 DeepSeek、通义千问、Kimi、智谱、OpenRouter、Anthropic、Gemini 等，自定义接口支持 OpenAI 兼容、Anthropic 和 Gemini 协议。

<!-- generated:providers:start -->
<!-- 这一段由 ./gradlew updateReadme 生成，不要手改 -->
内置 DeepSeek、通义千问、Kimi、智谱、OpenRouter、OpenCode Go、Anthropic、Gemini 等服务商预设；其他服务商选「自定义」，OpenAI 兼容、Anthropic、Gemini 接口都能接。
<!-- generated:providers:end -->

3. 按向导完成无线调试配对，Solace 会启动内置手机助手并自行打开无障碍。只想用网页、文件和聊天时可以暂时跳过。
4. 在新对话里选择执行引擎，然后试着说：

- 「总结一下某个微信群今天都聊了什么」
- 「打开某个招聘网站，帮我筛出合适的岗位并打招呼」
- 「每天早上 8 点看看天气，下雨才提醒我」

## 数据与隐私

- 没有 Solace 服务器。对话内容、屏幕数据和截图只发送给你自己配置的模型服务商。
- API Key、记忆与用户资料使用 Android Keystore 加密保存，对话和运行记录留在本机。
- 更新检查只访问 GitHub Releases。
- 沙箱默认断网；内置浏览器只访问你让它去的网站。

## 免责声明

自动化操作第三方 App 和网站可能违反对方协议，存在账号被限制的风险。请自行判断并承担使用风险；涉及金钱、账号和重要消息时，建议保持在询问模式并检查结果。

## 常见问题

**DSH 引擎启动失败怎么办？**

Solace 会记录崩溃类型并进入 SafeMode，不会再无限重试。可以在新对话里切回 Solace 引擎继续任务，之后按崩溃提示排查模型配置或设备资源。

**为什么系统设置操作被拒绝？**

系统相关包默认只读，这是宿主级安全策略。确实需要修改系统状态时，在当次任务里按提示授权；任务结束后授权不会保留。

**任务把 App 移到了主屏，这正常吗？**

这是接管流程。需要登录、输验证码或付款时，Solace 会把控制权还给你，处理完交还后它会先确认当前页面再继续。

**ColorOS 上任务总被杀怎么办？**

在「系统能力自检」里打开 OPPO / ColorOS 后台保活对应的三个入口。这些是厂商私有页面，Solace 无法读取实际开关，请跳转后手动开启。

**能使用局域网里的模型吗？**

可以。添加自定义服务商并填入局域网地址；不加密地址会有安全提示，能用 HTTPS 就尽量用 HTTPS。

## 参与建设

欢迎报告问题、补充知识文件和提交代码。仓库按四个主要区域组织：

| 模块 | 内容 |
|---|---|
| `core/` | 主循环、工具、提示词、知识与技能 |
| `app/` | Android 宿主、界面、通知、接管与安全策略 |
| `dsh-engine/` | DeepSeek Harness 引擎桥接、崩溃策略与资源预算 |
| `backend/`（在 `app` 内） | 执行后端选择、DSH 设备桥与审批桥 |

补知识不需要写代码：给 App 或网站加一份 Markdown 说明，放在 `core/src/main/resources/knowledge/`，格式见 [`docs/knowledge-format.md`](docs/knowledge-format.md)。

提交 Issue 时请带手机型号、Android 版本、Solace 版本和可复现步骤；截图请先遮住隐私内容。提交 PR 前请运行单元测试。

<!-- generated:knowledge:start -->
<!-- 这一段由 ./gradlew updateReadme 生成，不要手改 -->
| 类别 | 内置 |
|---|---|
| **App** | Android 设置、百家号、哔哩哔哩、BOSS直聘、得到、抖音、飞书、Gmail、即刻、快手、领英、QQ邮箱、今日头条、微信、微博、闲鱼、小红书、知乎 |
| **网站** | 哔哩哔哩、豆瓣、抖音、Instagram、百度贴吧、微博、搜狗微信、小红书、小宇宙、YouTube、知乎、BOSS直聘 |
| **通用做法**（技能） | 把内容整理成文字、处理 PDF 文档、查清一件事、消息与邮件 |
<!-- generated:knowledge:end -->

## 开发者

```bash
./gradlew :app:assembleDebug                  # 调试包
./gradlew :app:assemblePerf                   # 与发布版一致的预编译包，用调试签名
./gradlew :core:test :app:testDebugUnitTest   # 单元测试
```

调试包不做预编译，界面明显更慢，不要用它判断流畅度。架构说明见 [`docs/architecture.md`](docs/architecture.md)，路线与已知问题见 [`docs/roadmap.md`](docs/roadmap.md)。

## 致谢与许可

Solace 依靠两个开源项目才存在，感谢它们的作者：

- [Solace](https://github.com/wangzan101/solace) 提供了完整的 Android 私人助理基础。
- [DeepSeek Harness](https://github.com/jackwener/DeepseekHarness-Android) 提供了另一套可嵌入的执行引擎设计。

Solace 以 [Apache License 2.0](LICENSE) 发布；原作品版权与本次修改范围记录在 [NOTICE](NOTICE)。第三方组件见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
