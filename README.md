# Telefone Chamador

Discador Android completo com **transcrição em tempo real**, **gravação de ligações** e
**dados 100% locais**. Estética inspirada no projeto *Caderno de Cuidado* (verde profundo
`#174A3A`, papel creme, títulos serifados, cantos bem arredondados).

## O que o app faz

- **Discador completo** quando definido como app de telefone padrão (`ROLE_DIALER` +
  `InCallService`): teclado, histórico, contatos, tela de ligação integrada.
- **Modo "os dois"**: mesmo sem ser o discador padrão, funciona como app comum +
  **janela flutuante** com a transcrição ao vivo (`SYSTEM_ALERT_WINDOW`).
- **Transcrição ao vivo**: Vosk pt-BR offline em streaming (parciais quase instantâneas)
  ou Parakeet TDT v3 via sherpa-onnx (troca em Ajustes → Modelos).
  - Padrão: transcreve **apenas a voz recebida**; opção "ambas as vozes".
  - **VAD** (detecção de atividade de voz) próprio: piso de ruído adaptativo + hangover +
    pré-roll de 400 ms — alimenta o motor só quando há fala (menos alucinação).
- **Gravação**: automática com fallback — VOICE_CALL nativo → downlink+uplink
  → MediaRecorder nativo (transcrição pós-chamada) → microfone com alto-falante.
  Lados configuráveis: ambas as vozes (padrão) / recebida / enviada.
- **Retenção**: 1 a 99 dias (padrão **10**); conversas favoritas ★ nunca são apagadas.
- **Busca textual** nas transcrições (SQLite FTS4).
- **Compartilhar** (Android padrão): transcrição em texto e áudio da ligação.
- **Bloqueio de números**: silencia o toque e rejeita automaticamente
  (CallScreeningService no Android 10+).
- **Efeitos de voz** (DSP próprio): grave, aguda, robô, rádio, eco — aplicáveis na
  gravação/exportação e, experimentalmente, **ao vivo** via alto-falante.
- **Shizuku (opcional)**: concede permissões em silêncio, libera janela flutuante e
  tira o app da otimização de bateria, sem root.

## Compilar

Pré-requisitos: JDK 21 embutido em `.build/jdk-21.0.13+9` (configurado no
`gradle.properties` via `org.gradle.java.home`) e Android SDK com `android-35`.

```bat
gradlew.bat assembleDebug
```

APK em `app\build\outputs\apk\debug\app-debug.apk`.

Instalar no aparelho conectado:

```bat
%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe install -r app\build\outputs\apk\debug\app-debug.apk
```

## Parkeet TDT (opcional)

O motor sherpa-onnx **não está no Maven Central**. Para habilitá-lo:

