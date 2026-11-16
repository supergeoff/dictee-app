package top.supergeoff.dictee

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ProcessingNotificationsTest {
    @Test fun deniedPermissionLeavesDurableResultAvailable() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= 33)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val file = File(context.cacheDir, "processing-notice-test.json")
        val manager = context.getSystemService(NotificationManager::class.java)
        val id = "denied-notice-test"
        try {
            automation.revokeRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
            manager.cancel(id, 0)
            val task = TranscriptionTask(id, "recording", "a.mp3", "a.vtt", "folder", "hash", "https://example.invalid/v1", "ref", "fr", "model", TranscriptionPhase.PENDING)
            val store = LocalStateStore(file)
            store.save(LocalState(transcriptionTasks = listOf(task)))
            val repo = LocalStateRepository(store)
            val credentials = object : CredentialStore {
                override fun save(secret: String) = "ref"
                override fun read(ref: String) = "synthetic"
                override fun remove(ref: String) = Unit
            }
            val client = object : TranscriptionClient {
                override suspend fun submit(task: TranscriptionTask) = SubmitResult.BackgroundUnsupported
                override suspend fun get(task: TranscriptionTask): ServerTask = error("unexpected")
                override suspend fun cancel(task: TranscriptionTask): ServerTask = error("unexpected")
                override suspend fun test(settings: TranscriptionSettings) = Unit
            }
            val coordinator = TranscriptionCoordinator(repo, client, TranscriptionSettingsManager(repo, credentials), RecoverySafety(repo),
                verifyAudio = { true }, onProcessingResult = ProcessingNotifications(context)::show)
            assertEquals(RunOutcome.Failed, coordinator.runBounded(id))
            assertEquals(TranscriptionPhase.FAILED, store.load().transcriptionTasks.single().phase)
            assertFalse(manager.activeNotifications.any { it.tag == id })
        } finally {
            manager.cancel(id, 0)
            file.delete()
            if (granted) automation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
