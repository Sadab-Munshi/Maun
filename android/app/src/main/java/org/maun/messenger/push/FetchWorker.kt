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

/** What a finished fetch leaves to be done, if anything. */
internal enum class FetchStep {
    /** Try again in a moment: the wait grows with each try. */
    Again,

    /** Leave one fetch behind for the moment a network exists. Never asked for by that fetch itself. */
    WaitForNetwork,

    /** Nothing is waiting: put away the fetch that was waiting for a network. */
    Stop,
}

/**
 * What follows a fetch that has just finished. [attempt] counts from zero and
 * [maxAttempts] is how many tries a wake-up is worth; after the last of them a
 * fetch that still has something to do leaves one fetch for the moment a
 * network exists instead of trying on a timer. A fetch that was itself waiting
 * for a network keeps that mark through its own tries and stops after them
 * rather than leaving another one behind — otherwise a relay that cannot be
 * reached would leave a phone fetching for ever.
 */
internal fun nextStep(
    caughtUp: Boolean,
    unsent: Int,
    attempt: Int,
    maxAttempts: Int,
    waitingForNetwork: Boolean,
): FetchStep = when {
    caughtUp && unsent == 0 -> FetchStep.Stop
    attempt + 1 < maxAttempts -> FetchStep.Again
    waitingForNetwork -> FetchStep.Stop
    else -> FetchStep.WaitForNetwork
}

/**
 * Connects to the relay, takes in whatever is waiting, confirms it, and
 * disconnects. Run when the phone is woken up, and about once an hour in case
 * a wake-up went missing.
 */
class FetchWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        DeliveryTrace.stepped(applicationContext, DeliveryTrace.FETCH_START)
        val attempt = inputData.getInt(KEY_ATTEMPT, 0)
        val waitingForNetwork = inputData.getBoolean(KEY_WHEN_ONLINE, false)
        val messenger = (applicationContext as MaunApp).messenger
        val caughtUp = messenger.fetch()
        DeliveryTrace.stepped(applicationContext, DeliveryTrace.FETCH_DONE, "caughtUp=$caughtUp attempt=$attempt")
        // What could not be fetched in time stays on the relay, and what could
        // not be sent stays in the outbox. Either is tried again rather than
        // through WorkManager's retry, which would put a backoff between the
        // attempts that a wake-up arriving in the meantime would have to wait
        // behind. The wait between tries grows, so a phone on a bad network is
        // not woken every few seconds, and the hourly fetch is still there for
        // whatever is left after the last try.
        val unsent = messenger.unsentNow()
        when (nextStep(caughtUp, unsent, attempt, MAX_ATTEMPTS, waitingForNetwork)) {
            FetchStep.Again -> {
                // A fetch that waited for a network keeps the mark through its
                // retries, so the end of them is the end of it: without it the
                // last try would leave another wait-for-network fetch behind,
                // and an unreachable relay would have this phone fetching for
                // ever.
                schedule(
                    applicationContext,
                    attempt + 1,
                    delayMillis = RETRY_WAIT_MILLIS * (attempt + 1),
                    whenOnline = waitingForNetwork,
                )
                DeliveryTrace.stepped(
                    applicationContext,
                    DeliveryTrace.FETCH_AGAIN,
                    "attempt=${attempt + 1} unsent=$unsent",
                )
            }
            // The tries above are over and something is still waiting, which
            // most often means the phone had no usable network while they ran.
            // One fetch is left behind that Android runs the moment there is a
            // network again — not on a timer, and not in a loop: what is left
            // of the wait after that is the hourly fetch.
            FetchStep.WaitForNetwork -> {
                scheduleWhenOnline(applicationContext)
                DeliveryTrace.stepped(applicationContext, DeliveryTrace.FETCH_WAITING, "unsent=$unsent")
            }
            FetchStep.Stop -> cancelWhenOnline(applicationContext)
        }
        return Result.success()
    }

    /** Android 11 and older run urgent work as a foreground service, which needs a notification. */
    override suspend fun getForegroundInfo(): ForegroundInfo =
        ForegroundInfo(Notifications.ID_FETCH, Notifications(applicationContext).fetching())

    companion object {
        private const val NOW = "fetch-now-v2"
        private const val RECOVERY = "fetch" // includes delayed work scheduled by older releases
        private const val HOURLY = "fetch-hourly"
        private const val WHEN_ONLINE = "fetch-when-online"
        private const val KEY_ATTEMPT = "attempt"
        private const val KEY_WHEN_ONLINE = "when-online"
        private const val MAX_ATTEMPTS = 3

        /** How long the first repeat waits, before the second: grown by the attempt number. */
        private const val RETRY_WAIT_MILLIS = 20_000L

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
         * moment it does. Recovery jobs use a different chain so yesterday's
         * backoff cannot become a prerequisite of a new push.
         */
        fun schedule(context: Context, attempt: Int = 0, delayMillis: Long = 0L, whenOnline: Boolean = false) {
            val request = OneTimeWorkRequestBuilder<FetchWorker>()
                .setConstraints(online)
                .setInputData(workDataOf(KEY_ATTEMPT to attempt, KEY_WHEN_ONLINE to whenOnline))
                .apply {
                    if (delayMillis > 0) {
                        // A repeat that is waiting for its moment: it must not
                        // hold the phone awake, and urgent work may not be
                        // given a delay at all.
                        setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
                    } else {
                        // A wake-up or the app asking: wanted now.
                        setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    }
                }
                .build()
            val name = if (delayMillis > 0) RECOVERY else NOW
            WorkManager.getInstance(context).enqueueUniqueWork(name, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }

        /**
         * Leaves one fetch behind that runs as soon as there is a network: what
         * a phone that was woken up with nothing to reach asks for. There is at
         * most one of them, and a fetch that catches up takes it away again.
         */
        fun scheduleWhenOnline(context: Context) {
            val request = OneTimeWorkRequestBuilder<FetchWorker>()
                .setConstraints(online)
                .setInputData(workDataOf(KEY_ATTEMPT to 0, KEY_WHEN_ONLINE to true))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WHEN_ONLINE, ExistingWorkPolicy.KEEP, request)
        }

        fun cancelWhenOnline(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WHEN_ONLINE)
        }

        /** Stops every fetch, queued or running, and the hourly one: this phone is signed out. */
        fun cancel(context: Context) {
            val work = WorkManager.getInstance(context)
            work.cancelUniqueWork(NOW)
            work.cancelUniqueWork(RECOVERY)
            work.cancelUniqueWork(HOURLY)
            work.cancelUniqueWork(WHEN_ONLINE)
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
