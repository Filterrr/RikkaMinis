package com.openminis.app.sandbox.offload

/**
 * TF-B: explicit terminal-exception taxonomy for the :modelservice streaming
 * protocol, so the caller can classify a remote failure WITHOUT re-sending
 * (duplicate answer) or faking success.
 *
 * Both carry [hadChunks]:
 *   - `hadChunks == false` (worker died / failed before emitting any chunk):
 *     the caller MAY fall back to another provider — nothing was delivered,
 *     re-running produces no duplicate.
 *   - `hadChunks == true` (worker died mid-stream or the stream errored after
 *     content): the caller MUST NOT re-send — the user already saw partial
 *     text; re-running would duplicate it. Surface it as an explicit stream
 *     error instead.
 */
sealed class ModelExecutionStreamException(
    message: String,
    cause: Throwable? = null,
    val hadChunks: Boolean,
) : RuntimeException(message, cause)

/** Worker process died mid-flight (crash / kill / lost race). */
class ModelWorkerDiedException(hadChunks: Boolean, cause: Throwable? = null) :
    ModelExecutionStreamException(
        if (hadChunks) "model worker died mid-stream" else "model worker died before any output",
        cause,
        hadChunks,
    )

/** The worker completed but wrote an explicit error line in the stream. */
class ModelStreamErrorException(message: String, hadChunks: Boolean) :
    ModelExecutionStreamException(message, null, hadChunks)