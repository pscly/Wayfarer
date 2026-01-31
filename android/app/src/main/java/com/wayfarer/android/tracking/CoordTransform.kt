package com.wayfarer.android.tracking

import android.content.Context
import com.amap.api.maps.CoordinateConverter
import com.amap.api.maps.model.LatLng
import com.wayfarer.android.db.TrackPointEntity

object CoordTransform {
    data class Gcj02Result(
        val latitudeGcj02: Double?,
        val longitudeGcj02: Double?,
        val coordTransformStatus: String,
    )

    fun wgs84ToGcj02BestEffort(
        context: Context,
        latitudeWgs84: Double,
        longitudeWgs84: Double,
        amapKeyPresent: Boolean,
    ): Gcj02Result {
        if (!isInChina(latitudeWgs84, longitudeWgs84)) {
            return Gcj02Result(
                latitudeGcj02 = null,
                longitudeGcj02 = null,
                coordTransformStatus = TrackPointEntity.CoordTransformStatus.OUTSIDE_CN,
            )
        }

        // Keep CI-safe behavior: if key is missing (or map SDK is intentionally unused), bypass.
        if (!amapKeyPresent) {
            return Gcj02Result(
                latitudeGcj02 = null,
                longitudeGcj02 = null,
                coordTransformStatus = TrackPointEntity.CoordTransformStatus.BYPASS,
            )
        }

        return runCatching {
            val converted = CoordinateConverter(context)
                .from(CoordinateConverter.CoordType.GPS)
                .coord(LatLng(latitudeWgs84, longitudeWgs84))
                .convert()

            Gcj02Result(
                latitudeGcj02 = converted.latitude,
                longitudeGcj02 = converted.longitude,
                coordTransformStatus = TrackPointEntity.CoordTransformStatus.OK,
            )
        }.getOrElse {
            Gcj02Result(
                latitudeGcj02 = null,
                longitudeGcj02 = null,
                coordTransformStatus = TrackPointEntity.CoordTransformStatus.FAILED,
            )
        }
    }

    fun isInChina(latitude: Double, longitude: Double): Boolean {
        // Rough bounding box is sufficient for best-effort GCJ-02 conversion gating.
        return latitude in 0.8293..55.8271 && longitude in 72.004..137.8347
    }
}
