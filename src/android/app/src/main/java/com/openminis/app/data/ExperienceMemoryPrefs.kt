package com.openminis.app.data

import android.content.Context
import android.content.SharedPreferences

/**
 * 经验记忆（episodic memory）全局开关，持久化于 SharedPreferences。
 *
 * 与 [MemoryGlobalPrefs]（语义记忆：agent 主动写事实）正交：
 *  - 语义记忆：agent 主动调 memory_write / memory_get，粒度为一句话；
 *  - 经验记忆：系统自动记录每次完整回合（意图+工具序列+结果），
 *    模型调用前自动检索注入，agent 无感知、不可干预。
 *
 * Pref key `memory.experience.enabled`，默认 **false**（新装默认关闭、opt-in）：
 * 经验记忆会在本地明文记录 query/reply（app 私有目录 episodes.jsonl），
 * 用户可在设置页查看、一键清空；备份规则已排除该文件
 * （见 res/xml/backup_rules.xml 与 data_extraction_rules.xml）。
 * 默认关是隐私优先，用户明确开启后经验才能从使用中生长出来。
 */
object ExperienceMemoryPrefs {
    private const val PREFS = "minis_memory_prefs"
    private const val KEY_ENABLED = "memory.experience.enabled"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }
}
