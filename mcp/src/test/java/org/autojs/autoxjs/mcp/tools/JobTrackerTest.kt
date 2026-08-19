package org.autojs.autoxjs.mcp.tools

import com.google.gson.JsonObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JobTrackerTest {
    @Test
    fun cancelingJobCannotBeOverwrittenByLifecycleCallbacks() {
        val tracker = JobTracker()
        val job = tracker.newJob("loop")
        tracker.attachCancellation(job.jobId, JobCancellation { true })

        assertTrue(tracker.markRunning(job.jobId, "started"))
        assertTrue(tracker.requestCancel(job.jobId) is CancelRequest.Ready)
        assertEquals(JobStatus.Status.CANCELING, tracker.get(job.jobId)?.status)

        assertFalse(tracker.markSuccess(job.jobId, "completed"))
        assertFalse(tracker.markFailed(job.jobId, "interrupted"))
        assertEquals(JobStatus.Status.CANCELING, tracker.get(job.jobId)?.status)

        assertTrue(tracker.markCanceled(job.jobId, "engine stopped"))
        assertEquals(JobStatus.Status.CANCELED, tracker.get(job.jobId)?.status)
    }

    @Test
    fun completedJobRejectsCancellationAndKeepsTerminalStatus() {
        val tracker = JobTracker()
        val job = tracker.newJob("short")
        tracker.attachCancellation(job.jobId, JobCancellation { true })

        assertTrue(tracker.markSuccess(job.jobId, "completed"))
        val request = tracker.requestCancel(job.jobId)

        assertTrue(request is CancelRequest.AlreadyFinished)
        assertEquals(JobStatus.Status.SUCCESS, tracker.get(job.jobId)?.status)
    }

    @Test
    fun cancelToolStopsOnlyRequestedJobAndIsIdempotent() = runBlocking {
        val tracker = JobTracker()
        var firstStops = 0
        var secondStops = 0
        val first = tracker.newJob("first")
        val second = tracker.newJob("second")
        tracker.attachCancellation(first.jobId, JobCancellation {
            firstStops++
            true
        })
        tracker.attachCancellation(second.jobId, JobCancellation {
            secondStops++
            true
        })
        tracker.markRunning(first.jobId, "started")
        tracker.markRunning(second.jobId, "started")
        val tool = CancelJobTool(tracker)

        val firstResponse = tool.handle(params(first.jobId))
        val repeatedResponse = tool.handle(params(first.jobId))

        assertTrue(firstResponse.ok)
        assertTrue(repeatedResponse.ok)
        assertEquals(JobStatus.Status.CANCELED, tracker.get(first.jobId)?.status)
        assertEquals(JobStatus.Status.RUNNING, tracker.get(second.jobId)?.status)
        assertEquals(1, firstStops)
        assertEquals(0, secondStops)
    }

    @Test
    fun cancelTimeoutDoesNotReportCanceled() = runBlocking {
        val tracker = JobTracker()
        val job = tracker.newJob("stuck")
        var attempts = 0
        tracker.attachCancellation(job.jobId, JobCancellation {
            attempts++
            false
        })
        tracker.markRunning(job.jobId, "started")
        val tool = CancelJobTool(tracker)

        val response = tool.handle(params(job.jobId))
        val retryResponse = tool.handle(params(job.jobId))

        assertFalse(response.ok)
        assertFalse(retryResponse.ok)
        assertEquals("CancelTimeout", response.errorCode)
        assertEquals("CancelTimeout", retryResponse.errorCode)
        assertEquals(JobStatus.Status.CANCELING, tracker.get(job.jobId)?.status)
        assertEquals(2, attempts)
    }

    @Test
    fun concurrentCancelRequestDoesNotStartSecondAttempt() = runBlocking {
        val tracker = JobTracker()
        val job = tracker.newJob("slow-stop")
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var attempts = 0
        tracker.attachCancellation(job.jobId, JobCancellation {
            attempts++
            entered.complete(Unit)
            release.await()
            true
        })
        tracker.markRunning(job.jobId, "started")
        val tool = CancelJobTool(tracker)

        val first = async { tool.handle(params(job.jobId)) }
        entered.await()
        val concurrent = tool.handle(params(job.jobId))
        release.complete(Unit)
        val firstResponse = first.await()

        assertFalse(concurrent.ok)
        assertEquals("CancelInProgress", concurrent.errorCode)
        assertTrue(firstResponse.ok)
        assertEquals(1, attempts)
        assertEquals(JobStatus.Status.CANCELED, tracker.get(job.jobId)?.status)
    }

    @Test
    fun cancelFailureDoesNotReportCanceledAndCanBeRetried() = runBlocking {
        val tracker = JobTracker()
        var attempts = 0
        val job = tracker.newJob("failing-stop")
        tracker.attachCancellation(job.jobId, JobCancellation {
            attempts++
            throw IllegalStateException("stop failed")
        })
        tracker.markRunning(job.jobId, "started")
        val tool = CancelJobTool(tracker)

        val firstResponse = tool.handle(params(job.jobId))
        val secondResponse = tool.handle(params(job.jobId))

        assertFalse(firstResponse.ok)
        assertFalse(secondResponse.ok)
        assertEquals("CancelFailed", firstResponse.errorCode)
        assertEquals("CancelFailed", secondResponse.errorCode)
        assertEquals(2, attempts)
        assertEquals(JobStatus.Status.RUNNING, tracker.get(job.jobId)?.status)
        assertTrue(tracker.markSuccess(job.jobId, "completed after cancel failed"))
        assertEquals(JobStatus.Status.SUCCESS, tracker.get(job.jobId)?.status)
    }

    @Test
    fun unknownJobReturnsNotFoundWithoutInvokingCancellation() = runBlocking {
        val response = CancelJobTool(JobTracker()).handle(params(404))

        assertFalse(response.ok)
        assertEquals("NotFound", response.errorCode)
    }

    private fun params(jobId: Int) = JsonObject().apply {
        addProperty("jobId", jobId)
    }
}
