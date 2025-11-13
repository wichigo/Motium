package com.application.motium.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.application.motium.MotiumApplication
import com.application.motium.data.TripRepository

class AutoStartReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_PACKAGE_REPLACED -> {
                MotiumApplication.logger.i("System boot/app update detected", "AutoStart")

                val tripRepository = TripRepository.getInstance(context)

                if (tripRepository.isAutoTrackingEnabled()) {
                    MotiumApplication.logger.i("Auto tracking was enabled, starting service", "AutoStart")

                    try {
                        ActivityRecognitionService.startService(context)
                        MotiumApplication.logger.i("Activity recognition service started successfully", "AutoStart")
                    } catch (e: Exception) {
                        MotiumApplication.logger.e(
                            "Failed to start activity recognition service: ${e.message}",
                            "AutoStart",
                            e
                        )
                    }
                } else {
                    MotiumApplication.logger.i("Auto tracking disabled, not starting service", "AutoStart")
                }
            }
        }
    }
}