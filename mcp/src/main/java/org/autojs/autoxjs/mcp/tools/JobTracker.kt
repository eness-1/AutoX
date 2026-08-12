package org.autojs.autoxjs.mcp.tools

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

data class JobStatus(
    val jobId: Int,
    val name: String,
    val status: Status,
    val message: String? = null
) {
    enum class Status {
        SUBMITTED, RUNNING, CANCELING, SUCCESS, FAILED, CANCELED
    }
}

fun interface JobCancellation {
    suspend fun cancelAndAwait(timeoutMillis: Long): Boolean
}

sealed interface CancelRequest {
    data class Ready(val cancellation: JobCancellation) : CancelRequest
    data object AlreadyCanceled : CancelRequest
    data class AlreadyFinished(val status: JobStatus.Status) : CancelRequest
    data object InProgress : CancelRequest
    data object NotFound : CancelRequest
    data object NotReady : CancelRequest
}

class JobTracker {
    private val idGen = AtomicInteger(1)
    private data class Entry(
        val status: JobStatus,
        val cancellation: JobCancellation? = null,
        val statusBeforeCancel: JobStatus.Status? = null,
        val cancelAttemptInProgress: Boolean = false
    )

    private val jobs = ConcurrentHashMap<Int, Entry>()

    fun newJob(name: String): JobStatus {
        val id = idGen.getAndIncrement()
        val status = JobStatus(id, name, JobStatus.Status.SUBMITTED)
        jobs[id] = Entry(status)
        return status
    }

    fun attachCancellation(id: Int, cancellation: JobCancellation): Boolean {
        var attached = false
        jobs.computeIfPresent(id) { _, entry ->
            if (entry.status.status.isTerminal()) {
                entry
            } else {
                attached = true
                entry.copy(cancellation = cancellation)
            }
        }
        return attached
    }

    fun markRunning(id: Int, message: String? = null) = transition(
        id,
        allowedFrom = setOf(JobStatus.Status.SUBMITTED),
        target = JobStatus.Status.RUNNING,
        message = message
    )

    fun markSuccess(id: Int, message: String? = null) = transition(
        id,
        allowedFrom = setOf(JobStatus.Status.SUBMITTED, JobStatus.Status.RUNNING),
        target = JobStatus.Status.SUCCESS,
        message = message
    )

    fun markFailed(id: Int, message: String? = null) = transition(
        id,
        allowedFrom = setOf(JobStatus.Status.SUBMITTED, JobStatus.Status.RUNNING),
        target = JobStatus.Status.FAILED,
        message = message
    )

    fun requestCancel(id: Int): CancelRequest {
        var result: CancelRequest = CancelRequest.NotFound
        jobs.compute(id) { _, entry ->
            if (entry == null) {
                result = CancelRequest.NotFound
                null
            } else {
                when (entry.status.status) {
                    JobStatus.Status.CANCELED -> {
                        result = CancelRequest.AlreadyCanceled
                        entry
                    }
                    JobStatus.Status.SUCCESS, JobStatus.Status.FAILED -> {
                        result = CancelRequest.AlreadyFinished(entry.status.status)
                        entry
                    }
                    JobStatus.Status.CANCELING -> {
                        when {
                            entry.cancelAttemptInProgress -> {
                                result = CancelRequest.InProgress
                                entry
                            }
                            entry.cancellation == null -> {
                                result = CancelRequest.NotReady
                                entry
                            }
                            else -> {
                                result = CancelRequest.Ready(entry.cancellation)
                                entry.copy(cancelAttemptInProgress = true)
                            }
                        }
                    }
                    JobStatus.Status.SUBMITTED, JobStatus.Status.RUNNING -> {
                        val cancellation = entry.cancellation
                        if (cancellation == null) {
                            result = CancelRequest.NotReady
                            entry
                        } else {
                            result = CancelRequest.Ready(cancellation)
                            entry.copy(
                                status = entry.status.copy(
                                    status = JobStatus.Status.CANCELING,
                                    message = "cancel requested"
                                ),
                                statusBeforeCancel = entry.status.status,
                                cancelAttemptInProgress = true
                            )
                        }
                    }
                }
            }
        }
        return result
    }

    fun markCanceled(id: Int, message: String? = null) = transition(
        id,
        allowedFrom = setOf(JobStatus.Status.CANCELING),
        target = JobStatus.Status.CANCELED,
        message = message
    )

    fun finishCancelAttemptAfterTimeout(id: Int): Boolean {
        var changed = false
        jobs.computeIfPresent(id) { _, entry ->
            if (entry.status.status != JobStatus.Status.CANCELING || !entry.cancelAttemptInProgress) {
                entry
            } else {
                changed = true
                entry.copy(cancelAttemptInProgress = false)
            }
        }
        return changed
    }

    fun restoreAfterCancelFailure(id: Int, message: String? = null): Boolean {
        var changed = false
        jobs.computeIfPresent(id) { _, entry ->
            if (entry.status.status != JobStatus.Status.CANCELING) {
                entry
            } else {
                changed = true
                entry.copy(
                    status = entry.status.copy(
                        status = entry.statusBeforeCancel ?: JobStatus.Status.RUNNING,
                        message = message
                    ),
                    statusBeforeCancel = null,
                    cancelAttemptInProgress = false
                )
            }
        }
        return changed
    }

    fun get(id: Int): JobStatus? = jobs[id]?.status

    fun recent(limit: Int = 20): List<JobStatus> = jobs.values
        .map { it.status }
        .sortedBy { it.jobId }
        .takeLast(limit)

    private fun transition(
        id: Int,
        allowedFrom: Set<JobStatus.Status>,
        target: JobStatus.Status,
        message: String?
    ): Boolean {
        var changed = false
        jobs.computeIfPresent(id) { _, entry ->
            if (entry.status.status !in allowedFrom) {
                entry
            } else {
                changed = true
                entry.copy(
                    status = entry.status.copy(status = target, message = message),
                    cancellation = if (target.isTerminal()) null else entry.cancellation,
                    statusBeforeCancel = if (target.isTerminal()) null else entry.statusBeforeCancel,
                    cancelAttemptInProgress = if (target.isTerminal()) false else entry.cancelAttemptInProgress
                )
            }
        }
        return changed
    }

    private fun JobStatus.Status.isTerminal(): Boolean = when (this) {
        JobStatus.Status.SUCCESS, JobStatus.Status.FAILED, JobStatus.Status.CANCELED -> true
        else -> false
    }
}
