package org.maun.messenger.push

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.UUID
import org.maun.messenger.MaunApp
import org.maun.messenger.data.DatabaseKey

/** Finishes the local delivery if Android reclaims the process after the push callback. */
class ChatPushWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val sealed = inputData.getByteArray(RECORD) ?: return Result.failure()
        val raw = runCatching { DatabaseKey.openPushWork(sealed) }.getOrNull() ?: return Result.failure()
        val part = ChatPushPart.parse(raw) ?: return Result.failure()
        val messenger = (applicationContext as MaunApp).messenger
        messenger.saveChatPush(part)
        return Result.success()
    }

    override suspend fun getForegroundInfo() =
        ForegroundInfo(Notifications.ID_FETCH, Notifications(applicationContext).fetching())

    companion object {
        private const val RECORD = "record"
        internal const val TAG = "local-chat-push"

        internal fun retain(context: Context, raw: ByteArray): UUID {
            val request = OneTimeWorkRequestBuilder<ChatPushWorker>()
                .setInputData(workDataOf(RECORD to DatabaseKey.sealPushWork(raw)))
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .addTag(TAG)
                .build()
            WorkManager.getInstance(context).enqueue(request)
            return request.id
        }
    }
}
