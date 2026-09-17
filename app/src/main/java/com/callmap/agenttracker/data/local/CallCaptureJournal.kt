package com.callmap.agenttracker.data.local

import android.content.Context
import com.callmap.agenttracker.service.CallReceiver
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/** Small, synchronously committed journal. No call-state transition relies on process memory. */
object CallCaptureJournal {
    private val gson = Gson()
    private fun prefs(context: Context) = context.getSharedPreferences("call_capture_journal", Context.MODE_PRIVATE)

    @Synchronized fun clear(context: Context) {
        check(prefs(context).edit().clear().commit())
    }

    data class Snapshot(
        val current: CallReceiver.Companion.CallData? = null,
        val interrupted: List<CallReceiver.Companion.CallData> = emptyList(),
        val lastState: Int = 0,
        val processed: Set<String> = emptySet()
    )

    @Synchronized fun begin(context: Context, deviceUuid: String): Long {
        val p = prefs(context)
        if (p.getString("device", null) != deviceUuid) {
            check(p.edit().clear().putString("device", deviceUuid)
                .putLong("since", System.currentTimeMillis()).commit())
        }
        return p.getLong("since", System.currentTimeMillis())
    }

    @Synchronized fun read(context: Context): Snapshot =
        prefs(context).getString("state", null)?.let { gson.fromJson(it, Snapshot::class.java) } ?: Snapshot()

    @Synchronized fun rememberRecording(context: Context, callId: String, path: String) {
        check(prefs(context).edit().putString("audio:$callId", path).commit())
    }

    @Synchronized fun recording(context: Context, callId: String): String? = prefs(context).getString("audio:$callId", null)

    @Synchronized fun save(context: Context, state: Snapshot) {
        check(prefs(context).edit().putString("state", gson.toJson(state)).commit())
    }

    @Synchronized fun completed(context: Context): List<CallReceiver.Companion.CallData> =
        prefs(context).getString("completed", null)?.let {
            gson.fromJson(it, object : TypeToken<List<CallReceiver.Companion.CallData>>() {}.type)
        } ?: emptyList()

    @Synchronized fun complete(context: Context, call: CallReceiver.Companion.CallData) {
        val list = completed(context).filterNot { it.startTime == call.startTime && it.number == call.number } + call
        check(prefs(context).edit().putString("completed", gson.toJson(list)).commit())
    }

    @Synchronized fun forgetCompleted(context: Context, startTime: Long) {
        check(prefs(context).edit().putString("completed", gson.toJson(completed(context).filterNot { it.startTime == startTime })).commit())
    }

    @Synchronized fun setDial(context: Context, number: String, metadata: String) {
        check(prefs(context).edit().putString("dial_number", number).putString("dial_meta", metadata)
            .putLong("dial_at", System.currentTimeMillis()).commit())
    }

    @Synchronized fun takeDial(context: Context, number: String, callStartedAt: Long = System.currentTimeMillis()): String? {
        val p = prefs(context)
        val saved = p.getString("dial_number", null) ?: return null
        // Match when the call STARTED, not when a long call finished/recovery ran.
        if (callStartedAt - p.getLong("dial_at", 0) !in -30_000L..300_000L) return null
        if (saved.filter(Char::isDigit) != number.filter(Char::isDigit)) return null
        val result = p.getString("dial_meta", null)
        check(p.edit().remove("dial_number").remove("dial_meta").commit())
        return result
    }
}
