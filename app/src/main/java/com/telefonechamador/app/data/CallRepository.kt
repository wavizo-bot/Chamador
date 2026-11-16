package com.telefonechamador.app.data

import android.Manifest
import android.content.Context
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract
import com.telefonechamador.app.App
import com.telefonechamador.app.util.Fmt
import com.telefonechamador.app.util.Permissions
import com.telefonechamador.app.util.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

class CallRepository(private val context: Context) {

    private val db get() = (context.applicationContext as App).database
    private val calls get() = db.callDao()
    private val segments get() = db.segmentDao()
    private val blocked get() = db.blockedDao()

    // ---------- ciclo de uma ligação ----------

    suspend fun beginCall(number: String, name: String?, direction: String, startedAt: Long): Long =
        calls.insert(
            CallEntity(
                number = Fmt.normalize(number),
                contactName = name,
                direction = direction,
                startedAt = startedAt,
            )
        )

    /** Número chegou tarde (handle do Telecom, bind após o OFFHOOK): corrige a linha. */
    suspend fun updateNumber(callId: Long, number: String) {
        if (callId <= 0L) return
        val n = Fmt.normalize(number)
        if (n.isBlank()) return
        calls.updateNumber(callId, n)
    }

    suspend fun addSegment(callId: Long, side: String, offsetMs: Long, text: String) {
        if (text.isNotBlank()) {
            segments.insert(SegmentEntity(callId = callId, side = side, offsetMs = offsetMs, text = text.trim()))
        }
    }

    suspend fun finishCall(
        callId: Long,
        endedAt: Long,
        durationSec: Int,
        audioMixed: String?,
        audioReceived: String?,
        audioSent: String?,
        segmentCount: Int,
        note: String?,
    ) {
        val c = calls.byId(callId) ?: return
        calls.update(
            c.copy(
                endedAt = endedAt,
                durationSec = durationSec,
                audioMixed = audioMixed,
                audioReceived = audioReceived,
                audioSent = audioSent,
                segmentCount = segmentCount,
                captureNote = note?.trim()?.takeIf { it.isNotBlank() },
            )
        )
        backfillFromCallLog(callId, c.startedAt, endedAt)
    }

    /** Complementa número/duração com o registro do histórico do sistema, se disponível.
     * Escolhe a entrada com data MAIS PRÓXIMA do início da ligação (a janela larga
     * pegava a ligação anterior não atendida e registrava o número errado). */
    private suspend fun backfillFromCallLog(callId: Long, startedAt: Long, endedAt: Long) {
        if (!Permissions.granted(context, Manifest.permission.READ_CALL_LOG)) return
        try {
            val proj = arrayOf(
                CallLog.Calls._ID, CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME,
                CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION
            )
            context.contentResolver.query(CallLog.Calls.CONTENT_URI, proj, null, null, "${CallLog.Calls.DATE} DESC")
                ?.use { cur ->
                    var i = 0
                    var bestId = 0L
                    var bestDiff = Long.MAX_VALUE
                    var bestNumber = ""
                    var bestName: String? = null
                    var bestType = 0
                    var bestDur = 0
                    while (i < 30 && cur.moveToNext()) {
                        i++
                        val date = cur.getLong(4)
                        if (date < startedAt - 180_000L || date > endedAt + 180_000L) continue
                        val diff = kotlin.math.abs(date - startedAt)
                        if (diff < bestDiff) {
                            bestDiff = diff
                            bestId = cur.getLong(0)
                            bestNumber = cur.getString(1) ?: ""
                            bestName = cur.getString(2)
                            bestType = cur.getInt(3)
                            bestDur = cur.getInt(5)
                        }
                    }
                    if (bestId != 0L) {
                        val existing = calls.byId(callId) ?: return
                        val bestNorm = Fmt.normalize(bestNumber)
                        calls.update(
                            existing.copy(
                                // O CallLog do sistema é o que o modem de fato
                                // discou — corrige número vindo de fonte velha.
                                number = bestNorm.ifBlank { existing.number },
                                contactName = existing.contactName ?: bestName,
                                direction = existing.direction.takeIf { it != "out" } ?: mapDirection(bestType),
                                durationSec = if (existing.durationSec > 0) existing.durationSec else bestDur,
                                systemLogId = bestId,
                            )
                        )
                    }
                }
        } catch (_: Exception) {
        }
    }

    private fun mapDirection(type: Int): String = when (type) {
        CallLog.Calls.INCOMING_TYPE -> "in"
        CallLog.Calls.OUTGOING_TYPE -> "out"
        else -> "missed"
    }

