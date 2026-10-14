package com.example.androidlab.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.example.androidlab.data.Question
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

// 문제 하나를 소리 내어 읽어 주는 클래스. 한 목소리로 읽고, "다 읽을 때까지" 기다린다.
// TTS = Text To Speech, 글자를 소리로 바꾸는 안드로이드 기본 기능
class QuestionReader(context: Context) {

    // "TTS가 준비됐는가?"를 나중에 알려 주는 상자.
    // TTS는 만들자마자 쓸 수 없고, 준비가 끝나야 쓸 수 있다.
    // CompletableDeferred = 준비되면 complete(값)로 채워 두고, await()로 기다릴 수 있는 상자
    private val ready = CompletableDeferred<Boolean>()

    // "이 문장 읽기가 끝났는가?"를 기다리는 상자들. 문장마다 이름표(id)를 붙여 둔다.
    // 읽기가 끝나면 listener 가 해당 상자를 채워서(complete) 기다리던 곳을 깨운다.
    // ConcurrentHashMap = 여러 스레드가 동시에 써도 안전한 Map (Java에도 있는 클래스)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    // 문장 이름표를 만들 때 쓰는 번호 (utt1, utt2, ...)
    private var counter = 0

    // TTS 엔진 본체. init 블록에서 만든다. (? = null일 수도 있다는 뜻)
    private var tts: TextToSpeech? = null

    // 문장 하나를 읽는 도중 일어나는 일을 알려 주는 장치. (Java의 콜백 객체와 같다)
    // object : 클래스() { } 는 이름 없는 클래스를 바로 만드는 문법이다. (Java의 익명 클래스)
    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {}

        // 다 읽었다 → 기다리던 상자를 true 로 채운다
        override fun onDone(utteranceId: String?) {
            utteranceId?.let { pending.remove(it)?.complete(true) }
        }

        // 읽다가 실패했다 → false 로 채운다 (예전 방식 / 새 방식 둘 다 만들어야 컴파일된다)
        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            utteranceId?.let { pending.remove(it)?.complete(false) }
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            utteranceId?.let { pending.remove(it)?.complete(false) }
        }

        // 중간에 멈췄다 → false 로 채운다
        override fun onStop(utteranceId: String?, interrupted: Boolean) {
            utteranceId?.let { pending.remove(it)?.complete(false) }
        }
    }

    // 객체가 만들어질 때 한 번 실행된다. (Java의 생성자와 비슷하다)
    init {
        // { status -> ... } 는 "준비가 끝나면 호출되는 함수"다.
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.setOnUtteranceProgressListener(listener) // 읽기 끝 알림을 받을 장치 연결
                ready.complete(true)
            } else {
                ready.complete(false)
            }
        }
    }

    // 문제와 선택지를 읽고, 다 읽을 때까지 기다린다. 끝까지 읽었으면 true, 실패하면 false.
    // suspend = 읽는 동안 기다려야 해서 코루틴(백그라운드 작업) 안에서만 부를 수 있다.
    suspend fun read(question: Question): Boolean {
        if (!ready.await()) return false // 준비될 때까지 기다린다
        val engine = tts ?: return false // tts가 null이면 포기

        engine.language = Locale.US // 영어(미국) 목소리
        engine.setSpeechRate(1.0f)  // 말하는 속도 (1.0 = 보통)

        // 1) 질문 문장을 읽는다. 다 읽을 때까지 기다린다.
        if (!speak(engine, question.questionText, TextToSpeech.QUEUE_FLUSH)) return false

        delay(700) // 질문과 선택지 사이에 0.7초 쉰다

        // 2) 선택지를 읽는다.
        val choices = "A. ${question.choiceA}. B. ${question.choiceB}. C. ${question.choiceC}."
        return speak(engine, choices, TextToSpeech.QUEUE_ADD)
    }

    // 문장 하나를 읽고, 다 읽을 때까지 기다린다. 끝까지 읽었으면 true.
    // mode: QUEUE_FLUSH = 지금 읽던 것을 끊고 새로 시작 / QUEUE_ADD = 앞의 것 뒤에 이어서 읽기
    private suspend fun speak(engine: TextToSpeech, text: String, mode: Int): Boolean {
        // 문장 이름표를 만들고, 그 이름표로 "끝났는가?" 상자를 등록한다
        counter++
        val id = "utt$counter"
        val done = CompletableDeferred<Boolean>()
        pending[id] = done

        // 읽기를 시작한다. 시작 자체가 실패하면 등록한 상자를 지우고 false
        if (engine.speak(text, mode, null, id) != TextToSpeech.SUCCESS) {
            pending.remove(id)
            return false
        }

        // 다 읽을 때까지 기다린다.
        try {
            return done.await()
        } catch (e: CancellationException) {
            // 화면이 닫히거나 다른 버튼을 눌러 작업이 취소된 경우: 소리를 멈추고 취소를 그대로 위로 던진다.
            engine.stop()
            pending.remove(id)
            throw e
        }
    }

    // 읽는 중인 소리를 멈춘다
    fun stop() {
        tts?.stop()
    }

    // 다 쓰고 나면 TTS를 완전히 끈다 (화면이 닫힐 때 부른다)
    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
    }
}