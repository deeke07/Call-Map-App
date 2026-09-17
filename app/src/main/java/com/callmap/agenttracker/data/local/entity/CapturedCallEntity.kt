package com.callmap.agenttracker.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Retained after uploaded call rows are cleaned up, so reconciliation cannot re-import them. */
@Entity(tableName = "captured_calls")
data class CapturedCallEntity(@PrimaryKey val uniqueId: String)
