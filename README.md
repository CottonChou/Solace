<div align="center">

<img src="docs/media/logo.png" alt="Solace" width="120" />

# Solace · 小满

**说一句话，让手机替你把事办完。**

Solace 是一个跑在你手机上的 Android 私人助理：它在后台替你操作 App 和网页，你的屏幕始终归你自己用。带自己的模型 API Key，没有云端服务，数据不出手机。

<a href="https://github.com/CottonChou/Solace/releases/latest"><img src="https://img.shields.io/github/v/release/CottonChou/Solace?include_prereleases&label=version" alt="version" /></a>
<img src="https://img.shields.io/badge/Android-13%2B-3DDC84" alt="android" />
<img src="https://img.shields.io/badge/license-Apache--2.0-lightgrey" alt="license" />

[下载](https://github.com/CottonChou/Solace/releases/latest) · [开始用](#开始用) · [安全设计](#安全设计) · [常见问题](#常见问题) · [参与建设](#参与建设)

简体中文 | [English](README.en.md)

</div>

## 为什么叫小满

小满是二十四节气之一，意思是「小得盈满」：麦子开始灌浆，还没有完全成熟。它很适合这个项目的现在——一个已经能干不少事、但仍在快速成长的私人助理。

## 它能做什么

Solace 的能力不靠逐个 App 适配。它像一个真正坐在你手机旁边的人：看界面、理解内容、点按和输入，把任务推进到结束。

| 能力 | 说明 |
|---|---|
| **App 操作** | 在一块独立的后台屏里打开任意 App，读界面、点击、输入、滚动；长聊天记录和长列表可以逐屏读完。读不出控件树的 App 会退回到随 APK 自带的文字识别；配了看图模型后还能根据截图定位按钮。 |
| **网页操作** | 用内置浏览器以电脑版方式打开网站，真实点击和输入；登录态保留，支持下载和上传，会对连续访问限速，识别风控页后交还给你处理。 |
| **屏幕分享** | 悬浮胶囊常驻侧边，双击可以把当前屏幕交给 Solace，例如「这句怎么回」或「帮我记下来」。 |
| **定时任务** | 一次性提醒、每天早上查天气这类周期任务，以及「下雨才告诉我」这类条件任务；结果回到原对话。 |
| **本地记忆** | 记录你明确说过的偏好和做法，可以查看、修改、删除；密码、验证码、卡号等敏感信息不入记忆。 |
| **Linux 沙箱** | 每个对话有独立工作区，可跑 Python、Node 和 shell，默认断网，仅限 arm64 设备。 |
| **随时接管** | 登录、验证码、付款这类只有你能做的事会暂停任务交还给你；你处理完，Solace 先核实现场再继续。 |

## 安全设计

自动化 touching 的是你的真实账号和真实数据，所以这些规则是设计出来的，不是事后补的：

- **银行与支付硬拒绝**。银行、证券、支付类 App 在引擎层直接拒绝操作，没有开关。
- **通讯写入逐次确认**。在微信、WhatsApp 这类 App 里发送或修改内容，每一次都要你确认。
- **系统设置默认只读**。涉及系统包的操作默认只能看不能改；确要修改时授权只在当次任务内有效。
- **付款永远由你亲手完成**。判定为付款的动作会把控制权交还给你，Solace 不代付。
- **锁屏不泄露任务内容**。锁屏上的任务通知只显示固定标题。
- **敏感数据不进记忆**。验证码、卡号、证件号一类内容被过滤器挡在记忆之外。
- **崩溃不无限重试**。执行引擎连续异常会进入安全模式并保留最后一次可用配置，等你决定下一步。

## 开始用

### 要求

- Android 11 起可安装；执行任务需要 Android 13 及以上，Android 14+ 体验更好。
- Linux 沙箱仅限 arm64 设备。
- 一个支持工具调用的大模型 API Key；本地或局域网模型也可以。

### 步骤

1. 从 [Releases](https://github.com/CottonChou/Solace/releases/latest) 下载 APK 安装。
2. 首次启动按向导添加模型服务商。

<!-- generated:providers:start -->
<!-- 这一段由 ./gradlew updateReadme 生成，不要手改 -->
内置 DeepSeek、通义千问、Kimi、智谱、OpenRouter、OpenCode Go、Anthropic、Gemini 等服务商预设；其他服务商选「自定义」，OpenAI 兼容、Anthropic、Gemini 接口都能接。
<!-- generated:providers:end -->

3. 按向导完成无线调试配对。Solace 会启动内置手机助手并自行打开无障碍；只想用网页、文件和聊天可以暂时跳过。
4. 在对话里试着说：

- 「总结一下某个微信群今天都聊了什么」
- 「打开某个招聘网站，帮我筛出合适的岗位并打招呼」
- 「每天早上 8 点看看天气，下雨才提醒我」

## 数据与隐私

- **没有 Solace 服务器**。对话内容、屏幕数据和截图只发给你自己配置的模型服务商。
- **本机加密**。API Key、记忆和用户资料用 Android Keystore 加密；对话和运行记录留在本机。
- **更新检查只问 GitHub Releases**，不收集任何信息。
- **沙箱默认断网**；内置浏览器只访问你让它去的网站。

## 免责声明

自动化操作第三方 App 和网站可能违反对方协议，存在账号被限制的风险。请自行判断并承担使用风险；涉及金钱、账号和重要消息时，建议保持在询问模式并检查结果。

## 常见问题

**任务失败后重试有风险吗？**

Solace 记录每次失败的原因，连续异常会进入安全模式停止重试，不会反复执行同一个危险动作。

**为什么系统设置操作被拒绝？**

系统相关包默认只读，这是内建的安全策略。确实需要修改时，在当次任务里按提示授权；任务结束授权即失效。

**任务把 App 移到了主屏，正常吗？**

这是接管流程。需要登录、输验证码或付款时，Solace 把控制权还给你；处理完交还后它会先确认当前页面再继续。

**ColorOS 上任务总被杀怎么办？**

在「系统能力自检」里打开 OPPO / ColorOS 的三个后台保活入口（电池、自启动、后台弹出）。这些是厂商页面，Solace 无法代你操作，需要手动开启。

**能用局域网里的模型吗？**

可以。添加自定义服务商填局域网地址即可；不加密地址会提示风险，能用 HTTPS 尽量用。

**换手机了数据怎么办？**

对话、记忆和 API Key 都在本机且加密，不随应用备份迁移；换机后需要重新配置。应用刻意不申请云备份权限，这是隐私设计的一部分。

## 参与建设

欢迎报告问题、补充知识文件和提交代码：

| 模块 | 内容 |
|---|---|
| `core/` | 主循环、工具、提示词、知识与技能 |
| `app/` | Android 宿主、界面、通知、接管与安全策略 |
| `harness-engine/` | 第二执行引擎的桥接、崩溃策略与资源预算 |

补知识不需要写代码：给 App 或网站加一份 Markdown 说明放进 `core/src/main/resources/knowledge/`，格式见 [`docs/knowledge-format.md`](docs/knowledge-format.md)。

提交 Issue 请带手机型号、Android 版本、Solace 版本和复现步骤；截图先遮住隐私内容。提交 PR 前请运行单元测试。

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

## 许可

Solace 以 [Apache License 2.0](LICENSE) 发布。第三方组件见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
