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
import androidx.work.workDataOf
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
        val attempt = inputData.getInt(KEY_ATTEMPT, 0)
        val caughtUp = (applicationContext as MaunApp).messenger.fetch()
        DeliveryTrace.stepped(applicationContext, DeliveryTrace.FETCH_DONE, "caughtUp=$caughtUp attempt=$attempt")
        // What could not be fetched in time stays on the relay. It is tried
        // again straight away rather than through WorkManager's retry, which
        // would put a backoff between the attempts that a wake-up arriving in
        // the meantime would have to wait behind.
        if (!caughtUp && attempt + 1 < MAX_ATTEMPTS) {
            schedule(applicationContext, attempt + 1)
            DeliveryTrace.stepped(applicationContext, DeliveryTrace.FETCH_AGAIN, "attempt=${attempt + 1}")
        }
        return Result.success()
    }

    /** Android 11 and older run urgent work as a foreground service, which needs a notification. */
    override suspend fun getForegroundInfo(): ForegroundInfo =
        ForegroundInfo(Notifications.ID_FETCH, Notifications(applicationContext).fetching())

    companion object {
        private const val NOW = "fetch"
        private const val HOURLY = "fetch-hourly"
        private const val KEY_ATTEMPT = "attempt"
        private const val MAX_ATTEMPTS = 3

        private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /**
         * Fetches as soon as Android allows, which is at once unless the app
         * has used up its allowance for urgent work.
         *
         * A wake-up is appended to whatever fetch is already queued or
         * running, never put in its place: cancelling a fetch halfway would
         * throw away the decryption and acknowledgements of a whole batch of
         * messages, and the next wake-up would start it all again. Appending
         * means the fetch that is running finishes, and the new one runs the
         * moment it does. A fetch that is added while another is running has
         * nothing to do but come back for the rest, so the queue cannot grow
         * without bound: the relay wakes a phone at most once every five
         * seconds.
         */
        fun schedule(context: Context, attempt: Int = 0) {
            val request = OneTimeWorkRequestBuilder<FetchWorker>()
                .setConstraints(online)
                .setInputData(workDataOf(KEY_ATTEMPT to attempt))
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }

        /** Stops every fetch, queued or running, and the hourly one: this phone is signed out. */
        fun cancel(context: Context) {
            val work = WorkManager.getInstance(context)
            work.cancelUniqueWork(NOW)
            work.cancelUniqueWork(HOURLY)
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
