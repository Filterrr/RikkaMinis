package com.openminis.app.speech

import android.content.Context

/**
 * [T-read-aloud] 应用级 TTS 单例。
 *
 * 之前 `TextToSpeechManager` 只被 `SpeakOffloadHandler`（android-speak CLI）
 * 私有一份实例，UI 层想朗读没有入口——而 `SpeechSentenceSplitter` 那套流式
 * 断句本来就是为朗读 UI 写的，删了 UI 之后成了孤儿。
 *
 * 这里只做一件事：把"谁持有 TTS 引擎"收敛成一个进程级对象，UI 和 CLI 各
 * 拿同一份，避免两边同时初始化导致后初始化的把先初始化的打断（Android 的
 * TextToSpeech 是独占的，两个实例一起 speak 会互相截断）。
 *
 * 用 applicationContext，不持有 Activity，不会泄漏。
 */
object ReadAloudController {

    @Volatile
    private var manager: TextToSpeechManager? = null

    /** 引擎是否已经就绪（TTS 初始化是异步的，冷启动首秒内可能还没好）。 */
    val isReady: Boolean
        get() = manager?.isInitialized == true

    /**
     * 当前是否在朗读。没有实例时返回 null——调用方据此决定要不要显示
     * "停止朗读"按钮，而不是拿到一个永远 false 的状态流。
     */
    val speakingState: kotlinx.coroutines.flow.StateFlow<Boolean>?
        get() = manager?.isSpeaking

    @Synchronized
    private fun ensure(context: Context): TextToSpeechManager {
        manager?.let { return it }
        val app = context.applicationContext ?: context
        return TextToSpeechManager().also {
            it.init(app)
            manager = it
        }
    }

    /**
     * 朗读一段文本（QUEUE_FLUSH：先掐掉上一段，避免两条消息叠着念）。
     * 冷启动时引擎可能还没 ready，直接 no-op——比阻塞 UI 等 2 秒强。
     */
    fun speak(context: Context, text: String) {
        if (text.isBlank()) return
        val m = ensure(context)
        if (!m.isInitialized) return
        m.speak(text)
    }

    fun stop() {
        manager?.stop()
    }
}