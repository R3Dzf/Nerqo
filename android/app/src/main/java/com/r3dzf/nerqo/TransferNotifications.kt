package com.r3dzf.nerqo

import android.app.*
import android.content.*
import android.net.Uri
import android.os.Build

/** One notification follows the current transfer from preparation to its verified outcome. */
internal object TransferNotifications {
    const val ID = 44888
    const val JOB_ID = "transfer_job_id"
    fun build(context: Context, job: TransferJob?, state: String, detail: String, fraction: Float,
              running: Boolean): Notification {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), flags)
        val percent = (fraction.coerceIn(0f, 1f) * 100).toInt()
        val status = if (running) {
            if (job?.kind?.startsWith("upload") == true) "Sending" else "Receiving"
        } else state
        val title = "$status · $percent% · ${job?.title ?: "Nerqo transfer"}"
        val builder = Notification.Builder(context, "transfers").setSmallIcon(R.drawable.ic_transfer)
            .setContentTitle(title).setContentText(detail)
            .setStyle(Notification.BigTextStyle().bigText(detail)).setContentIntent(open)
            .setOngoing(running).setAutoCancel(!running).setOnlyAlertOnce(true)
            .setCategory(if (running) Notification.CATEGORY_PROGRESS else Notification.CATEGORY_STATUS)
        if (Build.VERSION.SDK_INT >= 31) builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        if (running) builder.setProgress(100, percent, fraction <= 0)
        fun addAction(label: String, name: String) {
            // Bind each action to its job. Tapping an old notification cannot cancel a newer transfer.
            val intent = Intent(context, TransferService::class.java).setAction(name)
                .setData(Uri.parse("nerqo://transfer/${job?.id}/$name")).putExtra(JOB_ID, job?.id)
            val action = PendingIntent.getForegroundService(context, 0, intent, flags)
            builder.addAction(Notification.Action.Builder(null, label, action).build())
        }
        if (running) { addAction("Pause", "pause"); addAction("Cancel", "cancel") }
        else if (state == "Paused" || state == "Interrupted") { addAction("Resume", "resume"); addAction("Cancel", "cancel") }
        return builder.build()
    }
}
