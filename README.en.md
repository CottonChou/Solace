<div align="center">

<img src="docs/media/logo.png" alt="Solace" width="120" />

# Solace

**One sentence, a finished task, on your own phone.**

Solace operates apps and websites in the background while your screen stays yours. It supports two execution engines and applies explicit safety gates before banks, payments and system settings are touched.

<a href="https://github.com/CottonChou/Solace/releases/latest"><img src="https://img.shields.io/github/v/release/CottonChou/Solace?include_prereleases&label=version" alt="version" /></a>
<img src="https://img.shields.io/badge/engine-Solace%20%2B%20DSH-7C5CFF" alt="engines" />
<img src="https://img.shields.io/badge/Android-13%2B-3DDC84" alt="android" />
<img src="https://img.shields.io/badge/license-Apache--2.0-lightgrey" alt="license" />

[Download](https://github.com/CottonChou/Solace/releases/latest) · [Get started](#get-started) · [Two engines](#two-execution-engines) · [How Solace differs](#how-solace-differs-from-the-original-projects) · [Contributing](#contributing)

[简体中文](README.md) | English

</div>

Solace is an on-device Android agent. It reads the screen, understands content, taps and types like a person, and can run a task with either the Solace engine or the DeepSeek Harness engine. Bring your own model API key; there is no Solace server. The app UI and this document are available in Chinese and English.

## Why "Solace"

The name is the intent: the phone should absorb the busywork, so what is left is a bit more room and a bit less noise. The project is deliberately young — useful today, still growing quickly.

## What it can do

| Capability | What it means |
|---|---|
| **App control** | Opens any installed app on a separate background screen and reads, taps, types and scrolls there. Long chats and lists are read screen by screen. Apps with unreadable controls fall back to bundled text recognition; a vision-capable model adds screenshot grounding. |
| **Web control** | Opens sites in desktop mode in the built-in browser, with real clicks and keyboard input. Logins persist, downloads and uploads work, visits are paced, and anti-bot pages are handed back to you instead of forced through. |
| **Current screen** | A floating capsule stays at the screen edge. Double-tap it to send what you are looking at to Solace — "how should I reply to this", "note this down". |
| **Scheduled tasks** | One-off reminders, recurring jobs and conditional tasks such as "check the weather every morning and tell me only if it will rain". Results return to the original conversation. |
| **Memory** | Remembers facts, preferences and approved ways of working. You can view, edit or delete everything; passwords, verification codes and card numbers are never stored. |
| **Linux sandbox** | Each conversation gets a workspace for Python, Node and shell. Offline by default. arm64 devices only. |
| **Real takeover** | Login, verification codes and payment pause the task and hand control to you. After you hand back, Solace re-checks the screen before continuing. |

## Two execution engines

The engine is chosen per conversation and fixed from the first message; switching affects new conversations only.

| | **Solace engine** (default) | **DeepSeek Harness (DSH)** |
|---|---|---|
| Execution | The Solace loop drives phone, browser and sandbox directly | DSH runs as an independent engine behind a device bridge |
| Best for | Everyday tasks with the most settled behavior | Trying a second agent stack or comparing engines side by side |
| Safety | Built-in action risk grading and payment confirmation | Every capability call passes the host-level HostSafetyPolicy first, then DSH approval |
| Takeover | Notification takeover, then resume after hand-back | Real `hand_to_user` takeover; unattended for 10 minutes, the task ends by timeout |

## How Solace differs from the original projects

Solace combines the Apache-2.0 Solace project with DeepSeek Harness. The capabilities below were added or reworked on the Solace side — this is not a rename.

### Host-level safety gate

Every DSH device capability call passes `HostSafetyPolicy` before the engine's own approval flow:

- Banking apps are hard-denied, with no unlock switch.
- Messaging apps require confirmation for every write action.
- System packages are read-only by default; task-scoped grants unlock them only when you explicitly allow it.
- Grants never outlive the task: starting a new task invalidates old ones.

### Real task takeover

`hand_to_user` is now a complete flow instead of a hint: the task pauses, a notification opens the target app on your main screen, you finish there, then hand back. Solace verifies the scene before continuing. If nobody takes over within 10 minutes, the task ends and reports honestly.

### Engine crash classification

DSH startup failures, early crashes and late crashes are recorded separately. Two consecutive abnormal exits enter SafeMode and keep the last known good configuration, so the host can suggest falling back to the Solace engine instead of restarting a doomed backend forever.

### ColorOS keep-alive

On OPPO / OnePlus / realme devices Solace surfaces three entries: battery and background running, auto-start, and background pop-up. Each entry tries vendor pages in order and falls back to the app details page; the status is re-checked when you return.

### Node resource budget

The DSH engine bounds Node memory and threads in four device tiers. Under thermal pressure it enters a lite mode that lowers concurrency and widens startup gaps, so background work does not heat the phone.

### Notification hardening

External-engine tasks use fixed notification slots so progress, attention and input notices do not overwrite each other. Lock screens show a fixed title only, never task content. Approval notices expire after 5 minutes as a rejection, and multiple tasks collapse into a grouped summary.

### Other changes

- A new icon and the "Solace / 小满" identity.
- DSH tasks are surfaced consistently in the task center, capsule and notifications.
- A system-capabilities page centralizes accessibility, notification listener, files, SMS, contacts, calendar and related grants.

## Get started

### Requirements

- Installs on Android 11+; running tasks needs Android 13+, with Android 14+ recommended.
- The Linux sandbox needs an arm64 device.
- An API key for a tool-calling model; local and LAN models also work.

### Steps

1. Install the APK from [Releases](https://github.com/CottonChou/Solace/releases/latest).
2. Add a model in the first-run wizard. Presets include DeepSeek, 通义千问, Kimi, 智谱, OpenRouter, Anthropic, Gemini and more; custom endpoints accept OpenAI-compatible, Anthropic and Gemini protocols.

<!-- generated:providers:start -->
<!-- Generated by ./gradlew updateReadme; do not edit by hand -->
Presets for DeepSeek, 通义千问, Kimi, 智谱, OpenRouter, OpenCode Go, Anthropic, Gemini and more; for any other provider pick Custom (自定义), which takes any OpenAI-compatible, Anthropic or Gemini endpoint.
<!-- generated:providers:end -->

3. Complete wireless-debugging pairing in the wizard. Solace starts its built-in phone helper and enables accessibility itself. Skip this for now if you only want web, files and chat.
4. Pick an execution engine for a new conversation and try:

- "Summarize what a WeChat group talked about today"
- "Open a job site, filter suitable roles and greet recruiters"
- "Check the weather at 8 every morning and tell me only if it will rain"

## Data and privacy

- There is no Solace server. Chats, screen data and screenshots go only to the model provider you configure.
- API keys, memory and user profile are encrypted with the Android Keystore; conversations and run records stay on the device.
- Update checks contact GitHub Releases only.
- The sandbox is offline by default; the built-in browser visits only sites you send it to.

## Disclaimer

Automating third-party apps and websites may violate their terms and carries account-restriction risk. Use your own judgment. For money, accounts and important messages, prefer Ask mode and check the result.

## FAQ

**The DSH engine failed to start. What now?**

Solace classifies the crash and enters SafeMode instead of retrying forever. Switch back to the Solace engine in a new conversation, then follow the crash hint to review model settings or device resources.

**Why was a system-settings action denied?**

System packages are read-only under the host safety policy by default. If a task genuinely needs to change system state, approve it for that task; the grant does not survive into later tasks.

**The app moved to my main screen. Is that normal?**

That is takeover. Login, verification codes and payment belong to you; hand back when done and Solace verifies the current page before continuing.

**Tasks keep getting killed on ColorOS.**

Open the OPPO / ColorOS keep-alive entries on the system-capabilities page: battery and background running, auto-start, and background pop-up. These are vendor pages Solace cannot read; turn them on manually.

**Can I use a LAN model?**

Yes. Add a custom provider with a LAN address. Unencrypted addresses show a warning; prefer HTTPS when available.

## Contributing

Issues, knowledge files and pull requests are all welcome. The repository has four main areas:

| Module | Contents |
|---|---|
| `core/` | Main loop, tools, prompts, knowledge and skills |
| `app/` | Android host, UI, notifications, takeover and safety policies |
| `dsh-engine/` | DeepSeek Harness bridge, crash policy and resource budget |
| `backend/` (inside `app`) | Backend selection, DSH device bridge and approval bridge |

Adding knowledge needs no code: write a Markdown file for an app or website under `core/src/main/resources/knowledge/`, following [`docs/knowledge-format.md`](docs/knowledge-format.md).

When filing an issue, include phone model, Android version, Solace version and reproduction steps; hide private content in screenshots. Run unit tests before submitting a PR.

<!-- generated:knowledge:start -->
<!-- Generated by ./gradlew updateReadme; do not edit by hand -->
| Category | Built in |
|---|---|
| **Apps** | Android 设置, 百家号, 哔哩哔哩, BOSS直聘, 得到, 抖音, 飞书, Gmail, 即刻, 快手, 领英, QQ邮箱, 今日头条, 微信, 微博, 闲鱼, 小红书, 知乎 |
| **Websites** | 哔哩哔哩, 豆瓣, 抖音, Instagram, 百度贴吧, 微博, 搜狗微信, 小红书, 小宇宙, YouTube, 知乎, BOSS直聘 |
| **How-to skills** | 把内容整理成文字, 处理 PDF 文档, 查清一件事, 消息与邮件 |
<!-- generated:knowledge:end -->

## For developers

```bash
./gradlew :app:assembleDebug                  # debug build
./gradlew :app:assemblePerf                   # precompiled like release, debug-signed
./gradlew :core:test :app:testDebugUnitTest   # unit tests
```

The debug build is not precompiled and its UI is noticeably slower; do not judge smoothness with it. Architecture notes live in [`docs/architecture.md`](docs/architecture.md); roadmap and known issues in [`docs/roadmap.md`](docs/roadmap.md).

## License

Solace is released under the [Apache License 2.0](LICENSE). Third-party components are listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
