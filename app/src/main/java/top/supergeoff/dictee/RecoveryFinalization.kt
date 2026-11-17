package top.supergeoff.dictee

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal suspend fun <T> withRecoveryFinalization(
    shouldReconcile: () -> Boolean,
    reconcile: suspend () -> Unit,
    block: suspend () -> T,
): T = try {
    block()
} finally {
    if (shouldReconcile()) withContext(NonCancellable) { reconcile() }
}
