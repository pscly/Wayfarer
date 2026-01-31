package com.wayfarer.android.tracking

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.pm.PackageManager
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import com.wayfarer.android.R
import com.wayfarer.android.amap.AmapApiKey
import com.wayfarer.android.db.TrackPointEntity
import com.wayfarer.android.tracking.fsm.SmartSamplingFsm
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityRecognitionResult
import com.google.android.gms.location.DetectedActivity
import java.time.Instant
import java.util.UUID

class TrackingForegroundService : Service(), LocationListener {
    companion object {
        const val ACTION_START = "com.wayfarer.android.tracking.action.START"
        const val ACTION_STOP = "com.wayfarer.android.tracking.action.STOP"
        const val ACTION_ACTIVITY_UPDATE = "com.wayfarer.android.tracking.action.ACTIVITY_UPDATE"

        private const val NOTIFICATION_CHANNEL_ID = "wayfarer_tracking"
        private const val NOTIFICATION_ID = 1001

        // Bootstrapping defaults: UNKNOWN 10s / 10m.
        private const val DEFAULT_INTERVAL_MS = 10_000L
        private const val DEFAULT_MIN_DISTANCE_M = 10f

        // Until auth is wired, all points are stored under a single local user bucket.
        private const val USER_ID_LOCAL = "local"

        private const val ACTIVITY_UPDATES_REQUEST_CODE = 2201
        private const val ACTIVITY_UPDATE_INTERVAL_MS = 5_000L
    }

    private lateinit var locationManager: LocationManager
    private lateinit var repository: TrackPointRepository
    private val fsm: SmartSamplingFsm = SmartSamplingFsm()

    private var amapKeyPresent: Boolean = false

    private var isCapturing: Boolean = false
    private var gpsAvailableHint: Boolean = true

    private var activityPermissionGranted: Boolean = false
    private var latestActivity: SmartSamplingFsm.ActivityType? = null
    private var activityUpdatesPendingIntent: PendingIntent? = null

    private var lastRequestedIntervalMs: Long? = null
    private var lastRequestedMinDistanceM: Float? = null

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        repository = TrackPointRepository(this)

