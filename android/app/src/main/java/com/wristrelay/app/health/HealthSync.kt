package com.wristrelay.app.health

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Health Connect read/write. Requires runtime permission grants.
 */
class HealthSync(private val context: Context) {

    private val client: HealthConnectClient? by lazy {
        try {
            HealthConnectClient.getOrCreate(context)
        } catch (e: Exception) {
            Log.w(TAG, "Health Connect unavailable: " + e.message)
            null
        }
    }

    data class DailySteps(val date: Instant, val steps: Long)

    data class HeartRateSample(val time: Instant, val bpm: Double)

    val isAvailable: Boolean get() = client != null

    /** Total steps for the given period. */
    suspend fun readTotalSteps(start: Instant, end: Instant): Long? = withContext(Dispatchers.IO) {
        val c = client ?: return@withContext null
        try {
            val request = AggregateRequest(
                metrics = setOf(StepsRecord.COUNT_TOTAL),
                timeRangeFilter = TimeRangeFilter.between(start, end)
            )
            val result = c.aggregate(request)
            result[StepsRecord.COUNT_TOTAL]
        } catch (e: Exception) {
            Log.w(TAG, "readTotalSteps failed: " + e.message)
            null
        }
    }

    /** Step records for the period. */
    suspend fun readSteps(start: Instant, end: Instant): List<DailySteps> = withContext(Dispatchers.IO) {
        val c = client ?: return@withContext emptyList()
        try {
            val request = ReadRecordsRequest(
                recordType = StepsRecord::class,
                timeRangeFilter = TimeRangeFilter.between(start, end)
            )
            c.readRecords(request).records.map { DailySteps(it.startTime, it.count) }
        } catch (e: Exception) {
            Log.w(TAG, "readSteps failed: " + e.message)
            emptyList()
        }
    }

    /** Write heart rate samples received from the watch. */
    suspend fun writeHeartRate(samples: List<HeartRateSample>): Boolean = withContext(Dispatchers.IO) {
        val c = client ?: return@withContext false
        try {
            val zone = ZoneOffset.UTC
            val records = samples.map { s ->
                HeartRateRecord(
                    startTime = s.time,
                    startZoneOffset = zone,
                    endTime = s.time,
                    endZoneOffset = zone,
                    samples = listOf(HeartRateRecord.Sample(s.time, s.bpm.toLong())),
                    metadata = Metadata()
                )
            }
            if (records.isNotEmpty()) {
                c.insertRecords(records)
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "writeHeartRate failed: " + e.message)
            false
        }
    }

    companion object {
        private const val TAG = "HealthSync"

        /** Permissions requested from Health Connect. */
        val REQUIRED_PERMISSIONS: Set<String> = setOf(
            "android.permission.health.READ_STEPS",
            "android.permission.health.READ_HEART_RATE",
            "android.permission.health.WRITE_HEART_RATE"
        )
    }
}
