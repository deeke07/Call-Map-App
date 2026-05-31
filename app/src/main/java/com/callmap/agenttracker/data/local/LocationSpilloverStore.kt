package com.callmap.agenttracker.data.local

import android.content.Context
import com.callmap.agenttracker.data.local.entity.LocationEntity
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Append-only file backup when Room insert fails (corruption, disk pressure, etc.).
 * Imported back into Room on init / sync.
 */
@Singleton
class LocationSpilloverStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gson: Gson
) {
    private val spillFile: File
        get() = File(context.filesDir, "location_spillover.jsonl")

    suspend fun append(location: LocationEntity, source: String) = withContext(Dispatchers.IO) {
        val line = gson.toJson(
            SpillLine(
                latitude = location.latitude,
                longitude = location.longitude,
                batteryLevel = location.batteryLevel,
                recordedAt = location.recordedAt,
                source = source
            )
        )
        spillFile.appendText(line + "\n")
    }

    suspend fun readAll(): List<SpillLine> = withContext(Dispatchers.IO) {
        if (!spillFile.exists()) return@withContext emptyList()
        spillFile.readLines()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                runCatching { gson.fromJson(line, SpillLine::class.java) }.getOrNull()
            }
    }

    suspend fun rewrite(remaining: List<SpillLine>) = withContext(Dispatchers.IO) {
        if (remaining.isEmpty()) {
            if (spillFile.exists()) spillFile.delete()
        } else {
            spillFile.writeText(remaining.joinToString("\n") { gson.toJson(it) } + "\n")
        }
    }

    suspend fun count(): Int = withContext(Dispatchers.IO) {
        if (!spillFile.exists()) 0 else spillFile.readLines().count { it.isNotBlank() }
    }

    data class SpillLine(
        @SerializedName("latitude") val latitude: Double,
        @SerializedName("longitude") val longitude: Double,
        @SerializedName("batteryLevel") val batteryLevel: Int?,
        @SerializedName("recordedAt") val recordedAt: String,
        @SerializedName("source") val source: String = "spillover"
    ) {
        fun toEntity() = LocationEntity(
            latitude = latitude,
            longitude = longitude,
            batteryLevel = batteryLevel,
            recordedAt = recordedAt
        )
    }
}
