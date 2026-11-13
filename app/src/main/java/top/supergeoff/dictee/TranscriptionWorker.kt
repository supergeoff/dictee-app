package top.supergeoff.dictee

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** Programmation unique par tâche, confirmée avant la création durable de la tâche. */
internal class TranscriptionSchedule(private val manager: WorkManager) {
    suspend fun enqueue(localId: String) = withContext(Dispatchers.IO) {
        val request = PeriodicWorkRequestBuilder<TranscriptionWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(workDataOf(TranscriptionWorker.LOCAL_ID to localId))
            .build()
        manager.enqueueUniquePeriodicWork(name(localId), ExistingPeriodicWorkPolicy.KEEP, request).result.get()
        Unit
    }

    suspend fun cancel(localId: String) = withContext(Dispatchers.IO) {
        manager.cancelUniqueWork(name(localId)).result.get()
        Unit
    }

    companion object { fun name(localId: String) = "transcription-" + localId }
}

/** Ne démarre jamais de service : la reprise utilise le coordinateur unique de l'application. */
class TranscriptionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val localId = inputData.getString(LOCAL_ID)?.takeIf { it.isNotBlank() } ?: return Result.failure()
        return try {
            val model = (applicationContext as DicteeApp).model
            val scheduler = TranscriptionSchedule(WorkManager.getInstance(applicationContext))
            val runner = TranscriptionWorkerRunner(model.localRepository, model.transcriptionCoordinator, scheduler::cancel)
            when (runner.run(localId, runAttemptCount)) {
                WorkerRun.Finished -> Result.success()
                WorkerRun.Retry -> Result.retry()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object { const val LOCAL_ID = "localId" }
}
