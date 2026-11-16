package com.telefonechamador.app.data

import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "calls")
data class CallEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val number: String,
    val contactName: String? = null,
    /** "in" | "out" | "missed" */
    val direction: String,
    val startedAt: Long,
    val endedAt: Long = 0,
    val durationSec: Int = 0,
    val favorite: Boolean = false,
    val audioMixed: String? = null,
    val audioReceived: String? = null,
    val audioSent: String? = null,
    val segmentCount: Int = 0,
    val captureNote: String? = null,
    val systemLogId: Long? = null,
)

@Entity(tableName = "segments", indices = [Index("callId")])
data class SegmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val callId: Long,
    /** "received" | "sent" | "mixed" */
    val side: String,
    val offsetMs: Long,
    val text: String,
)

@Fts4(contentEntity = SegmentEntity::class)
@Entity(tableName = "segments_fts")
data class SegmentFtsEntity(val text: String)

@Entity(tableName = "blocked_numbers")
data class BlockedNumberEntity(
    @PrimaryKey val number: String,
    val label: String? = null,
    val addedAt: Long = System.currentTimeMillis(),
)

data class SearchResultRow(
    val callId: Long,
    val number: String,
    val contactName: String?,
    val startedAt: Long,
    val side: String,
    val offsetMs: Long,
    val snippet: String,
)
