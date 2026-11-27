package com.openminis.app.tools

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject

/**
 * [T-tool-approval] 主 agent 的危险命令闸门。
 *
 * 之前只有 `spawn_agent` 有审批（[SpawnApprovalQueue]）。主 agent 的
 * shell_execute 是**直接执行**的：模型自己拼错一条 `rm -rf` 就把用户的挂载
 * 目录或者整个 rootfs 送走了。
 *
 * 设计上刻意收窄，只拦"不可逆且没有第二次机会"的那几种模式。理由很简单：
 * 一个每次 `rm -rf /tmp` 都弹窗的闸门，用户三天就会养成无脑点"允许"的肌肉
 * 记忆——那比没有闸门更危险，因为它制造了"我有保护"的错觉。
 */
enum class ToolApprovalDecision {
    /** 允许这一次。 */
    ALLOW_ONCE,

    /** 本会话内允许这一条规则（不是整个工具）。 */
    ALLOW_SESSION,

    /** 拒绝——工具不会执行，模型收到明确的拒绝结果。 */
    DENY,
}

/** 一次待审批的工具调用。纯数据，无 Android 类型。 */
data class ToolApprovalRequest(
    val id: String,
    val toolName: String,
    /** 命中规则的人类可读名（也是"本会话允许"的作用域键）。 */
    val ruleId: String,
    val ruleLabel: String,
    /** 命令预览（截断）。 */
    val commandPreview: String,
    val workingDirHint: String = "",
)

/**
 * [T-tool-approval] 待审批队列。形状与 [SpawnApprovalQueue] 一致
 * （resolve-once / remove-on-timeout / cancel-on-teardown）——那套竞态语义
 * 已经在生产里跑过一轮，不再发明第二套。
 */
class ToolApprovalQueue {
    private class Entry(val request: ToolApprovalRequest) {
        val deferred = CompletableDeferred<ToolApprovalDecision>()
    }

    private val entries = ConcurrentHashMap<String, Entry>()

    private val _requests = MutableStateFlow<List<ToolApprovalRequest>>(emptyList())
    val requests: StateFlow<List<ToolApprovalRequest>> = _requests.asStateFlow()

    fun submit(
        toolName: String,
        ruleId: String,
        ruleLabel: String,
        command: String,
    ): Pair<ToolApprovalRequest, CompletableDeferred<ToolApprovalDecision>> {
        val request = ToolApprovalRequest(
            id = nextId(),
            toolName = toolName,
            ruleId = ruleId,
            ruleLabel = ruleLabel,
            commandPreview = command.trim().take(PREVIEW_CHARS),
        )
        val entry = Entry(request)
        entries[request.id] = entry
        _requests.update { it + request }
        return request to entry.deferred
    }

    fun resolve(id: String, decision: ToolApprovalDecision): Boolean {
        val entry = entries[id] ?: return false
        if (!entry.deferred.complete(decision)) return false
        retire(id)
        return true
    }

    fun remove(id: String) {
        val entry = entries[id] ?: return
        if (entry.deferred.isCompleted) {
            retire(id)
            return
        }
        entries.remove(id)
        _requests.update { list -> list.filterNot { it.id == id } }
    }

    /** Teardown：所有挂起的审批一律 DENY（没人看得见的弹窗不能放行）。 */
    fun denyAllOutstanding(): Int =
        entries.keys.toList().count { resolve(it, ToolApprovalDecision.DENY) }

    private fun retire(id: String) {
        entries.remove(id)
        _requests.update { list -> list.filterNot { it.id == id } }
    }

    companion object {
        const val PREVIEW_CHARS = 500
        private val counter = AtomicLong(0)
        fun nextId(): String =
            "approval-" + System.currentTimeMillis().toString(36) + "-" + counter.incrementAndGet().toString(36)
    }
}

/**
 * [T-tool-approval] 危险命令识别。纯函数，可单测。
 *
 * 只认"执行了就没法撤销"的动作：
 *  - 递归删根 / 家目录 / 通配符；
 *  - 覆写块设备（dd / mkfs / 重定向到 /dev/）；
 *  - 远程脚本直接管道给 shell（`curl … | sh`）；
 *  - 强推远端分支（覆盖别人的提交）；
 *  - 递归改根权限。
 *
 * 明确**不拦**：`rm -rf /tmp/…`、`rm -rf node_modules`、普通 `dd` 到文件、
 * `chmod -R` 作用在 workspace 里——这些是 agent 的日常，拦了只会训练用户
 * 闭眼点允许。
 */
object DangerousCommandPolicy {

    data class Hit(val ruleId: String, val label: String)

    private data class Rule(
        val id: String,
        val label: String,
        val pattern: Regex,
    )

