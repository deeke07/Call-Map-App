package com.callmap.agenttracker.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log
import android.os.Build
import androidx.annotation.RequiresApi
import com.callmap.agenttracker.domain.manager.SessionManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Collections
import javax.inject.Inject

@AndroidEntryPoint
class CallReceiver : BroadcastReceiver() {

    @Inject
    lateinit var sessionManager: SessionManager

    companion object {
        private const val TAG = "CallReceiver"

        // Track current active call
        private var currentCallData: CallData? = null
        private val interruptedCalls = Collections.synchronizedList(mutableListOf<CallData>())
        private var lastCallState: Int = TelephonyManager.CALL_STATE_IDLE
        // Track call IDs we've already sent to service to prevent duplicates
        private val processedCallIds = Collections.synchronizedSet(mutableSetOf<String>())

        // Metadata for the next outgoing call triggered via FCM
        private var pendingDialMetaData: Pair<String, String>? = null

        fun setPendingDialMetaData(number: String, metaData: String) {
            pendingDialMetaData = number to metaData
        }

        data class CallData(
            var number: String = "Unknown",
            var startTime: Long = 0,
            var answeredTime: Long = 0,
            var type: Int = android.provider.CallLog.Calls.OUTGOING_TYPE,
            var isIncoming: Boolean = false,
            var wasAnswered: Boolean = false,
            var interruptedBy: MutableSet<String> = mutableSetOf(),
            var wasOnHold: Boolean = false,
            var serviceInstanceId: Long = 0,
            var isSaved: Boolean = false,
            var metaData: String? = null
        )
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_NEW_OUTGOING_CALL -> {
                val number = intent.getStringExtra(Intent.EXTRA_PHONE_NUMBER) ?: "Unknown"
                Log.d(TAG, "Outgoing call initiated: $number")

                // Create new call data
                currentCallData = CallData(
                    number = number,
                    startTime = System.currentTimeMillis(),
                    type = android.provider.CallLog.Calls.OUTGOING_TYPE,
                    isIncoming = false,
                    serviceInstanceId = System.currentTimeMillis()
                ).apply {
                    // Attach metadata if this call matches the pending dial request
                    pendingDialMetaData?.let { (pendingNumber, meta) ->
                        if (normalizeNumber(number) == normalizeNumber(pendingNumber)) {
                            metaData = meta
                            pendingDialMetaData = null // Consume it
                        }
                    }
                }
            }
            "android.intent.action.PHONE_STATE" -> {
                val stateStr = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
                val incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)

                val state = when (stateStr) {
                    TelephonyManager.EXTRA_STATE_IDLE -> TelephonyManager.CALL_STATE_IDLE
                    TelephonyManager.EXTRA_STATE_OFFHOOK -> TelephonyManager.CALL_STATE_OFFHOOK
                    TelephonyManager.EXTRA_STATE_RINGING -> TelephonyManager.CALL_STATE_RINGING
                    else -> return
                }

                if (incomingNumber != null && state == TelephonyManager.CALL_STATE_RINGING) {
                    // Incoming call ringing
                    val active = currentCallData
                    if (active != null && active.wasAnswered && !active.isSaved) {
                        // Current call is active, this is an interruption
                        active.wasOnHold = true
                        active.interruptedBy.add(incomingNumber)
                        interruptedCalls.add(active)
                    }
                    currentCallData = CallData(
                        number = incomingNumber,
                        startTime = System.currentTimeMillis(),
                        type = android.provider.CallLog.Calls.INCOMING_TYPE,
                        isIncoming = true,
                        serviceInstanceId = System.currentTimeMillis()
                    )
                }

                onCallStateChanged(context, state, incomingNumber)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun onCallStateChanged(context: Context, state: Int, incomingNumber: String?) {
        Log.d(TAG, "State: ${getStateString(state)}, Current: ${currentCallData?.number}, lastState: ${getStateString(lastCallState)}")

        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> {
                // Log incoming call ringing
                Log.d(TAG, "Incoming call ringing: $incomingNumber")
            }

            TelephonyManager.CALL_STATE_OFFHOOK -> {
                // Call is answered or in progress
                if (currentCallData == null) {
                    // Assume outgoing call if no current call data (ACTION_NEW_OUTGOING_CALL not received)
                    currentCallData = CallData(
                        number = "Unknown",
                        startTime = System.currentTimeMillis(),
                        type = android.provider.CallLog.Calls.OUTGOING_TYPE,
                        isIncoming = false,
                        serviceInstanceId = System.currentTimeMillis()
                    )
                }

                // Mark as answered only once
                val callData = currentCallData
                if (callData != null && !callData.wasAnswered) {
                    // Only mark as answered if it was RINGING (Incoming) or if it's Outgoing
                    if (!callData.isIncoming || lastCallState == TelephonyManager.CALL_STATE_RINGING) {
                        callData.wasAnswered = true
                        callData.answeredTime = System.currentTimeMillis()

                        // Start/Notify recording service
                        CoroutineScope(Dispatchers.IO).launch {
                            val config = sessionManager.getRegistration().first()
                            startRecording(context, callData, config?.recordingEnabled ?: true)
                        }
                    }
                }
            }

            TelephonyManager.CALL_STATE_IDLE -> {


                // 1. Process CURRENT call
                val current = currentCallData
                if (current != null && !current.isSaved) {
                    current.isSaved = true
                    if (current.answeredTime > 0) current.wasAnswered = true
                    stopRecording(context, current)
                }

                // 2. Process all INTERRUPTED calls
                synchronized(interruptedCalls) {
                    val iterator = interruptedCalls.iterator()
                    while (iterator.hasNext()) {
                        val call = iterator.next()
                        if (!call.isSaved) {
                            call.isSaved = true
                            if (call.answeredTime > 0) call.wasAnswered = true
                            stopRecording(context, call)
                        }
                        iterator.remove()
                    }
                }

                // Reset state
                currentCallData = null
            }
        }

        lastCallState = state
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun startRecording(context: Context, callData: CallData, recordingEnabled: Boolean) {
        val intent = Intent(context, CallRecorderService::class.java).apply {
            action = CallRecorderService.ACTION_START
            putExtra(CallRecorderService.EXTRA_NUMBER, callData.number)
            putExtra(CallRecorderService.EXTRA_TYPE, callData.type)
            putExtra(CallRecorderService.EXTRA_START_TIME, callData.startTime)
            putExtra(CallRecorderService.EXTRA_RECORDING_ENABLED, recordingEnabled)
        }
        context.startForegroundService(intent)
    }

    private fun stopRecording(context: Context, callData: CallData) {
        val callId = "${callData.number}|${callData.startTime}|${callData.type}"

        // Prevent duplicate processing of the same call
        if (processedCallIds.contains(callId)) {
            return
        }
        processedCallIds.add(callId)

        // Cleanup old IDs
        if (processedCallIds.size > 100) {
            val toRemove = processedCallIds.take(50).toSet()
            processedCallIds.removeAll(toRemove)
        }

        val intent = Intent(context, CallRecorderService::class.java).apply {
            action = CallRecorderService.ACTION_STOP
            putExtra(CallRecorderService.EXTRA_NUMBER, callData.number)
            putExtra(CallRecorderService.EXTRA_TYPE, callData.type)
            putExtra(CallRecorderService.EXTRA_START_TIME, callData.startTime)
            putExtra(CallRecorderService.EXTRA_ANSWERED_TIME, callData.answeredTime)
            putExtra(CallRecorderService.EXTRA_WAS_ANSWERED, callData.wasAnswered)
            putExtra(CallRecorderService.EXTRA_WAS_ON_HOLD, callData.wasOnHold)
            putExtra(CallRecorderService.EXTRA_INTERRUPTED_NUMBERS, callData.interruptedBy.joinToString(","))
            putExtra(CallRecorderService.EXTRA_META_DATA, callData.metaData)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    private fun getStateString(state: Int): String = when (state) {
        TelephonyManager.CALL_STATE_IDLE -> "IDLE"
        TelephonyManager.CALL_STATE_RINGING -> "RINGING"
        TelephonyManager.CALL_STATE_OFFHOOK -> "OFFHOOK"
        else -> "UNKNOWN"
    }

    private fun normalizeNumber(number: String?): String {
        return number?.filter { it.isDigit() }?.takeLast(10) ?: ""
    }
}