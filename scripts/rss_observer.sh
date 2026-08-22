#!/bin/sh
# =============================================================================
# rss_observer.sh — provider-rss / offload-rss / browser-rss logcat 聚合观测
# (TF-A, provider-rss v2 配套)
#
# 抓取 logcat -b all 中的三类 RSS 打点，按 pid+processName 分组输出：
#   count / totalDelta / peak / postIdleRss / vmHwm / vmPeak
# 方便判定「内存泄漏斜率」：若某组 postRss 单调上移且不回落，即泄漏候选。
#
# 用法：
#   sh scripts/rss_observer.sh          # 快照：抓当前 -d 缓冲并汇总后退出
#   sh scripts/rss_observer.sh --follow # 流式：持续抓取，Ctrl-C 退出
#   sh scripts/rss_observer.sh -n 300   # 只汇总最近 300 行打点
#   sh scripts/rss_observer.sh < dump.txt  # 从文件/stdin 读 logcat dump（沙箱场景）
#
# 仅输出汇总，不改动任何日志/行为。awk 用 POSIX/BusyBox 兼容语法（不用 capture-array match）。
# =============================================================================

LOGCAT_BIN="logcat"
FOLLOW=0
LIMIT=""

# 解析参数
prev=""
for arg in "$@"; do
    case "$arg" in
        --follow|-f) FOLLOW=1 ;;
        -n*)
            val="${arg#-n}"
            [ -n "$val" ] && LIMIT="$val" && prev="" || true
            ;;
        *)
            prev="$arg"
            ;;
    esac
done
[ -n "$prev" ] && [ -z "$LIMIT" ] && LIMIT="$prev"
if [ -z "$LIMIT" ]; then LIMIT=100000; fi

collect() {
    if [ -t 0 ]; then
        if [ "$FOLLOW" -eq 1 ]; then
            "$LOGCAT_BIN" -b all | grep --line-buffered -E '\[provider-rss\]|\[offload-rss\]|\[browser-rss\]' | head -n "$LIMIT"
        else
            "$LOGCAT_BIN" -b all -d | grep -E '\[provider-rss\]|\[offload-rss\]|\[browser-rss\]' | tail -n "$LIMIT"
        fi
    else
        grep -E '\[provider-rss\]|\[offload-rss\]|\[browser-rss\]' | tail -n "$LIMIT"
    fi
}

awk_score() {
    awk '
    function val(s, key,   i, len, rest) {
        # 提取 s 中 " key=NNNMB"（负值/无 MB 取通用形式），返回数值；找不到返回空
        i = index(s, " " key "=")
        if (i == 0) return ""
        rest = substr(s, i + length(key) + 2)
        if (rest ~ /^[-+0-9]+/) {
            if (match(rest, /^[-+0-9]+/)) return substr(rest, 1, RLENGTH)
        }
        return ""
    }
    {
        line = $0
        if (line !~ /\[provider-rss\]/ && line !~ /\[offload-rss\]/ && line !~ /\[browser-rss\]/) next
        if (line ~ /provider-rss summary/) next

        pid = val(line, "pid"); proc = val(line, "process")
        # process 是字符串（不以 MB 结尾），用专用提取
        if (proc == "") {
            i = index(line, " process=")
            if (i) proc = substr(line, i + 9)
            if (proc ~ / /) proc = substr(proc, 1, index(proc, " ") - 1)
        }
        if (pid == "" && proc == "") { pid = "?"; proc = "?" }

        c = val(line, "cum")
        p = val(line, "postRss")
        k = val(line, "peak")
        h = val(line, "hwm")
        v = val(line, "vmpeak")

        key = pid "|" proc
        n[key]++
        if (c != "") cum[key] += c + 0
        if (p != "") post[key] = p + 0
        if (k != "" && (k + 0) > maxpk[key]) maxpk[key] = k + 0
        if (h != "") hs[key] = h + 0
        if (v != "") vs[key] = v + 0
    }
    END {
        fmt = "%-30s %6s %10s %10s %12s %12s %8s\n"
        printf fmt, "pid|process", "count", "totalΔMB", "peakMB", "postRssMB", "hwmMB", "vmPeakMB"
        for (k in n) {
            printf fmt, k, n[k], cum[k], (maxpk[k]==""?"0":maxpk[k]),
                post[k], hs[k], vs[k]
        }
    }'
}

collect | awk_score