        // GCJ-02 conversion is best-effort and should be bypassed in CI when key is missing.
        amapKeyPresent = runCatching {
            AmapApiKey.isPresent(AmapApiKey.readFromManifest(this))
        }.getOrDefault(false)

        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startTracking()
            ACTION_STOP -> stopTracking()
            ACTION_ACTIVITY_UPDATE -> handleActivityUpdate(intent)
            else -> Unit
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopActivityUpdates()
        stopLocationUpdates()
        super.onDestroy()
    }

    private fun startTracking() {
        isCapturing = true
        startForeground(NOTIFICATION_ID, buildNotification())

        // Best-effort: use Activity Recognition if runtime permission is present.
        startActivityUpdatesIfPermitted()

        // Best-effort: request location updates only when runtime permissions are present.
        requestLocationUpdatesIfPermitted(
            intervalMs = DEFAULT_INTERVAL_MS,
            minDistanceM = DEFAULT_MIN_DISTANCE_M,
        )
    }

    private fun stopTracking() {
        isCapturing = false
        stopActivityUpdates()
        stopLocationUpdates()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun hasActivityRecognitionPermission(): Boolean {
        // Permission exists since Q; minSdk is currently 33 but keep this defensive.
        if (Build.VERSION.SDK_INT < 29) return true
        return checkSelfPermission(android.Manifest.permission.ACTIVITY_RECOGNITION) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun startActivityUpdatesIfPermitted() {
        if (!hasActivityRecognitionPermission()) {
            activityPermissionGranted = false
            latestActivity = null
            return
        }

        activityPermissionGranted = true

        runCatching {
            val pendingIntent = activityUpdatesPendingIntent ?: buildActivityUpdatePendingIntent().also {
                activityUpdatesPendingIntent = it
            }
            ActivityRecognition.getClient(this)
                .requestActivityUpdates(ACTIVITY_UPDATE_INTERVAL_MS, pendingIntent)
        }.onFailure {
            // Missing Play Services / runtime errors should not crash tracking.
            activityPermissionGranted = false
            latestActivity = null
        }
    }

    private fun stopActivityUpdates() {
        val pendingIntent = activityUpdatesPendingIntent ?: return
        runCatching {
            ActivityRecognition.getClient(this)
                .removeActivityUpdates(pendingIntent)
        }
        activityUpdatesPendingIntent = null
        activityPermissionGranted = false
        latestActivity = null
    }

    private fun buildActivityUpdatePendingIntent(): PendingIntent {
        val intent = Intent(this, TrackingForegroundService::class.java)
            .setAction(ACTION_ACTIVITY_UPDATE)

        val mutabilityFlag = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getService(
            this,
            ACTIVITY_UPDATES_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or mutabilityFlag,
        )
    }

    private fun handleActivityUpdate(intent: Intent) {
        if (!isCapturing) return

        // Treat Activity Recognition as best-effort. If anything looks off, keep GPS-only.
        if (!activityPermissionGranted) return

        val activityType = runCatching {
            if (!ActivityRecognitionResult.hasResult(intent)) return@runCatching null
            val result = ActivityRecognitionResult.extractResult(intent) ?: return@runCatching null
            mapDetectedActivity(result.mostProbableActivity)
        }.getOrNull() ?: return

        latestActivity = activityType

        val output = fsm.step(
            SmartSamplingFsm.Input(
                nowMs = System.currentTimeMillis(),
                isCapturing = true,
                gpsAvailable = gpsAvailableHint,
                speedMps = null,
                accuracyM = null,
                activityPermissionGranted = true,
                activity = activityType,
            ),
        )
        maybeUpdateLocationRequest(output.profile)
    }

    private fun mapDetectedActivity(detected: DetectedActivity): SmartSamplingFsm.ActivityType? {
        return when (detected.type) {
            DetectedActivity.STILL -> SmartSamplingFsm.ActivityType.STILL
            DetectedActivity.WALKING -> SmartSamplingFsm.ActivityType.WALKING
            DetectedActivity.RUNNING -> SmartSamplingFsm.ActivityType.RUNNING
            DetectedActivity.ON_BICYCLE -> SmartSamplingFsm.ActivityType.ON_BICYCLE
            DetectedActivity.IN_VEHICLE -> SmartSamplingFsm.ActivityType.IN_VEHICLE
            // ON_FOOT can be either walking or running; keep it conservative.
            DetectedActivity.ON_FOOT -> SmartSamplingFsm.ActivityType.WALKING
            else -> null
        }
    }

    private fun ensureNotificationChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val existing = nm.getNotificationChannel(NOTIFICATION_CHANNEL_ID)
        if (existing != null) return

        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "Wayfarer Tracking",
            NotificationManager.IMPORTANCE_LOW,
        )
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Wayfarer")
            .setContentText("Tracking is running")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .build()
    }

    private fun hasFineLocationPermission(): Boolean {
        return checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun hasCoarseLocationPermission(): Boolean {
        return checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun requestLocationUpdatesIfPermitted(intervalMs: Long, minDistanceM: Float) {
        if (!hasFineLocationPermission() && !hasCoarseLocationPermission()) {
            return
        }

        val provider = when {
            runCatching { locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false) ->
                LocationManager.GPS_PROVIDER
            runCatching { locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrDefault(false) ->
                LocationManager.NETWORK_PROVIDER
            else -> null
        } ?: return

        //noinspection MissingPermission
        locationManager.requestLocationUpdates(
            provider,
            intervalMs,
            minDistanceM,
            this,
            Looper.getMainLooper(),
        )
        lastRequestedIntervalMs = intervalMs
        lastRequestedMinDistanceM = minDistanceM
    }

    private fun stopLocationUpdates() {
        runCatching { locationManager.removeUpdates(this) }
        lastRequestedIntervalMs = null
        lastRequestedMinDistanceM = null
    }

    override fun onLocationChanged(location: Location) {
        gpsAvailableHint = true
        val nowMs = System.currentTimeMillis()
        val output = fsm.step(
            SmartSamplingFsm.Input(
                nowMs = nowMs,
                isCapturing = isCapturing,
                gpsAvailable = true,
                speedMps = if (location.hasSpeed()) location.speed.toDouble() else null,
                accuracyM = if (location.hasAccuracy()) location.accuracy.toDouble() else null,
                activityPermissionGranted = activityPermissionGranted,
                activity = latestActivity,
            ),
        )

        maybeUpdateLocationRequest(output.profile)

        val recordedAt = Instant.ofEpochMilli(location.time).toString()
        val clientPointId = UUID.randomUUID().toString()
        val lat = location.latitude
        val lon = location.longitude

        val gcj02 = CoordTransform.wgs84ToGcj02BestEffort(
            context = this,
            latitudeWgs84 = lat,
            longitudeWgs84 = lon,
            amapKeyPresent = amapKeyPresent,
        )

        val entity = TrackPointEntity(
            userId = USER_ID_LOCAL,
            clientPointId = clientPointId,
            recordedAtUtc = recordedAt,
            latitudeWgs84 = lat,
            longitudeWgs84 = lon,
            latitudeGcj02 = gcj02.latitudeGcj02,
            longitudeGcj02 = gcj02.longitudeGcj02,
            coordSource = TrackPointEntity.CoordSource.GPS,
            coordTransformStatus = gcj02.coordTransformStatus,
            accuracyM = if (location.hasAccuracy()) location.accuracy.toDouble() else null,
            altitudeM = if (location.hasAltitude()) location.altitude else null,
            speedMps = if (location.hasSpeed()) location.speed.toDouble() else null,
            bearingDeg = if (location.hasBearing()) location.bearing.toDouble() else null,
            geomHash = GeomHash.sha256LatLonWgs84(lat, lon),
            createdAtUtc = recordedAt,
            updatedAtUtc = recordedAt,
        )
        repository.insertAsync(entity)
    }

    private fun maybeUpdateLocationRequest(profile: SmartSamplingFsm.SamplingProfile) {
        val intervalSec = profile.intervalSec
        val minDistance = profile.minDistanceM

        if (intervalSec == null || minDistance == null) return

        val intervalMs = intervalSec * 1_000L
        val unchanged = intervalMs == lastRequestedIntervalMs && minDistance == lastRequestedMinDistanceM
        if (unchanged) return

        // Re-register with new sampling profile.
        stopLocationUpdates()
        requestLocationUpdatesIfPermitted(intervalMs = intervalMs, minDistanceM = minDistance)
    }

    override fun onProviderDisabled(provider: String) {
        gpsAvailableHint = false
        // Drive the FSM into UNKNOWN if GPS goes away long enough.
        fsm.step(
            SmartSamplingFsm.Input(
                nowMs = System.currentTimeMillis(),
                isCapturing = isCapturing,
                gpsAvailable = false,
                speedMps = null,
                accuracyM = null,
                activityPermissionGranted = activityPermissionGranted,
                activity = latestActivity,
            ),
        )
    }

    override fun onProviderEnabled(provider: String) {
        // No-op; next location fix will converge the FSM.
    }

    @Suppress("DEPRECATION")
    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {
        // Deprecated; kept for older LocationListener contract compatibility.
    }
}
