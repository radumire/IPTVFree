package com.example.motor.config

import android.app.ActivityManager
import android.content.Context
import android.util.Log

/**
 * Utilidades para medición y diagnóstico de la memoria JVM / Heap en tiempo de ejecución.
 * Mide memoryClass y largeMemoryClass reales del dispositivo.
 */
object MemoryHelper {
    private const val TAG = "MemoryHelper"

    data class MemoryDiagnostics(
        val memoryClassMb: Int,
        val largeMemoryClassMb: Int,
        val isLowRamDevice: Boolean,
        val maxMemoryMb: Long,
        val totalMemoryMb: Long,
        val freeMemoryMb: Long,
        val usedMemoryMb: Long
    )

    fun getMemoryDiagnostics(context: Context): MemoryDiagnostics {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memoryClass = am?.memoryClass ?: 192
        val largeMemoryClass = am?.largeMemoryClass ?: 512
        val isLowRam = am?.isLowRamDevice ?: false

        val runtime = Runtime.getRuntime()
        val maxMemoryMb = runtime.maxMemory() / (1024 * 1024)
        val totalMemoryMb = runtime.totalMemory() / (1024 * 1024)
        val freeMemoryMb = runtime.freeMemory() / (1024 * 1024)
        val usedMemoryMb = totalMemoryMb - freeMemoryMb

        return MemoryDiagnostics(
            memoryClassMb = memoryClass,
            largeMemoryClassMb = largeMemoryClass,
            isLowRamDevice = isLowRam,
            maxMemoryMb = maxMemoryMb,
            totalMemoryMb = totalMemoryMb,
            freeMemoryMb = freeMemoryMb,
            usedMemoryMb = usedMemoryMb
        )
    }

    fun logMemoryDiagnostics(context: Context, tag: String = TAG) {
        val diag = getMemoryDiagnostics(context)
        Log.i(
            tag,
            "JVM Memory Diagnostics: memoryClass=${diag.memoryClassMb}MB, " +
            "largeMemoryClass=${diag.largeMemoryClassMb}MB, isLowRam=${diag.isLowRamDevice}, " +
            "maxHeap=${diag.maxMemoryMb}MB, allocatedHeap=${diag.totalMemoryMb}MB, " +
            "usedHeap=${diag.usedMemoryMb}MB, freeHeap=${diag.freeMemoryMb}MB"
        )
    }
}
