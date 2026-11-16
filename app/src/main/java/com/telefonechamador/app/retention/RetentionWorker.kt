package com.telefonechamador.app.retention

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.telefonechamador.app.data.CallRepository
import com.telefonechamador.app.util.Notifications
import java.util.concurrent.TimeUnit

/**
 * Limpeza automática: conversas mais antigas que N dias são removidas
 * (favoritas com ★ nunca). Diária via WorkManager + discreta ao encerrar.
 */
class RetentionWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val deleted = try {
            CallRepository(applicationContext).sweepRetention()
        } catch (_: Throwable) {
            0
        }
        Notifications.cleanup(applicationContext, deleted)
        return Result.success()
    }

    companion object {
        private const val NAME = "limpeza_diaria"

        fun schedule(context: Context) {
            try {
                val req = PeriodicWorkRequest.Builder(RetentionWorker::class.java, 24, TimeUnit.HOURS)
                    .setConstraints(
                        Constraints.Builder().setRequiresBatteryNotLow(true).build()
                    )
                    .build()
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    NAME, ExistingPeriodicWorkPolicy.KEEP, req,
                )
            } catch (_: Throwable) { }
        }

        fun runOnce(context: Context) {
            try {
                WorkManager.getInstance(context)
                    .enqueue(OneTimeWorkRequest.Builder(RetentionWorker::class.java).build())
            } catch (_: Throwable) { }
        }
    }
}
