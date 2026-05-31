package com.callmap.agenttracker.util

import android.util.Log

/** Gate noisy tracking/alarm logs; keep production Logcat minimal. */
object TrackingLog {
    const val DIAGNOSTICS = false

    fun d(tag: String, message: String) {
        if (DIAGNOSTICS) Log.d(tag, message)
    }

    fun i(tag: String, message: String) = Log.i(tag, message)

    fun w(tag: String, message: String) = Log.w(tag, message)

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) Log.e(tag, message, throwable) else Log.e(tag, message)
    }
}
