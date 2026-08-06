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
 * Pref key `memory.experience.enabled`，默认 **true**：纯本地纯文本文件
 * （app 私有目录），可一键清空，无隐私泄漏风险；默认开才能让功能
 * 开箱即用——经验只能从使用中生长出来。
 */
object ExperienceMemoryPrefs {
    private const val PREFS = "minis_memory_prefs"
    private const val KEY_ENABLED = "memory.experience.enabled"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }
}
