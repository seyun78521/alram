package com.example.androidlab.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import com.example.androidlab.data.Question
import kotlinx.coroutines.CompletableDeferred
import java.util.Locale

// Question 하나를 TTS로 읽어주는 클래스
class QuestionReader(
    context: Context
) {

    // TTS가 준비되었는지 확인하기 위한 값
    private val ready =
        CompletableDeferred<Boolean>()

    // TTS 객체
    private var tts: TextToSpeech? =
        null

    init {

        // TTS 초기화
        tts =
            TextToSpeech(
                context.applicationContext
            ) { status ->

                // 초기화 성공 여부 저장
                ready.complete(
                    status ==
                            TextToSpeech.SUCCESS
                )
            }
    }

    // 문제와 선택지를 읽어준다.
    suspend fun read(
        question: Question
    ): Boolean {

        // TTS 초기화가 끝날 때까지 기다린다.
        if (!ready.await()) {
            return false
        }

        val engine =
            tts ?: return false

        // 영어 문제이므로 영어 음성 사용
        engine.language =
            Locale.US

        // 기본 속도
        engine.setSpeechRate(1.0f)

        // 문제 읽기
        engine.speak(
            question.questionText,
            TextToSpeech.QUEUE_FLUSH,
            null,
            "question"
        )

        // 선택지 만들기
        val choices =
            buildString {

                append(
                    "A. ${question.choiceA}. "
                )

                append(
                    "B. ${question.choiceB}. "
                )

                append(
                    "C. ${question.choiceC}."
                )
            }

        // 선택지 읽기
        engine.speak(
            choices,
            TextToSpeech.QUEUE_ADD,
            null,
            "choices"
        )

        return true
    }

    // 현재 음성 중지
    fun stop() {
        tts?.stop()
    }

    // TTS 완전히 종료
    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
    }
}