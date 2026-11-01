package com.unipars.app

import android.app.Application
import android.content.Context
import com.unipars.app.data.AppDatabase
import com.unipars.app.data.Repository
import com.unipars.app.data.SecurePrefs
import com.unipars.app.net.AppFetcher
import com.unipars.app.notify.Notifier
import com.unipars.app.work.MaintenanceWorker
import com.unipars.engine.PageFetcher
import java.io.File

class UniParsApp : Application() {

    val db: AppDatabase by lazy { AppDatabase.create(this) }
    val repository: Repository by lazy { Repository(db) }
    val prefs: SecurePrefs by lazy { SecurePrefs(this) }
    val notifier: Notifier by lazy { Notifier(this) }

    /** Swappable in tests. */
    var fetcherFactory: (Context) -> PageFetcher = { AppFetcher(it) }

    val crashFile: File get() = File(filesDir, "last_crash.txt")

    override fun onCreate() {
        super.onCreate()
        installCrashLog()
        notifier.createChannels()
        // WorkManager may be unavailable in some test environments; scheduling is retried on next start.
        runCatching { MaintenanceWorker.schedule(this) }
    }

    /** Keeps the last crash locally so the user can share it from Settings; nothing is uploaded. */
    private fun installCrashLog() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                crashFile.writeText(
                    "Uni-pars ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}), Android ${android.os.Build.VERSION.RELEASE} " +
                        "(${android.os.Build.VERSION.SDK_INT}), ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\n" +
                        "${java.util.Date()} thread=${thread.name}\n\n" + error.stackTraceToString(),
                )
            }
            previous?.uncaughtException(thread, error)
        }
    }
}
