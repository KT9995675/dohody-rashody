package ru.dohody.rashody

import kotlin.coroutines.cancellation.CancellationException

/** Rethrow coroutine cancellation so it is never shown as a user-facing error. */
fun Exception.rethrowIfCancellation() {
    if (this is CancellationException) throw this
}

fun Exception.userMessage(): String? {
    if (this is CancellationException) return null
    val msg = message?.trim().orEmpty()
    if (msg.isEmpty()) return toString()
    // Defensive: some runtimes spell cancel with one "l".
    if (msg.contains("was cancelled", ignoreCase = true) ||
        msg.contains("was canceled", ignoreCase = true)
    ) {
        return null
    }
    return msg
}
