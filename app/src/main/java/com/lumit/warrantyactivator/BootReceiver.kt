package com.lumit.warrantyactivator

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (
            action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
            action == Intent.ACTION_USER_UNLOCKED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == Intent.ACTION_PACKAGE_ADDED ||
            action == Intent.ACTION_PACKAGE_REPLACED
        ) {
            // If PACKAGE_ADDED, ensure it is our own package before starting
            if (action == Intent.ACTION_PACKAGE_ADDED) {
                val addedPkg = intent.data?.schemeSpecificPart
                if (addedPkg != context.packageName) return
            }
            UsageTrackerService.start(context)
        }
    }
}


