package com.unipars.app

import android.app.Application
import com.unipars.app.data.AppDatabase
import com.unipars.app.data.Repository
import com.unipars.app.data.SecurePrefs
import com.unipars.app.notify.Notifier

class UniParsApp : Application() {

    val db: AppDatabase by lazy { AppDatabase.create(this) }
    val repository: Repository by lazy { Repository(db) }
    val prefs: SecurePrefs by lazy { SecurePrefs(this) }
    val notifier: Notifier by lazy { Notifier(this) }

    override fun onCreate() {
        super.onCreate()
        notifier.createChannels()
    }
}
