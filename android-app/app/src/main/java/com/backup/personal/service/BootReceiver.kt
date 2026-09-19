package com.backup.personal.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.backup.personal.data.PreferencesManager

class BootReceiver : BroadcastReceiver {
    constructor() : super()

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            val prefs = PreferencesManager(context)
            if (prefs.isPaired && prefs.autoBackupEnabled) {
                BackupService.startBackup(context)
            }
        }
    }
}
