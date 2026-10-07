package com.example.androidlab.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import com.example.androidlab.data.Question
import kotlinx.coroutines.CompletableDeferred
import java.util.Locale

// 문제 하나를 소리 내어 읽어 주는 클래스 (한 목소리로 읽는다. 화자 구분 없음)
// TTS = Text To Speech, 글자를 소리로 바꾸는 안드로이드 기본 기능
class QuestionReader(context: Context) {

    // "TTS가 준비됐는가?"를 나중에 알려 주는 상자.
    // TTS는 만들자마자 쓸 수 없고, 준비가 끝나야 쓸 수 있다.
    // CompletableDeferred = 준비되면 complete(값)로 채워 두고, await()로 기다릴 수 있는 상자
    private val ready = CompletableDeferred<Boolean>()

    // TTS 엔진 본체. init 블록에서 만든다.
    private var tts: TextToSpeech? = null

    // 객체가 만들어질 때 한 번 실행된다. (Java의 생성자와 비슷하다)
    init {
        // { status -> ... } 는 "준비가 끝나면 호출되는 함수"다.
        tts = TextToSpeech(context.applicationContext) { status ->
            ready.complete(status == TextToSpeech.SUCCESS) // 성공이면 true, 실패면 false
        }
    }

    // 문제와 선택지를 읽는다. 준비가 안 됐거나 실패하면 false를 돌려준다.
    // suspend = 준비를 기다려야 해서 코루틴(백그라운드 작업) 안에서만 부를 수 있다.
    suspend fun read(question: Question): Boolean {
        if (!ready.await()) return false // 준비될 때까지 기다린다
        val engine = tts ?: return false // tts가 null이면 포기

        engine.language = Locale.US      // 영어(미국) 목소리
        engine.setSpeechRate(1.0f)       // 말하는 속도 (1.0 = 보통)

        // 1) 질문 문장 읽기. QUEUE_FLUSH = 지금 읽던 것을 끊고 새로 시작
        //    (화자 표시는 questionText에 들어 있지 않아서 읽지 않는다)
        engine.speak(question.questionText, TextToSpeech.QUEUE_FLUSH, null, "question")

        // 2) 선택지 읽기. QUEUE_ADD = 앞의 것이 끝난 뒤에 이어서 읽는다
        val choices = "A. ${question.choiceA}. B. ${question.choiceB}. C. ${question.choiceC}."
        engine.speak(choices, TextToSpeech.QUEUE_ADD, null, "choices")

        return true // 읽기를 "시작"했다는 뜻이다. 다 읽을 때까지 기다리지는 않는다.
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