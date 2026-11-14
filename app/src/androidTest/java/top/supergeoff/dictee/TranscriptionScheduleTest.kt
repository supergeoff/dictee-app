package top.supergeoff.dictee

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TranscriptionScheduleTest {
    private lateinit var manager: WorkManager
    private lateinit var schedule: TranscriptionSchedule

    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().build())
        manager = WorkManager.getInstance(context)
        schedule = TranscriptionSchedule(manager)
    }

    @Test fun repeatedScheduleKeepsOneNetworkConstrainedPeriodicRequest() = runBlocking {
        schedule.enqueue("local-schedule-test")
        schedule.enqueue("local-schedule-test")
        val work = manager.getWorkInfosForUniqueWork(TranscriptionSchedule.name("local-schedule-test")).get()
        assertEquals(1, work.size)
        assertEquals(WorkInfo.State.ENQUEUED, work.single().state)
        assertEquals(androidx.work.NetworkType.CONNECTED, work.single().constraints.requiredNetworkType)
        assertEquals(15 * 60 * 1000L, work.single().periodicityInfo!!.repeatIntervalMillis)
        schedule.cancel("local-schedule-test")
        assertEquals(WorkInfo.State.CANCELLED, manager.getWorkInfosForUniqueWork(TranscriptionSchedule.name("local-schedule-test")).get().single().state)
    }
    @Test fun eachTaskKeepsItsOwnSinglePeriodicRequestAndCancellation() = runBlocking {
        val first = "local-schedule-first"
        val second = "local-schedule-second"
        schedule.enqueue(first); schedule.enqueue(second)
        schedule.enqueue(first); schedule.enqueue(second)
        val firstWork = manager.getWorkInfosForUniqueWork(TranscriptionSchedule.name(first)).get()
        val secondWork = manager.getWorkInfosForUniqueWork(TranscriptionSchedule.name(second)).get()
        for (work in listOf(firstWork, secondWork)) {
            assertEquals(1, work.size)
            assertEquals(WorkInfo.State.ENQUEUED, work.single().state)
            assertEquals(androidx.work.NetworkType.CONNECTED, work.single().constraints.requiredNetworkType)
            assertEquals(15 * 60 * 1000L, work.single().periodicityInfo!!.repeatIntervalMillis)
        }
        assertNotEquals(firstWork.single().id, secondWork.single().id)
        schedule.cancel(first)
        assertEquals(WorkInfo.State.CANCELLED, manager.getWorkInfosForUniqueWork(TranscriptionSchedule.name(first)).get().single().state)
        assertEquals(WorkInfo.State.ENQUEUED, manager.getWorkInfosForUniqueWork(TranscriptionSchedule.name(second)).get().single().state)
        schedule.cancel(second)
        assertEquals(WorkInfo.State.CANCELLED, manager.getWorkInfosForUniqueWork(TranscriptionSchedule.name(second)).get().single().state)
    }

}
