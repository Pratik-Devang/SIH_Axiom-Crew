package com.percorsa.navigation

import android.content.Context
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class TripLogger(
    private val context: Context
) {

    private val executor = Executors.newSingleThreadExecutor()

    private var logFile: File? = null
    private var writer: BufferedWriter? = null

    @Volatile
    private var tripActive = false

    fun startTrip() {

        if (tripActive) return

        val timestamp =
            SimpleDateFormat(
                "yyyyMMdd_HHmmss",
                Locale.US
            ).format(Date())

        logFile = File(
            context.filesDir,
            "trip_$timestamp.csv"
        )

        try {

            writer = BufferedWriter(
                FileWriter(
                    logFile,
                    false
                )
            )

            tripActive = true

            executor.execute {

                writer?.apply {

                    write(
                        "timestamp_ns,ax,ay,az,gx,gy,gz,quality_flags"
                    )

                    newLine()
                    flush()
                }
            }

        } catch (e: Exception) {

            writer = null
            tripActive = false

            e.printStackTrace()
        }
    }


    fun logSample(sample: SensorSample) {

        if (!tripActive) return

        executor.execute {

            writer?.apply {

                write(
                    "${sample.timestampNs}," +
                            "${sample.ax}," +
                            "${sample.ay}," +
                            "${sample.az}," +
                            "${sample.gx}," +
                            "${sample.gy}," +
                            "${sample.gz}," +
                            "${sample.qualityFlags}"
                )

                newLine()
            }
        }
    }


    fun stopTrip() {

        if (!tripActive) return

        tripActive = false

        executor.execute {

            try {

                writer?.apply {
                    flush()
                    close()
                }

            } catch (e: Exception) {

                e.printStackTrace()

            } finally {

                writer = null
            }
        }
    }


    fun shutdown() {

        stopTrip()

        executor.shutdown()
    }


    fun getCurrentLogFile(): File? {

        return logFile
    }


    fun isTripActive(): Boolean {

        return tripActive
    }
}