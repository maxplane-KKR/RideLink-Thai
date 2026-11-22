package dev.zanderp.opencfmoto

import android.app.Application

class RideLinkApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        TftDiagnostics.initialize(noBackupFilesDir, resources)
    }
}
