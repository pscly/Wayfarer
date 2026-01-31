package com.wayfarer.android.tracking

import java.security.MessageDigest
import java.util.Locale
import kotlin.math.round

object GeomHash {
    /**
     * Definition from plan-supplement:
     * - lat_round=round(lat, 5), lon_round=round(lon, 5)
     * - string = "{lat_round},{lon_round}"
     * - geom_hash = sha256(hex)
     */
    fun sha256LatLonWgs84(lat: Double, lon: Double): String {
        val latRound = round(lat * 1e5) / 1e5
        val lonRound = round(lon * 1e5) / 1e5
        val s = String.format(Locale.US, "%.5f,%.5f", latRound, lonRound)
        val digest = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { b -> "%02x".format(b) }
    }
}
