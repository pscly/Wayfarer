package com.wayfarer.android.tracking

import android.content.Context
import android.content.Intent

object TrackingServiceController {
    fun start(context: Context) {
        val intent = Intent(context, TrackingForegroundService::class.java)
            .setAction(TrackingForegroundService.ACTION_START)
        context.startForegroundService(intent)
    }

    fun stop(context: Context) {
        val intent = Intent(context, TrackingForegroundService::class.java)
            .setAction(TrackingForegroundService.ACTION_STOP)
        context.startService(intent)
    }
}
