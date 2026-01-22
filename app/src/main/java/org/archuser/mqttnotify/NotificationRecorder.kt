package org.archuser.mqttnotify

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object NotificationRecorder {
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    fun record(context: Context, topic: String, payload: String) {
        val recordsDir = File(context.filesDir, "records")
        if (!recordsDir.exists()) {
            recordsDir.mkdirs()
        }
        val fileName = topic.replace("/", "_").replace("#", "_").replace("+", "_")
        val file = File(recordsDir, "$fileName.log")
        val timestamp = dateFormat.format(Date())
        file.appendText("[$timestamp] $payload\n")
    }
}
