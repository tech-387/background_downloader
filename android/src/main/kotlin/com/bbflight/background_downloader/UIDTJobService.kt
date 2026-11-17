package com.bbflight.background_downloader

import android.app.Notification
import android.app.job.JobParameters
import android.app.job.JobService
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

class UIDTJobService : JobService() {

    val jobs = java.util.concurrent.ConcurrentHashMap<Int, Job>()
    val jobContexts = java.util.concurrent.ConcurrentHashMap<Int, UIDTJobContext>()

    override fun onStartJob(params: JobParameters?): Boolean {
        Log.d(TaskRunner.TAG, "Starting UIDT JobService")
        if (params == null) return false

        val extras = params.extras
        val taskJson = extras.getString(TaskWorker.keyTask)
        if (taskJson == null) {
            Log.e(TaskRunner.TAG, "Task JSON not found in job parameters")
            return false
        }

        val jobContext = UIDTJobContext(this, params)
        try {
            jobContext.task = bdJson.decodeFromString(taskJson)
            jobContext.notificationConfigJsonString = extras.getString(TaskWorker.keyNotificationConfig)
            if (jobContext.notificationConfigJsonString != null) {
                jobContext.notificationConfig = bdJson.decodeFromString(jobContext.notificationConfigJsonString!!)
            }
        } catch (e: Exception) {
            Log.e(TaskRunner.TAG, "Failed to decode task or notification config: $e")
            return false
        }

        // Determine runner based on task type.
        val runner = when (jobContext.task.taskType) {
            "DownloadTask" -> DownloadTaskRunner(jobContext)
            "UriDownloadTask" -> DownloadTaskRunner(jobContext)
            "UploadTask" -> UploadTaskRunner(jobContext)
            "UriUploadTask" -> UploadTaskRunner(jobContext)
            "MultiUploadTask" -> UploadTaskRunner(jobContext)
            "DataTask" -> DataTaskRunner(jobContext)
            "ParallelDownloadTask" -> ParallelDownloadTaskRunner(jobContext)
            else -> {
                Log.e(TaskRunner.TAG, "Unknown task type: ${jobContext.task.taskType}")
                return false
            }
        }

        jobContexts[params.jobId] = jobContext
        val job = CoroutineScope(Dispatchers.IO).launch {
            try {
                runner.run()
                Log.d(TaskRunner.TAG, "UIDT JobService finished for taskId ${jobContext.task.taskId}")
            } finally {
                jobs.remove(params.jobId)
                jobContexts.remove(params.jobId)
                jobFinished(params, false) // retries managed internally
                NotificationService.onUIDTJobFinished(jobContext)
            }
        }
        jobs[params.jobId] = job

        return true // Work is still running on background thread
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        if (params == null) return false
        // The job lost its network: the system runs it again once the network is back, with the
        // same task, also when no Flutter engine runs to retry it. Other stops are not
        // rescheduled; background_downloader manages retries internally
        val lostNetwork = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                params.stopReason == JobParameters.STOP_REASON_CONSTRAINT_CONNECTIVITY
        Log.i(TaskRunner.TAG, "Stopping UIDT JobService${if (lostNetwork) ", lost its network" else ""}")
        jobContexts[params.jobId]?.let {
            it.lostNetwork = lostNetwork
            it.isStopped = true
        }
        jobs.remove(params.jobId)?.cancel()
        jobContexts.remove(params.jobId)
        return lostNetwork
    }

    /**
     * Context for a single job execution, holding the state for that specific task/job
     */
    class UIDTJobContext(val service: JobService, val params: JobParameters) : TaskJobContext {
        // TaskJobContext Properties
        override lateinit var task: Task
        override var notificationConfig: NotificationConfig? = null
        override var notificationId: Int = 0
        override var notificationProgress: Double = 2.0
        override var networkSpeed: Double = -1.0
        override var taskCanResume: Boolean = false
        override var notificationConfigJsonString: String? = null
        override val runId: String? = null
        override var runInForeground: Boolean
            get() = true
            set(_) {} // UIDT always runs in foreground service

        @Volatile
        var isStopped: Boolean = false

        /** Set before [isStopped] when the system stopped the job because it lost its network */
        @Volatile
        var lostNetwork: Boolean = false

        override val reschedulesOnNetworkLoss: Boolean
            get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

        override val waitsForNetwork: Boolean
            get() = lostNetwork

        override val appContext: Context
            get() = service.applicationContext

        override val isTaskStopped: Boolean
            get() = isStopped || !isActive

        override val isActive: Boolean
            get() = !isStopped && ((service as? UIDTJobService)?.jobs?.get(params.jobId)?.isActive ?: true)


        override fun getInputLong(key: String, defaultValue: Long): Long {
            return params.extras.getLong(key, defaultValue)
        }

        override fun getInputString(key: String): String? {
            return params.extras.getString(key)
        }

        /**
         * What the system does with the job's notification when the job ends.
         *
         * When the task shows a notification after it ended (complete, error, paused or
         * canceled), the notification is detached and the plugin updates or removes it
         * afterwards. Otherwise the system removes it together with the job: removing it
         * from the app after [JobService.jobFinished] does not happen when the process is
         * killed right after, which Android does within milliseconds for an app the user
         * swiped away from Recents, and the running notification then stays forever.
         */
        private val jobEndNotificationPolicy: Int
            get() {
                val config = notificationConfig
                val showsNotificationAfterEnd = config != null && (config.complete != null ||
                        config.error != null || config.paused != null || config.canceled != null)
                return if (showsNotificationAfterEnd) JobService.JOB_END_NOTIFICATION_POLICY_DETACH
                else JobService.JOB_END_NOTIFICATION_POLICY_REMOVE
            }

        override suspend fun setForegroundNotification(
            notificationId: Int,
            notification: Notification,
            notificationType: Int
        ) {
            if (Build.VERSION.SDK_INT >= 34) {
                service.setNotification(
                    params,
                    notificationId,
                    notification,
                    jobEndNotificationPolicy
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                service.startForeground(notificationId, notification, notificationType)
            } else {
                service.startForeground(notificationId, notification)
            }
        }

        override suspend fun updateNotification(
            task: Task,
            status: TaskStatus,
            progress: Double,
            timeRemaining: Long
        ) {
            NotificationService.updateNotification(this, status, progress, timeRemaining)
        }

        override fun updateEstimatedNetworkBytes(downloadBytes: Long, uploadBytes: Long) {
            if (Build.VERSION.SDK_INT >= 34) {
                service.updateEstimatedNetworkBytes(params, downloadBytes, uploadBytes)
            }
        }
    }
}
