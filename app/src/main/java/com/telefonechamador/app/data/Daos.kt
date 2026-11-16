package com.telefonechamador.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CallDao {
    @Insert
    suspend fun insert(c: CallEntity): Long

    @Update
    suspend fun update(c: CallEntity)

    @Query("SELECT * FROM calls ORDER BY startedAt DESC")
    fun observeAll(): Flow<List<CallEntity>>

    @Query("SELECT * FROM calls WHERE id = :id")
    suspend fun byId(id: Long): CallEntity?

    @Query("SELECT * FROM calls WHERE systemLogId = :sid LIMIT 1")
    suspend fun bySystemLog(sid: Long): CallEntity?

    @Query("SELECT COUNT(*) FROM calls WHERE number = :number AND startedAt BETWEEN :lo AND :hi")
    suspend fun existsNear(number: String, lo: Long, hi: Long): Int

    @Query("UPDATE calls SET favorite = :fav WHERE id = :id")
    suspend fun setFavorite(id: Long, fav: Boolean)

    @Query("UPDATE calls SET number = :number WHERE id = :id")
    suspend fun updateNumber(id: Long, number: String)

    @Query("DELETE FROM calls WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM calls WHERE favorite = 0 AND startedAt < :cutoff")
    suspend fun expired(cutoff: Long): List<CallEntity>

    @Query("SELECT number FROM calls WHERE direction = 'out' AND number != '' ORDER BY startedAt DESC LIMIT 1")
    suspend fun lastOutgoingNumber(): String?
}

@Dao
interface SegmentDao {
    @Insert
    suspend fun insert(s: SegmentEntity): Long

    @Query("SELECT * FROM segments WHERE callId = :callId ORDER BY offsetMs ASC")
    suspend fun forCall(callId: Long): List<SegmentEntity>

    @Query("SELECT COUNT(*) FROM segments WHERE callId = :callId")
    suspend fun countForCall(callId: Long): Int

    @Query("DELETE FROM segments WHERE callId = :callId")
    suspend fun deleteForCall(callId: Long)

    @Query(
        """
        SELECT c.id AS callId, c.number AS number, c.contactName AS contactName, c.startedAt AS startedAt,
               s.side AS side, s.offsetMs AS offsetMs, s.text AS snippet
        FROM segments s
        JOIN segments_fts ON segments_fts.rowid = s.id
        JOIN calls c ON c.id = s.callId
        WHERE segments_fts MATCH :q
        ORDER BY c.startedAt DESC, s.offsetMs ASC
        LIMIT 200
        """
    )
    suspend fun search(q: String): List<SearchResultRow>
}

@Dao
interface BlockedDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun add(b: BlockedNumberEntity)

    @Query("SELECT * FROM blocked_numbers ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<BlockedNumberEntity>>

    @Query("SELECT * FROM blocked_numbers")
    suspend fun all(): List<BlockedNumberEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM blocked_numbers WHERE number = :number)")
    suspend fun isBlocked(number: String): Boolean

    @Query("DELETE FROM blocked_numbers WHERE number = :number")
    suspend fun remove(number: String)
}
