package top.supergeoff.dictee

import android.app.ActivityManager
import android.app.NotificationManager
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkServiceLifecycleTest {
    @Suppress("DEPRECATION")
    @Test fun timeoutStopsServiceAndNotificationWithoutCancellingScheduledWork() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<DicteeApp>()
        WorkManagerTestInitHelper.initializeTestWorkManager(app, Configuration.Builder().build())
        val manager = WorkManager.getInstance(app)
        val schedule = TranscriptionSchedule(manager)
        val id = "service-lifecycle-test"
        val activityManager = app.getSystemService(ActivityManager::class.java)
        val notifications = app.getSystemService(NotificationManager::class.java)
        fun running() = activityManager.getRunningServices(Int.MAX_VALUE).any { it.service.className == WorkService::class.java.name }
        val jobs = app.model.jobs
        assertEquals(0, jobs.value)
        assertFalse(running())
        if (Build.VERSION.SDK_INT >= 33 && app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(app.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        schedule.enqueue(id)
        val entered = CompletableDeferred<Unit>()
        val allowTimeout = CompletableDeferred<Unit>()
        val work = async {
            jobs.track {
                entered.complete(Unit)
                allowTimeout.await()
                withTimeoutOrNull(1) { CompletableDeferred<Unit>().await() }
            }
        }
        try {
            withTimeout(5_000) { entered.await() }
            WorkService.start(app)
            withTimeout(5_000) {
                while (!running() || notifications.activeNotifications.none { it.id == 1 }) delay(10)
            }
            allowTimeout.complete(Unit)
            withTimeout(10_000) { work.await() }
            withTimeout(5_000) {
                while (running() || notifications.activeNotifications.any { it.id == 1 }) delay(10)
            }
            assertEquals(WorkInfo.State.ENQUEUED, manager.getWorkInfosForUniqueWork(TranscriptionSchedule.name(id)).get().single().state)
        } finally {
            work.cancel()
            app.stopService(Intent(app, WorkService::class.java))
            schedule.cancel(id)
        }
    }
}
