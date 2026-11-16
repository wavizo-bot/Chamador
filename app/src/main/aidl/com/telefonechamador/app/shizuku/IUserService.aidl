package com.telefonechamador.app.shizuku;

import android.os.ParcelFileDescriptor;

interface IUserService {
    /** Roda um comando shell com os privilégios do ADB (processo do Shizuku). */
    String runShell(String cmd);

    /**
     * Sonda: abre AudioRecords no processo do Shizuku (uid shell) e mede a
     * energia. Testa MIC, VOICE_COMMUNICATION, VOICE_CALL, VOICE_UPLINK e
     * VOICE_DOWNLINK, cada uma por `seconds` segundos. Devolve por fonte:
     * {abriu, rmsMedio, blocos}.
     */
    int[] probeCapture(int seconds);

    /**
     * Gravação no processo shell: AudioRecord com identidade com.android.shell
     * escrevendo WAV PCM 16-bit mono no descriptor do app (o arquivo é criado
     * e possuído pelo app; o processo shell só escreve no fd recebido).
     * Os tamanhos do cabeçalho WAV saem com placeholder — o app os corrige
     * ao encerrar. Devolve o id da sessão (>= 0) ou -1 em falha.
     */
    int startRecord(int source, int sampleRate, in ParcelFileDescriptor output);

    /** Encerra a sessão; devolve o rms médio (0 = silêncio absoluto), -1 se id inválido. */
    int stopRecord(int sessionId);

    /** rms médio acumulado da sessão até o momento, ou -1 se id inválido. */
    int recordRms(int sessionId);

    /** Encerra o serviço no processo do Shizuku. */
    void exit();
}