    // ---------- consultas ----------

    fun observeCalls(): Flow<List<CallEntity>> = calls.observeAll()

    /** Último número para o qual o usuário ligou (atalho "Refazer ligação"). */
    suspend fun lastOutgoingNumber(): String? = calls.lastOutgoingNumber()

    suspend fun callById(id: Long): CallEntity? = calls.byId(id)

    suspend fun segmentsFor(callId: Long): List<SegmentEntity> = segments.forCall(callId)

    suspend fun countSegments(callId: Long): Int = segments.countForCall(callId)

    /** Após a transcrição pós-ligação: atualiza o contador salvo na linha da chamada. */
    suspend fun refreshSegmentCount(callId: Long) {
        val c = calls.byId(callId) ?: return
        calls.update(c.copy(segmentCount = countSegments(callId)))
    }

    suspend fun setFavorite(callId: Long, fav: Boolean) = calls.setFavorite(callId, fav)

    suspend fun deleteCall(callId: Long) {
        val c = calls.byId(callId) ?: return
        deleteFiles(c)
        segments.deleteForCall(callId)
        calls.delete(callId)
    }

    private fun deleteFiles(c: CallEntity) {
        listOf(c.audioMixed, c.audioReceived, c.audioSent).forEach { p ->
            if (p != null) {
                try {
                    val f = File(p)
                    f.delete()
                    f.parentFile?.takeIf { it.isDirectory && it.list()?.isEmpty() == true }?.delete()
                } catch (_: Exception) {
                }
            }
        }
    }

    suspend fun search(query: String): List<SearchResultRow> {
        val q = ftsQuery(query) ?: return emptyList()
        return segments.search(q)
    }

    private fun ftsQuery(input: String): String? {
        val tokens = input.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return null
        return tokens.joinToString(" AND ") { raw ->
            val t = raw.replace(Regex("[^\\p{L}\\p{Nd}]"), "")
            if (t.isEmpty()) "zzz*" else t + "*"
        }
    }

    // ---------- retenção ----------

    suspend fun sweepRetention(): Int {
        val cutoff = System.currentTimeMillis() - Prefs.retentionDays * 86_400_000L
        val expired = calls.expired(cutoff)
        for (c in expired) {
            deleteFiles(c)
            segments.deleteForCall(c.id)
            calls.delete(c.id)
        }
        return expired.size
    }

    // ---------- bloqueados ----------

    fun observeBlocked(): Flow<List<BlockedNumberEntity>> = blocked.observeAll()

    suspend fun block(number: String) {
        val n = Fmt.normalize(number)
        if (n.isNotBlank()) blocked.add(BlockedNumberEntity(number = n))
    }

    suspend fun unblock(number: String) = blocked.remove(number)

    suspend fun isBlocked(number: String): Boolean {
        val n = Fmt.normalize(number)
        return n.isNotBlank() && blocked.isBlocked(n)
    }

    // ---------- contatos ----------

    fun lookupName(number: String): String? {
        if (!Permissions.granted(context, Manifest.permission.READ_CONTACTS)) return null
        val n = Fmt.normalize(number)
        if (n.isBlank()) return null
        return try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(n))
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null, null, null
            )?.use { cur -> if (cur.moveToFirst()) cur.getString(0) else null }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun importSystemLog(): Int = withContext(Dispatchers.IO) {
        if (!Permissions.granted(context, Manifest.permission.READ_CALL_LOG)) return@withContext 0
        var added = 0
        try {
            val proj = arrayOf(
                CallLog.Calls._ID, CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME,
                CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION
            )
            context.contentResolver.query(CallLog.Calls.CONTENT_URI, proj, null, null, "${CallLog.Calls.DATE} DESC")
                ?.use { cur ->
                    var count = 0
                    while (cur.moveToNext() && count < 300) {
                        count++
                        val sid = cur.getLong(0)
                        val number = cur.getString(1) ?: continue
                        val name = cur.getString(2)
                        val type = cur.getInt(3)
                        val date = cur.getLong(4)
                        val dur = cur.getInt(5)
                        if (calls.bySystemLog(sid) != null) continue
                        if (calls.existsNear(Fmt.normalize(number), date - 90_000L, date + 90_000L) > 0) continue
                        calls.insert(
                            CallEntity(
                                number = Fmt.normalize(number),
                                contactName = name,
                                direction = mapDirection(type),
                                startedAt = date,
                                endedAt = date + dur * 1000L,
                                durationSec = dur,
                                systemLogId = sid,
                            )
                        )
                        added++
                    }
                }
        } catch (_: Exception) {
        }
        added
    }
}
