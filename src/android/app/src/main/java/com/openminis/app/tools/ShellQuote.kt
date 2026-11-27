package com.openminis.app.tools

/**
 * [T-shell-job] POSIX 单引号转义。
 *
 * 这是 `shell_job` 唯一的安全边界：命令要原样塞进 `sh -c '...'`，这里的
 * 每个字节都决定"用户给的命令"会不会变成"两条命令"。
 *
 * 规则只有一条：单引号内部除了 `'` 本身没有特殊字符，所以把每个 `'` 用
 * `'\''` 断开再续。写成纯函数是为了能被单测钉死——这类转义错一次就是
 * 命令注入，不是样式问题。
 */
object ShellQuote {

    /** 返回被单引号包裹、可安全交给 `/bin/sh -c` 的字符串。 */
    fun single(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}