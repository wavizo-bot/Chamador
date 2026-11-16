package com.telefonechamador.app.call

import android.telecom.Call
import android.telecom.CallScreeningService
import com.telefonechamador.app.data.CallRepository
import com.telefonechamador.app.util.Prefs
import kotlinx.coroutines.runBlocking

/**
 * Memória curta do último número que estava tocando — em Android 11+ o
 * PhoneStateListener não entrega mais o número, então o serviço de triagem
 * (este) o guarda para o CallMonitor e para a tela de transcrição.
 */
object IncomingMemo {
    @Volatile private var number = ""
    @Volatile private var at = 0L

    fun put(n: String) {
        number = n
        at = System.currentTimeMillis()
    }

    fun peek(): String? =
        if (number.isNotBlank() && System.currentTimeMillis() - at < 90_000L) number else null
}

/**
 * Triagem de chamadas (papel CALL_SCREENING, Android 10+):
 * permite identificar o número de quem liga mesmo sem ser o discador padrão
 * e rejeitar automaticamente os números bloqueados.
 */
class CallScreeningImpl : CallScreeningService() {

    override fun onScreenCall(callDetails: Call.Details) {
        val number = try {
            callDetails.handle?.schemeSpecificPart ?: ""
        } catch (_: Throwable) {
            ""
        }
        if (number.isNotBlank()) IncomingMemo.put(number)

        val blocked = runBlocking {
            try {
                number.isNotBlank() && Prefs.silenceBlocked &&
                    CallRepository(applicationContext).isBlocked(number)
            } catch (_: Throwable) {
                false
            }
        }

        val builder = CallScreeningService.CallResponse.Builder()
        if (blocked) {
            builder.setDisallowCall(true)
                .setRejectCall(true)
                .setSkipNotification(true)
        }
        respondToCall(callDetails, builder.build())
    }
}