1. Copie um AAR do sherpa-onnx para `app\libs\` (o build o detecta e compila o
   source set `src/sherpa/java` automaticamente).
2. Em Ajustes → Modelos, baixe "Parakeet TDT 0.6b v3" (~660 MB) e selecione o motor.

Sem o AAR, o app usa Vosk como padrão e ignora a opção Parakeet com aviso.

## Realidade por aparelho (importante)

O Android restringe a captura do áudio interno da chamada (`VOICE_CALL`) para apps
comuns. O app **testa** no primeiro uso o que funciona e guarda o resultado:

- Se `VOICE_CALL`/downlink/uplink abrir → qualidade máxima, separando vozes.
- Se não abrir → gravação nativa via `MediaRecorder` (transcrição gerada ao encerrar).
- Última opção → microfone comum + **alto-falante automático** (captação acústica).

Em Xiaomi/MIUI/HyperOS as fontes nativas costumam estar abertas — use
**Ajustes → Testar captação agora** (ideal durante uma ligação).

Se todas as fontes estiverem mudas durante a ligação (bloqueio da HAL), o app
avisa na tela da ligação e a gravação fica vazia — sem varinha mágica no
espaço do usuário: a alternativa honesta é a captação acústica com o
viva-voz ligado (o app tenta ligar sozinho pelo caminho certo do Telecom).

## Dicas MIUI/HyperOS

1. **Autostart**: ligações perdidas em segundo plano são evitadas com "Iniciar
   automaticamente" permitido no app (Segurança/HyperOS).
2. **Economia de bateria**: Ajustes → Ignorar otimização de bateria.
3. **Permissões via Shizuku** (sem root): instale o Shizuku, inicie o serviço via ADB
   e use "Permissões avançadas (Shizuku)" nos Ajustes — concede tudo em um toque.
4. Notificações: permita todas, inclusive o canal "Transcrição em andamento".

## Nota de diagnóstico (ícones invisíveis)

Durante o desenvolvimento os ícones vetoriais apareciam em branco neste aparelho.
Causa real (confirmada com app de teste mínimo): os XMLs usavam `d="..."`
**sem o namespace `android:`** — o atributo correto é `android:pathData`.
Sem o namespace, o framework infla o `VectorDrawable` sem erro, mas sem geometria,
e nada é desenhado (launcher inclusive). Não é bug de ROM. Todos os ícones foram
corrigidos e os vetores renderizam normalmente em qualquer Android 5+.

## Nota de diagnóstico (papéis do sistema)

Neste aparelho (HyperOS, Android 16), `adb shell cmd role add-role-holder`
não concede `ROLE_DIALER` ("Package does not qualify") — o caminho é o diálogo
do app. A qualificação para `ROLE_DIALER` exige um `InCallService` com o
meta-data `android.telecom.IN_CALL_SERVICE_UI` (`value="true"`) — sem isso o
PermissionController recusa o pedido silenciosamente (log:
"Package does not qualify for the role"). Com o meta-data, o diálogo
"Definir … como seu app de telefone padrão?" abre normalmente.

`ROLE_CALL_SCREENING` foi concedido automaticamente ao app qualificado
(`CallScreeningImpl` com `BIND_SCREENING_SERVICE`) — confirmado em
`dumpsys role` (`holders=com.telefonechamador.app`).

## Nota de diagnóstico (probe de captação)

O `AudioSourceProbe` original **nunca chamava `rec.startRecording()`** — o
`AudioRecord` inicializava a trilha, mas todo `read()` voltava
`INVALID_OPERATION (-38)` ("Error -38 during AudioRecord native read" no
logcat) e todas as fontes apareciam como indisponíveis, mesmo com
`RECORD_AUDIO` concedida. Corrigido: iniciar/parar a gravação antes de ler.

## Nota de diagnóstico (captação muda durante a ligação — MTK/HyperOS)

Instrumentado com logcat (`TChamador`) durante ligações reais neste aparelho:
com a chamada ativa, `AudioSource.MIC` entrega **zeros digitais puros**
(`cap side=mixed src=1 rmsMedio=0` a cada 2 s) — o VAD nunca ativa e o Vosk
recebe nada, ainda que a gravação WAV seja criada (silêncio ao ouvir). Em idle
o mesmo MIC entrega energia normal (probe ok). A HAL da ROM corta o áudio de
terceiros no modo de chamada. Como o probe em idle não prevê o comportamento
em chamada, o `CallRecorder` agora faz **troca adaptativa de fonte durante a
ligação**: 3 s sem energia → tenta `VOICE_CALL` → `VOICE_COMMUNICATION` →
`VOICE_DOWNLINK` → `VOICE_UPLINK` → microfone, registrando cada troca no
logcat e avisando na tela da ligação quando todas forem mudas.

## Nota de diagnóstico (destino 97675-7550 não recebe ligações)

Testado de **dois aparelhos independentes** com o rádio instrumentado:

- Celular principal (o app disca) — modem aceita, `DIALING` ~30s, sem erro.
- Celular secundário (Qualcomm) — `DIAL` aceito pela rede, **36s em
  DIALING** (a operadora encaminhou e tocou), e a rede encerrou com
  `LAST_CALL_FAIL_CAUSE causeCode: 31 vendorCause: "Normal, unspecified"`.

Causa 31 após ~35s = **o destino existe, a rede alcança, e ninguém atende**
(aparelho desligado, fora de área, em Não Perturbe, ou rejeitando números
desconhecidos). Número inválido rejeitaria em ~2s com causa 1. O caminho de
discagem do app está íntegro — o problema é do destino/linha.

## Nota de diagnóstico (fallback de gravação do sistema)

Quando a troca adaptativa esgota todas as fontes do `AudioRecord`, o
`CallEngine` aciona o **MediaRecorder com fonte nativa**
(`VOICE_CALL → VOICE_COMMUNICATION → MIC`, AAC/m4a 16 kHz) — a rota que o
gravador de chamadas do próprio sistema usa. Ao encerrar, o arquivo é
decodificado (`MediaDecode`) e transcrito pós-ligação pelo Vosk, com
segmentos salvos no banco. Notas na tela refletem cada etapa.

**Resultado medido neste aparelho (com DTMF cruzando a linha):** o
MediaRecorder aceita `VOICE_COMMUNICATION` e grava um m4a de tamanho normal
(283 KB / 35 s), mas o PCM decodificado tem **rmsMedio=0** — silêncio
digital puro também no MediaRecorder. Ou seja: nesta HyperOS/MTK **nenhum
caminho público entrega áudio de chamada celular a apps de terceiros**
(AudioRecord: todas as fontes mudas/bloqueadas; MediaRecorder: grava
silêncio). O app documenta isso na tela e segue tentando a cada ligação —
em outros aparelhos a mesma cadeia funciona (é o comportamento do
discador AOSP/gravadores comuns). Para áudio real neste aparelho: ligações
VoIP (WhatsApp etc., `MODE_IN_COMMUNICATION` permite o microfone) ou o
gravador do próprio sistema, que rota por caminho privilegiado.

## Nota de diagnóstico (viva-voz automático que não ligava)

`CallUtils.setSpeakerOn` usava `AudioManager.setCommunicationDevice`
(caminho de áudio de comunicação/VoIP, API 31+) — **não altera a rota de uma
chamada de celular do Telecom**. O caminho correto para o discador padrão é
`InCallService.setAudioRoute(ROUTE_SPEAKER/ROUTE_EARPIECE)` (é o que o
discador AOSP usa; `Call.setAudioRoute` é @SystemApi e não existe na API
pública). Implementado em `InCallServiceImpl` + `CallController`, com o
estado real (`onCallAudioStateChanged`) refletido na UI.

## Nota de diagnóstico (Ajustes desconectados)

O `SettingsFragment` era um `PreferenceFragmentCompat` puro: os switches e
listas gravavam no arquivo de SharedPreferences **padrão** do
PreferenceManager (`com.telefonechamador.app_preferences`), enquanto o motor
do app lia o arquivo próprio de `Prefs`
(`telefone_chamador_prefs`). Resultado: mudar Ajustes não mudava o
comportamento do app (e o estado exibido podia divergir do real).
Corrigido com um `PreferenceDataStore` (`PrefsDataStore`) que mapeia cada
chave do `preferences.xml` direto para o `Prefs`.

## Aviso legal

Gravar ligações sem avisar pode ser vedado por lei no seu país/estado. Os dados ficam
no aparelho; nada é enviado para servidores.

## Roadmap

- **Clonar/espelhar a voz do interlocutor (XTTS v2 etc.)**: hoje é inviável em tempo
  real no aparelho (modelos de ~2 GB e inferência de segundos por frase, licença
  não-comercial). Pesquisa futura: TTS on-device leve + conversão de voz.
- Modo efeito ao vivo por áudio multicast nativo (exigiria captura privilegiada).

## Estrutura de pastas

```
app/src/main/java/com/telefonechamador/app/   call/ audio/ shizuku/ stt/ data/
                                              retention/ service/ ui/ incall/ util/
