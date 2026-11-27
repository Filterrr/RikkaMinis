package com.openminis.app.data

import android.content.Context

/**
 * [T-token-budget-warning] "跑到这里该看一眼花费了"的门槛。
 *
 * 为什么不直接折算成钱：每个模型的价格不同、缓存命中要打折、用户还可能
 * 在模型组里轮换——折成的数字很快就会和账单不符。token 数是唯一一个既能
 * 拿到、又不会被解释错的量。
 *
 * 0 = 关闭提醒。
 */
object TokenBudgetPrefs {

    private const val PREFS = "token_budget_prefs"
    private const val KEY_WARN_AT = "warnAtTokens"

    /**
     * 默认 200k：一次正常的长任务（十几次工具调用、几次上下文重发）大致在
     * 这个量级。太低会在每个稍长的任务里弹，太高就等于没有。
     */
    const val DEFAULT_WARN_AT: Int = 200_000

    fun warnAt(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_WARN_AT, DEFAULT_WARN_AT)

    fun setWarnAt(context: Context, tokens: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_WARN_AT, tokens.coerceAtLeast(0))
            .apply()
    }
}