    private val RULES = listOf(
        Rule(
            "rm_root",
            "Recursive delete of / , /* or ~",
            Regex("""\brm\s+(?:-[a-zA-Z]+\s+)*(?:-[a-zA-Z]*[rR][a-zA-Z]*\s+)?(?:--no-preserve-root\s+)?(?:/|/\*|~|~/|\$\{?HOME\}?)(?=\s|$|[;&|])"""),
        ),
        Rule(
            "rm_glob_top",
            "Recursive delete with a top-level wildcard",
            Regex("""\brm\s+(?:-[a-zA-Z]+\s+)*(?:-[a-zA-Z]*[rR][a-zA-Z]*\s+)?(?:/[a-z]+\*|/\*/\*)"""),
        ),
        Rule(
            "dd_block",
            "Write directly to a block device (dd)",
            Regex("""\bdd\b[^;&|\n]*\bof=/dev/(?!null\b|zero\b|random\b|urandom\b|stdout\b|stderr\b|tty)"""),
        ),
        Rule(
            "rm_minis_root",
            "Recursive delete of the Minis data root",
            Regex("""\brm\s+(?:-[a-zA-Z]+\s+)*(?:-[a-zA-Z]*[rR][a-zA-Z]*\s+)?/var/minis/?(?:\*|$|\s|[;&|])"""),
        ),
        Rule(
            "rm_mounted_folder",
            "Recursive delete of a folder the user mounted from the device",
            Regex("""\brm\s+(?:-[a-zA-Z]+\s+)*(?:-[a-zA-Z]*[rR][a-zA-Z]*\s+)?/var/minis/mounts/?(?:\*|$|\s|[;&|])"""),
        ),
        Rule(
            "mkfs",
            "Format a filesystem (mkfs)",
            Regex("""\bmkfs(?:\.\w+)?\b"""),
        ),
        Rule(
            "remote_pipe_shell",
            "Pipe a downloaded script straight into a shell",
            Regex("""\b(?:curl|wget)\b[^|;\n]*\|\s*(?:sudo\s+)?(?:ba|z|k)?sh\b"""),
        ),
        Rule(
            "git_force_push",
            "Force-push a branch",
            Regex("""\bgit\s+push\b[^;&|\n]*(?:--force\b|--force-with-lease\b|\s-f\b)"""),
        ),
        Rule(
            "chmod_root",
            "Recursively open up the root filesystem",
            Regex("""\bchmod\s+(?:-[a-zA-Z]+\s+)*(?:-[a-zA-Z]*R[a-zA-Z]*\s+)?(?:777|a\+rwx)\s+/(?=\s|$|[;&|])"""),
        ),
        Rule(
            "shutdown",
            "Shut down or reboot the device",
            Regex("""\b(?:shutdown|reboot|poweroff|halt)\b"""),
        ),
    )

    // ── 组合规则：单独看每一条都不危险，合起来就是绕过 ──────────────────
    //
    // 纯正则只能看"字面写了什么"，而下面三种写法把真正的动作藏在了解析之后：
    //
    //   rm -rf $(cat target.txt)        目标在运行时才展开
    //   echo cm0g | base64 -d | sh      代码在运行时才解码
    //   find / -delete                  不是 rm，删得一样干净
    //
    // 不引入 shell parser（成本不成比例），只把这三种"隐藏执行 + 破坏性"
    // 的组合单独抓出来。注意是**组合**判断：`echo $(date) && rm -rf /tmp`
    // 不该被拦——命令替换里没有破坏性动作。

    /** `$(rm …)` / `` `rm …` ``：替换内部就是破坏性命令。 */
    private val SUBSHELL_DESTRUCTIVE = Regex(
        """(?:${'$'}\(|`)[^)`]*\b(?:rm|dd|mkfs(?:\.\w+)?|shred|truncate|chmod|chown|git\s+push)\b""",
    )

    /** 解码后直接管道给 shell：base64 -d / xxd -r。 */
    private val DECODED_TO_SHELL = Regex(
        """\b(?:base64\s+(?:-d|--decode)|xxd\s+-r)\b[^|;&\n]*\|\s*(?:sudo\s+)?(?:ba|z|k)?sh\b""",
    )

    /** find 的删除形态（-delete / -exec rm）。 */
    private val FIND_DELETE = Regex(
        """\bfind\b[^;&|\n]*(?:-delete\b|-exec\s+rm\b)""",
    )

    /** find 作用在根 / Minis 数据根 / 用户挂载目录上。 */
    private val FIND_ROOT_TARGET = Regex(
        """\bfind\s+(?:/\*?|/var/minis/?|/var/minis/mounts/?)(?=\s|$)""",
    )

    /**
     * 检查一次工具调用。返回 null = 放行。
     *
     * 只对真正会执行命令的工具检查：`shell_execute` 读 `command`；
     * `shell_job` 读 `command`（后台跑同样是执行）。
     */
    fun check(toolName: String, argsJson: String): Hit? {
        val command = when (toolName) {
            "shell_execute", "shell_job" -> runCatching {
                JSONObject(argsJson).optString("command", "")
            }.getOrDefault("")
            else -> return null
        }
        if (command.isBlank()) return null
        // 只看这一条命令，不做 shell 语义分析：命中即问，误报成本是一次点击。
        for (rule in RULES) {
            if (rule.pattern.containsMatchIn(command)) {
                return Hit(rule.id, rule.label)
            }
        }
        if (SUBSHELL_DESTRUCTIVE.containsMatchIn(command)) {
            return Hit(
                "subshell_destructive",
                "Destructive command hidden inside a $( ) substitution or backticks",
            )
        }
        if (DECODED_TO_SHELL.containsMatchIn(command)) {
            return Hit("decoded_pipe_shell", "Decoded data piped straight into a shell")
        }
        if (FIND_DELETE.containsMatchIn(command) && FIND_ROOT_TARGET.containsMatchIn(command)) {
            return Hit(
                "find_delete_root",
                "Recursive delete through find on / , the Minis data root or a mounted folder",
            )
        }
        return null
    }

    /** 单测/设置页展示用：全部规则的 id + 说明。 */
    fun rules(): List<Pair<String, String>> = RULES.map { it.id to it.label }
}