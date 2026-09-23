<div align="center">

# 对话副驾 · QQ 适配版

**装在手机上的「对话副驾」：你在微信 / QQ 里聊天，它在旁边读懂对方、给出 3 条候选回复，一键填进输入框，发不发由你。**

[![Build APK](https://github.com/Animal2404/jev-jarvis-qq/actions/workflows/build-apk.yml/badge.svg)](https://github.com/Animal2404/jev-jarvis-qq/actions/workflows/build-apk.yml)
[![Version](https://img.shields.io/badge/%E7%89%88%E6%9C%AC-v1.4-1f6feb?style=flat-square)](https://github.com/Animal2404/jev-jarvis-qq/releases)
[![Android](https://img.shields.io/badge/Android-11%2B-3DDC84?style=flat-square&logo=android&logoColor=white)](#安装)
[![License](https://img.shields.io/badge/License-MIT-blue?style=flat-square)](LICENSE)

[下载 APK](https://github.com/Animal2404/jev-jarvis-qq/releases/latest) · [怎么构建](#云端构建) · [上游项目](https://github.com/jev-chat/jev-chat-jarvis)

</div>

> 这是 [Jev 聊天助手](https://github.com/jev-chat/jev-chat-jarvis)（MIT）的二次开发版，**与原作者无隶属关系，不代表其出品或背书**。
> 相对上游的差别只有三处：**判断接口换成 Knox Studio、回复接口换成 TokenRhythm、构建全部走 GitHub Actions**。
> 采集与判断的内核、UI、题目集都来自上游。

## 这一版做了什么

| | 上游 v1.3 | 本版 v1.4 |
|---|---|---|
| 判断接口（Jev） | OpenRouter `typesafe/jev-1.13` | **Knox Studio** `https://api.knoxstudio.ai/v1` + `jev-1.13.0` |
| 回复接口 | OpenRouter `deepseek-chat-v3.1` | **TokenRhythm** `https://tokenrhythm.studio/v1` + `mimo-v2.6-flash` |
| 视觉接口（OCR 兜底） | OpenRouter `qwen2.5-vl` | TokenRhythm `mimo-v2.6-flash`（已验证可读图） |
| 构建 | 本机 Gradle | **GitHub Actions 云端构建**，产物自动发 Release |
| 密钥 | 手动填 | 手动填（**APK 内不含任何密钥**） |

QQ 适配器沿用上游已真机验证的实现，没有改动。

## 它怎么工作

```
微信 / QQ ──(无障碍读节点)──▶ 采集最近消息
                                  │
              ┌───────────────────┴───────────────────┐
              ▼                                       ▼
   Jev 判断（一次 7 道题）                生成模型起草 3 条候选
   意图 / 危险 / 需求 / 动作 / 该不该回       （TokenRhythm）
     （Knox Studio）                              │
              └───────────────────┬───────────────┘
                                  ▼
                        Jev 给 3 条候选排序
                                  ▼
                半透明悬浮窗展示 → 复制 / 填入（不发送）
```

- **采集**：一个 App 一个适配器。QQ 节点不混淆，正文 `id/mjn`、标题 `id/371`、输入框 `id/input`；
  微信 8.0.52+ 混淆节点，靠把服务类名伪装成系统 `SelectToSpeakService` 读 `id/bkl`。
  QQ 全程是 `SplashActivity`，判断「是否在聊天窗」只能看树里有没有 `id/input`，不能看 Activity 名。
- **判断**：Jev 只回答选择 / 打分 / 是非，不生成文字，一次请求发全部题目，约 1.5 秒返回。
  题目 instructions/criteria 用英文（Jev 主训练语言），聊天内容保留中文原文。
- **起草**：`mimo-v2.6-flash` 生成 3 条策略不同的候选，再交 Jev 排序。
- **回填**：`ACTION_SET_TEXT`，失败则剪贴板 `ACTION_PASTE`。**从不自动发送。**

## 安装

从 [Releases](https://github.com/Animal2404/jev-jarvis-qq/releases/latest) 下载 `jev-assistant-v1.4-qq.apk`（arm64，Android 11+）：

```bash
adb install -r jev-assistant-v1.4-qq.apk
```

或把 APK 传到手机点击安装。小米 / HyperOS 装完请按主页向导开
**无障碍 + 悬浮窗（显示在其他应用上层） + 自启动 / 省电无限制**，否则后台会被冻结。

## 首次配置：填两个密钥

APK 里**不含任何密钥**（发布物是公开的，烧进去等于公开你的额度）。打开 App → 设置：

| 用途 | Base URL（已预置默认） | 模型 | 你要填的 |
|---|---|---|---|
| 判断接口 | `https://api.knoxstudio.ai/v1` | `jev-1.13.0` | Knox Studio 的密钥 |
| 回复接口 | `https://tokenrhythm.studio/v1` | `mimo-v2.6-flash` | TokenRhythm 的密钥 |

> 两条密钥**不一样**：判断走 Knox、起草走 TokenRhythm，是两家服务，两个都要填。
> 视觉接口默认复用回复接口的密钥，留空即可。

填完先点「测试判断」「测试回复」确认通了，再点「保存全部设置」。
密钥只存在 App 私有存储，不出设备、不进日志、不进 git。

## 云端构建

仓库自带 GitHub Actions 工作流（[`.github/workflows/build-apk.yml`](.github/workflows/build-apk.yml)），
**不需要在本机装 Android SDK**：

1. 打开仓库 **Actions** → 左侧 **Build APK** → **Run workflow**
2. 跑完在 [Releases](https://github.com/Animal2404/jev-jarvis-qq/releases) 取 APK，或在该次运行的
   Artifacts 里下载

推送 `v*` 标签（如 `v1.4`）也会自动触发并发布 Release。
工作流里有一道断言：**若 APK 内出现任何密钥特征串就直接失败**，保证发布物干净。

## 目录

- `app/` — Android 应用（Kotlin，传统 View，无 Compose）
  - `capture/` 无障碍采集（`ChatAppAdapter.kt` 各 App 适配器、`ChatCaptureService.kt` 分发服务）与前台保活
  - `jev/` 三路接口客户端（`JudgeClient` / `ReplyClient` / `VisionClient`，共用 `HttpJson`）
  - `overlay/` 悬浮窗 · `core/` 配置与数据模型 · `core/kb/` 本地知识库
- `tools/jev/` — Jev 题目集与校准脚手架（Python，PC 上跑）
- `docs/` — 设计与验收文档

## 硬约束

1. **不 hook、不 Xposed、不改目标 App、不读其数据库**，只用系统无障碍服务与截屏。
2. **绝不自动发送消息**，绝不点发送按钮。填入输入框后停手。
3. **不碰钱**：不触碰转账、红包、收款码相关任何界面元素。
4. **密钥不落盘进仓库、不进日志、不进 git。**

## 已知边界

- 本版把接口换成 Knox / TokenRhythm，**未在真机上跑过端到端**：QQ 采集逻辑来自上游真机验证
  （QQ 9.3.50 / 小米 14 / 1200×2670），换接口后的完整流程需要装机实测一次。
- 国产 ROM 后台冻结：小米 / HyperOS 仍可能杀后台，被杀后气泡短暂消失，在聊天里再交互一下即自愈。
- 飞书正文自绘，树里没有文字，要靠截图 OCR 补；本版视觉接口已可读图。
- 群聊按一对一分析，「对方」与关系设定对群聊不准。
- 伪装无障碍服务是绕过微信节点混淆的手段，微信版本更新可能失效。

## 免责声明

仅供个人学习与研究使用。只处理你自己设备上、你自己有权查看的聊天。
请遵守微信、QQ 等各软件的许可协议与当地法律法规，作者不对使用后果负责。

## License

[MIT](LICENSE)。本项目基于 [Jev 聊天助手](https://github.com/jev-chat/jev-chat-jarvis)
（Copyright © 2026 Finderchangchang and the jev-chat contributors）二次开发，
保留原 [LICENSE](LICENSE) 与 [NOTICE](NOTICE)。