entregas/android/                             pacote vigente + arquivo/ + loja/
app/libs/                                     AAR opcional do sherpa-onnx
.build/jdk-21.0.13+9/                         JDK 21 embutido (fora do git)
```
Detalhamento completo em `manual.txt`. `entregas/` segue a regra 2.4
(C:\APPS\Regras.txt): pacote vigente na raiz da plataforma, anteriores em
`arquivo/`, assets de loja em `loja/`. **Exceção consciente documentada (GH2.2):**
o APK vigente em `entregas/android/` é versionado no repositório porque é o
artefato de entrega (sideload); gerado por `gradlew assembleDebug`.

## Versão atual

**1.0.0 (versionCode 1)** — nunca enviada a loja; uso pessoal via sideload.
O número exibido no app vem do pacote instalado (regra 2.2: dinâmico e fiel).

## Links

- Repositório/landing públicos: pendentes de decisão (ver manual.txt).
- Documentos legais no app: Ajustes → Aviso legal (voltarão a `docs/` quando
  houver Pages/loja, conforme 1.3 e GH7).

## Contato

mmr05@hotmail.com / 11978831938

---

## English summary

Telefone Chamador is a full Android dialer replacement (ROLE_DIALER + InCallService)
with real-time offline call transcription (Vosk pt-BR, optional Parakeet TDT),
call recording with a graceful fallback chain, on-device voice activity detection,
customizable retention, full-text search and optional Shizuku integration for
privileged shell-based capture on MIUI/HyperOS devices. All data stays on the
device — no servers, no accounts, no tracking. It is a personal-use, independent
tool: it is not an official system, not a medical record and not a medical device.
Current distribution is manual APK sideload; store listing and public repository
are pending decisions. Contact: mmr05@hotmail.com.
