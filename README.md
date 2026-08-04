# RikkaMinis — Android

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Android%20arm64-brightgreen.svg)](#install)
[![Build](https://github.com/logicflow-GYW/RikkaMinis/actions/workflows/build-apk.yml/badge.svg)](https://github.com/logicflow-GYW/RikkaMinis/actions/workflows/build-apk.yml)

**简体中文** · [English](README_EN.md)

**你的私有、端侧 AI 智能体。**

RikkaMinis 是一个个人专用的 **Android-only** 构建，杂交了两个项目：
引擎与代码库来自 [OpenMinis](https://github.com/OpenMinis/OpenMinis)，
交互设计（聊天历史抽屉、极简顶栏、精简设置）则受 [RikkaHub](https://github.com/rikkahub/rikkahub) 启发。

它在 GitHub Actions 上构建可用的 APK 并自动发布。

OpenMinis 核心把领先的模型——Claude、GPT、Gemini 等——带进原生移动体验，
并给它们一台真正的计算机：设备上运行的完整 Linux Shell、浏览器自动化、
可扩展技能、持久记忆，以及深度的系统集成。

---

## 安装

**→ [下载最新 APK](https://github.com/logicflow-GYW/RikkaMinis/releases/tag/android-latest)**

每次推送到 `main` 都会构建一个发布版 APK 并重新发布到该链接，所以这个
URL 始终指向最新构建。要求：

- **arm64-v8a** 设备（任意现代手机），**Android 8.0+**
- 设备提示时允许"安装未知来源应用"

构建使用固定密钥签名，因此新 APK 会**覆盖**安装旧版本——你的数据和设置会被保留。

```
SHA-256  FC:0C:40:0D:B7:7E:C1:81:A3:35:18:C2:E8:13:6A:AE
         1A:3F:6C:79:4A:1A:A7:9F:DB:67:63:8F:C6:B1:61:13
```

用 `python3 scripts/apk_cert_sha256.py <apk>` 校验下载。注意此密钥与官方构建
不同，如果你当前安装的是官方 APK，必须先卸载再安装。

---

## 这个 fork 改了什么

起初这是一个纯构建 fork，但现在也携带了一小批上游没有的 Android 专属产品改动。

### 应用改动

- **完整的本地备份与恢复。** 设置 → 存储 → 备份与恢复 导出一个可移植的 JSON
  文件，可在另一台安装上导入。涵盖提供方/模型配置与分组、可选 API 密钥、
  环境变量、应用/智能体/聊天默认值、Soul、完整技能（SKILL.md 连同捆绑脚本、
  引用与资源）、持久记忆、MCP 服务器配置，以及聊天历史（纯文本，默认最近
  90 天，窗口可在备份设置中调整）。
- **诚实的排除项。** 聊天历史仅以纯文本携带：媒体（图片/视频）和附件文件会被
  丢弃，只包含最近 N 天的活动（0–365，默认 90；0 表示禁用聊天历史）。
  挂载文件夹的授权无法在 Android 设备间迁移，MCP OAuth 客户端密钥/令牌
  从不导出——OAuth 认证的 MCP 服务器在恢复后必须重新授权。
- **聊天 UI 打磨。** 消息链接可以聚焦并高亮某条消息；导航标题左对齐；
  当前模型选择器位于输入栏内；附件与命令操作排布更紧凑。
- **左滑聊天历史抽屉。** 聊天界面从左侧边缘（或通过汉堡按钮）滑出会话列表，
  无需离开当前聊天即可切换历史会话——或开启新对话。抽屉与会话列表保持一致：
  相同的分组、分类图标与相对时间戳，当前会话高亮，长按可删除会话。
- **UX 打磨。** 进入应用不再自动弹出键盘——输入栏只在你点击时才聚焦。
  输入栏中的工具结果缩略预览默认关闭（在 设置 → 外观 中切换）。聊天的
  "…" 菜单可导出当前会话（JSON 或纯文本，位于 Slash Commands 与 Token Usage
  之间），且不再列出 Clear Chat——它与 New Chat 重复，还可能留下一个空的
  幽灵会话。设置及其顶层子页（外观、备份、环境变量、日志、MCP、记忆、
  提供方、技能、Soul、存储、用量）去掉了冗余的顶栏返回箭头——改用系统返回
  手势 / 底部导航处理；编辑、向导与权限流程页面保留返回箭头。
- **更简洁的输入栏。** 专用的语音聊天快捷入口及其内嵌 UI 已被移除。
  Android 面向智能体的语音工具不受影响。
- **设置一致性修复。** 恢复的偏好会刷新实时设置界面，此前缺失/断连的设置键
  现已注册并纳入备份。

### 构建与发布改动

- **proot 从源码构建。** 沙箱引擎来自 `deps/proot` 子模块 + `deps/build_proot.sh`
  + vendored 的 `deps/talloc`，在 CI 中用 NDK r28 编译——不提交二进制文件，
  完全可复现。
- **其他原生库保持 vendored。** `libpty_bridge.so`、`libminis_crash_handler.so`
  和 `libjieba_jni.so` 按原样提交。
- **备份测试在 CI 中运行。** 备份负载测试在 APK 构建之前执行。
- **iOS 源码已移除。** `src/ios/` 已删除；本树仅限 Android。
- **自动发布。** 成功构建会把 APK 发布到 `android-latest` release。


### 为什么要从源码构建 proot？

沙箱引擎 `libproot.so` 需要上游的 Android 10+ W^X 绕过补丁。通过 AGP 的
CMake 块构建，产出的二进制能编译通过，却在运行时以
`execve("/bin/sh"): Permission denied` 失败——终端永远打不开。因此本 fork
改用 `deps/build_proot.sh`（上游支持的路径——与官方二进制相同的源码、
相同的 NDK 工具链）而非 CMake 来构建它。`externalNativeBuild` 保持禁用，
这样 AGP 永远不会用未打补丁的 CI 构建版覆盖 vendored 的
pty_bridge / crash_handler / jieba 库。

**权衡：** `src/android/app/src/main/cpp/` 下的改动不会被编译——只有
`deps/proot` 通过 `build_proot.sh` 构建。改动其他原生代码意味着要恢复 CMake
块并在 CI 中安装 NDK。Kotlin、UI、提示词与模型集成不受影响——正常构建。

---

## 它能做什么

| | |
|---|---|
| **自带模型** | Claude、GPT、Gemini 及其他提供方，使用你自己的 API 密钥或账号登录。 |
| **真正的 Linux Shell** | 设备上运行沙箱化的 Alpine Linux 环境——智能体可以安装软件包、运行脚本、操作真实文件。 |
| **设备集成** | 日历、联系人、剪贴板、定位、媒体、闹钟、通知等，作为工具开放给智能体。 |
| **浏览器自动化** | 智能体可以代表你浏览并操作网页。 |
| **技能与记忆** | 可扩展技能 + 跨会话的持久记忆。完整技能包与记忆文件包含在本地备份中。 |
| **本地备份与恢复** | 把配置、凭据（可选）、技能、记忆、MCP 服务器与聊天历史（文本、最近 N 天）导出到一个可移植的 JSON 文件。 |
| **工作区** | 把工作组织到独立上下文中，通过 `minis://workspace/` 访问。 |
| **原生卸载（offload）** | 繁重或平台特定的工作交给原生代码而非沙箱处理。 |

**→ [OpenMinis/MinisSkills](https://github.com/OpenMinis/MinisSkills)** — 现成技能。
为 Claude、Codex、OpenClaw 或 Hermes Agent 构建的技能通常可以直接在 Minis 中运行。

**→ [OpenMinis/AwesomeMinis](https://github.com/OpenMinis/AwesomeMinis)** — 精选的
用例与工作流合集。

---

## 本地构建

```sh
git clone --recurse-submodules https://github.com/logicflow-GYW/RikkaMinis.git
cd RikkaMinis/src/android
../../deps/build_proot.sh        # 从源码构建 proot 沙箱引擎
./gradlew assembleRelease
```

需要 **JDK 17**、Android SDK（compileSdk 36）和 **NDK r28**——后者用于
`deps/build_proot.sh`，它从 `deps/proot` 子模块编译 proot 沙箱引擎（其他原生库
已 vendored 在树中）。APK 输出在 `app/build/outputs/apk/release/`。

本地构建使用你自己的 `~/.android/debug.keystore` 签名，因此无法覆盖安装 CI
构建。要对齐 CI，请把相同的 keystore 放到那里。

工具链细节与排障见 [BUILDING.md](BUILDING.md)。

---

## 跟上上游

上游是单向镜像，不接受 pull request，而本 fork 在少数文件上已经分叉。
同步是可能的，但有操作顺序要求——尤其是 vendored 的 pty_bridge /
crash_handler / jieba 库必须在上游 Kotlin 改动时刷新，否则应用会在运行时崩溃。
proot **不再** vendored：它在 CI 中通过 `deps/build_proot.sh` 从源码构建，
所以对它来说唯一需要刷新的是上游升级时的 `deps/proot` 子模块。

```sh
git fetch upstream
git rebase upstream/main               # 不要 merge
./scripts/sync_official_binaries.sh    # 刷新 vendored 的 pty_bridge/crash_handler/jieba 库
```

**→ 完整流程、冲突文件清单以及从坏同步中恢复的方法见 [docs/SYNCING_UPSTREAM.md](docs/SYNCING_UPSTREAM.md)**

---

## 隐私

本 fork 不添加任何追踪，上游也没有。具体来说：

- **无分析或遥测 SDK。** 没有 Firebase、Crashlytics、Sentry 或类似组件。
- **崩溃报告留在设备上。** 包含 ACRA 但仅 `acra-core`——未配置任何网络发送器。
  报告写入本地文件，并在应用的日志界面中展示。
- **不收集设备标识符。** 没有 IMEI，没有广告 ID。
- **发布构建中没有调试服务器。** 开发用的本地 JSON-RPC 服务器位于
  `127.0.0.1:5321`，由 `BuildConfig.DEBUG` 门控，并已从这里发布的 release APK
  中编译移除。

网络流量只流向你配置的模型提供方（使用你自己的 API 密钥），以及你明确让
智能体访问的端点。本地备份文件不会离开设备，除非你自己分享或复制。
如果你选择"包含机密"，JSON 会以可恢复形式包含 API 密钥和环境变量值；
请像保管密码一样保管该文件。即使包含机密的备份也排除 MCP OAuth 令牌与
客户端密钥。

应用请求较宽泛的权限（存储、联系人、日历、麦克风、定位、无障碍），因为它们
支撑智能体工具。这些权限在使用时按需请求——智能体只能使用你授予的能力。

---

## 仓库结构

```
src/android/      Android 应用（Kotlin / Compose）
  app/src/main/jniLibs/arm64-v8a/   原生库（jieba、pty bridge、crash handler）；
                                    libproot.so 是 CI 构建产物，非 vendored
  app/src/main/assets/              Alpine minirootfs
src/shared/       与上游 iOS 树共享的资源（bashism 规则）
deps/             proot 源码（子模块）+ build_proot.sh（NDK r28 构建）
docs/             同步流程与接口规范
scripts/          二进制同步与开发者工具
```

---

## 致谢

OpenMinis 建立在大量开源工作之上——完整清单见
[THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md)。本 fork 派生自
**[OpenMinis/OpenMinis](https://github.com/OpenMinis/OpenMinis)**，并从头构建其
沙箱二进制：`deps/proot` 子模块（OpenMinis 的 PRoot fork，含其 native-offload
与 W^X 扩展）在每次 CI 运行中通过 `deps/build_proot.sh` 用 NDK r28 编译。
本仓库不提交任何预构建的沙箱二进制。

**沙箱** — [PRoot](https://github.com/termux/proot)（GPLv2），Android 沙箱的用户态
chroot，经由 [OpenMinis 的 fork](https://github.com/OpenMinis/proot)；
**[talloc](https://talloc.samba.org)**（LGPLv3+）是其底层；
**[Alpine Linux](https://alpinelinux.org)** — 沙箱启动所用的 minirootfs。

**文本与渲染** — [cppjieba](https://github.com/yanyiwu/cppjieba)（MIT）、
[KaTeX](https://katex.org)（MIT）。

**Android** — [AndroidX & Jetpack Compose](https://developer.android.com/jetpack)、
[OkHttp](https://square.github.io/okhttp/)、[Coil](https://coil-kt.github.io/coil/)、
[kotlinx](https://github.com/Kotlin) 序列化与协程、
[multiplatform-markdown-renderer](https://github.com/mikepenz/multiplatform-markdown-renderer)、
[Reorderable](https://github.com/Calvin-LL/Reorderable)、[ACRA](https://github.com/ACRA/acra)
（均为 Apache-2.0），以及 [Shizuku](https://github.com/RikkaApps/Shizuku-API)（MIT）。

---

## 许可证

OpenMinis 以 **[GNU General Public License v3.0](LICENSE)** 许可。

应用链接了 GPL 许可的组件——[PRoot](https://github.com/OpenMinis/proot)
（GPLv2）——因此合并后的作品以 GPLv3 分发。捆绑的第三方许可证列在
[THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md)。

---

## 上游

原始项目、iOS 应用、issue 与社区：

**→ [OpenMinis/OpenMinis](https://github.com/OpenMinis/OpenMinis)** ·
[openminis.app](https://openminis.app) ·
[Telegram](https://t.me/+2NzhOJuzRyI1YmM1)

对于一般应用 bug，请检查官方上游构建是否也会出现。上游 issue 属于
OpenMinis/OpenMinis；本 fork 的构建、APK、备份/恢复流程或 Android UI 改动
的问题请提交到本仓库。
