---
name: ubuntu-companion
description: Ubuntu 沙箱（feat/ubuntu-sandbox 起）的运维搭档 — 常用包按需安装速查、apt 失败自愈（镜像自动回退）、时区与环境差异清单（proot 无 init、policy-rc.d、$BROWSER 劫持等）。涉及 apt 安装失败、镜像切换、沙箱环境差异、proot 行为差异、时区不一致时触发。
version: 1.0.0
---
# Ubuntu Companion Skill

## 这是什么
RikkaMinis 的沙箱自 2026-09 起从 Alpine 迁移到 **Ubuntu Base 24.04 arm64**（proot 运行，非虚拟机）。本技能封装这个环境的三件事：

1. **常用包按需安装速查** — minbase rootfs 只有 270 个包，缺什么装什么
2. **apt 失败自愈** — 镜像探测 + 自动回退链（`minis-mirror`）
3. **环境差异清单** — proot 环境与"真正的 Linux"哪里不一样，别踩坑

## 何时触发
- `apt install` 失败 / `apt-get update` 报错 / 404 / TLS 失败 / 换源
- 用户要求安装开发工具（node、jq、gcc、ripgrep、sqlite…）
- 时区、日期、mtime 看起来不对
- 脚本在沙箱里行为和正常 Linux 不一致（daemon 起不来、浏览器打开方式怪异）
- 关键词：装包 换源 apt 镜像 沙箱差异 proot 时区

## 工具

### 1. 环境体检（最先跑）
```bash
sh /var/minis/skills/ubuntu-companion/scripts/check_env.sh
```
一键输出：OS/内核、缺哪些常用工具、apt 镜像现状、时区一致性（TZ env vs /etc/localtime vs date）、网络连通性。结果是 JSON（`--quiet` 去掉人类可读部分；存在 CRITICAL 问题时退出码为 1）。**遇到"环境怪异"类问题先跑这个再下结论。**

### 2. apt 自愈（装包失败时按序执行）
```bash
# 第一步：镜像自动回退（探测 8 个镜像，切到第一个 apt 验证通过的）
minis-mirror auto

# 手动指定（探测排名见 probe）
minis-mirror probe            # 全部镜像测速
minis-mirror set ustc tuna official   # 固定优先级链
minis-mirror reset            # 恢复出厂（TUNA 优先）
minis-mirror status           # JSON 状态

# 第二步：仍失败 → 按症状处理
#   Hash Sum mismatch   → apt-get clean && rm -rf /var/lib/apt/lists/* && apt-get update
#   dpkg lock           → 另一个 apt 在跑，等它结束（应用会序列化 apt，别 kill）
#   E: Unable to locate → update 没真正成功，先解决镜像
```

### 3. 常用包速查（minbase 缺省未装，按需一条命令）
```bash
apt-get update && apt-get install -y <包>
```

| 需求 | 包 | 备注 |
|---|---|---|
| JSON 处理 | `jq` | 已预装 |
| Node.js/前端 | `nodejs npm` | Node 18 |
| C/C++ 编译 | `build-essential` | gcc/g++/make |
| Python 构建 | `python3-dev` | pip 装源码包时 |
| 更好用的文本工具 | `ripgrep fd-find bat` | rg/fdfind/bat |
| 网络诊断 | `dnsutils netcat-openbsd` | dig/nc（ping 不可用）|
| 进程/系统 | `procps psmisc lsof htop` | ps/pstree/killall |
| 压缩 | `unzip zip xz-utils zstd` | |
| git 全功能 | `git openssh-client` | git 已预装 |
| man 手册 | `man-db less` | less 缺省没有 |
| 定时任务 | `cron` | 见下方差异清单 |

## 环境差异清单（proot ≠ 真机 Linux）

| 差异 | 表现 | 正确姿势 |
|---|---|---|
| **无 init/PID 1** | `systemctl`/`service` 不可用；`/usr/sbin/policy-rc.d` 拦截 daemon 自启 | 前台直接跑二进制；需要后台就 `nohup cmd >log 2>&1 &` 并把 stdout/stderr 重定向 |
| **ICMP 被禁** | `ping` 永远挂起 | 用 `curl -sI` / `wget -q --spider` 探连通性 |
| **$BROWSER 被接管** | `xdg-open`/`webbrowser.open()` 路由到宿主浏览器 offload | 想在沙箱内抓网页内容用 curl；想给用户开页面就直接说，宿主会打开 |
| **TZ 双通道** | env `TZ`（POSIX 格式）与 `/etc/localtime` 指向设备时区，两者一致 | 别在脚本里 export TZ 覆盖，会破坏一致性 |
| **apt 走系统代理** | 宿主 HTTP(S)_PROXY 透传，企业代理/抓包工具直接生效 | 代理出问题时排查宿主代理设置，别在沙箱里改 |
| **apk→apt 词汇迁移** | 老脚本/技能里的 `apk add` 已失效 | 一律 `apt-get install -y`；`/etc/apt/sources.list.d/minis.sources` 是应用管理的源文件 |
| **pip** | `break-system-packages=true` 已配置，PEP 668 不拦 | 直接 `pip install`；装编译型包先装 `python3-dev` |
| **硬链接敏感** | proot link2symlink 拒绝对 sentinel 的二次硬链接 | uv/pip 已配置 symlink 模式；自己写拷贝逻辑别用 hardlink |
| **cron 不自跑** | 没有 init，cron daemon 不会常驻 | 定时需求用宿主能力（闹钟/日历），沙箱内只做一次性任务 |
| **/bin/sh 是 dash** | `**`、`{a,b}`、数组等 bash 语法报错 | 脚本头部写 `#!/bin/bash` 或保持 POSIX；长脚本先落盘再执行 |

## 安装包名差异（apk → apt 对照）
alpine 时代的老技能如果还写着 apk 包名，按此映射：
`py3-pip→python3-pip`、`openssh→openssh-client`、`ncurses→libncurses6`（运行库）/`ncurses-bin`（工具）、`musl→`（无需，glibc 环境）。

## 状态与排障
```bash
minis-mirror status         # 当前镜像 + 最近探测结果
cat /var/lib/minis-mirror/state.json
date; date -u; ls -l /etc/localtime   # 时区一致性三件套
```
