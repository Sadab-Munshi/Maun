package org.maun.messenger.push

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import org.maun.messenger.MaunApp

/**
 * Connects to the relay, takes in whatever is waiting, confirms it, and
 * disconnects. Run when the phone is woken up, and about once an hour in case
 * a wake-up went missing.
 */
class FetchWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        DeliveryTrace.stepped(applicationContext, DeliveryTrace.FETCH_START)
        val caughtUp = (applicationContext as MaunApp).messenger.fetch()
        DeliveryTrace.stepped(applicationContext, DeliveryTrace.FETCH_DONE, "caughtUp=$caughtUp attempt=$runAttemptCount")
        // What could not be fetched in time stays on the relay; try a few times, then
        // leave it to the next wake-up.
        return if (caughtUp || runAttemptCount >= MAX_ATTEMPTS) Result.success() else Result.retry()
    }

    /** Android 11 and older run urgent work as a foreground service, which needs a notification. */
    override suspend fun getForegroundInfo(): ForegroundInfo =
        ForegroundInfo(Notifications.ID_FETCH, Notifications(applicationContext).fetching())

    companion object {
        private const val NOW = "fetch"
        private const val HOURLY = "fetch-hourly"
        private const val MAX_ATTEMPTS = 3
        private const val MIN_BACKOFF_SECONDS = 5L

        private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /**
         * Fetches as soon as Android allows, which is at once unless the app
         * has used up its allowance for urgent work.
         *
         * A wake-up replaces whatever fetch is waiting rather than queueing
         * behind it: a fetch that is being retried may be a minute away, and
         * the messages that woke the phone now are in the same queue the retry
         * would fetch. Replacing it also keeps the wait from growing with each
         * message that arrives in the meantime.
         */
        fun schedule(context: Context) {
            val request = OneTimeWorkRequestBuilder<FetchWorker>()
                .setConstraints(online)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                // A retry is worth waiting a few seconds for, not half a minute.
                .setBackoffCriteria(BackoffPolicy.LINEAR, MIN_BACKOFF_SECONDS, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, request)
        }

        /** Safe to call every time the app starts: an existing schedule is kept. */
        fun scheduleHourly(context: Context) {
            val request = PeriodicWorkRequestBuilder<FetchWorker>(1, TimeUnit.HOURS)
                .setConstraints(online)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(HOURLY, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
