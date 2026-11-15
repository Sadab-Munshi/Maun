package org.maun.messenger.push

import android.content.Context
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
        val caughtUp = (applicationContext as MaunApp).messenger.fetch()
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

        private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /**
         * Fetches as soon as Android allows, which is at once unless the app
         * has used up its allowance for urgent work. A fetch asked for while
         * one is running starts after it: the running one may already be past
         * the point where it would notice a new message.
         */
        fun schedule(context: Context) {
            val request = OneTimeWorkRequestBuilder<FetchWorker>()
                .setConstraints(online)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
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
