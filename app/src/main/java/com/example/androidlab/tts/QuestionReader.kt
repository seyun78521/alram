package com.example.androidlab.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.example.androidlab.data.Question
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

class QuestionReader(context: Context) {

    private var tts: TextToSpeech? = null
    private val ready = CompletableDeferred<Boolean>()                 // TTS 준비 완료 여부
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val counter = AtomicInteger()

    // 한 문장을 다 읽으면 알려주는 장치
    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {}

        override fun onDone(utteranceId: String?) {
            utteranceId?.let { pending.remove(it)?.complete(true) }
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            utteranceId?.let { pending.remove(it)?.complete(false) }
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            utteranceId?.let { pending.remove(it)?.complete(false) }
        }

        override fun onStop(utteranceId: String?, interrupted: Boolean) {
            utteranceId?.let { pending.remove(it)?.complete(false) }
        }
    }

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.setOnUtteranceProgressListener(listener)
                ready.complete(true)
            } else {
                ready.complete(false)
            }
        }
    }

    // 문제 하나를 소리 내어 읽는다: 질문(질문하는 사람 목소리) → 선택지(응답하는 사람 목소리)
    suspend fun read(q: Question): Boolean {
        if (!ready.await()) return false

        val asker = SpeakerVoices.profileFor(q.questionSpeaker.ifEmpty { "W-Am" })
        val answerer =
            if (q.answerSpeaker.isNotEmpty()) SpeakerVoices.profileFor(q.answerSpeaker)
            else SpeakerVoices.fallbackAnswerer(asker)

        if (!speakAndWait(q.questionText, asker)) return false
        delay(800)

        val choices = listOf("A" to q.choiceA, "B" to q.choiceB, "C" to q.choiceC, "D" to q.choiceD)
            .filter { it.second.isNotBlank() }
        for ((letter, text) in choices) {
            if (!speakAndWait("$letter. $text", answerer)) return false
            delay(500)
        }
        return true
    }

    // 한 문장을 읽고, 다 읽을 때까지 기다린다 (목소리를 바꾸려면 앞 문장이 끝나야 해서)
    private suspend fun speakAndWait(text: String, profile: VoiceProfile): Boolean {
        val engine = tts ?: return false
        applyProfile(engine, profile)

        val id = "utt" + counter.incrementAndGet()
        val done = CompletableDeferred<Boolean>()
        pending[id] = done

        val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        if (result != TextToSpeech.SUCCESS) {
            pending.remove(id)
            return false
        }
        return try {
            done.await()
        } catch (e: CancellationException) {
            engine.stop()
            pending.remove(id)
            throw e
        }
    }

    // 이 사람의 목소리(음성, 음높이, 속도)를 적용한다
    private fun applyProfile(engine: TextToSpeech, profile: VoiceProfile) {
        val voice = pickVoice(engine, profile)
        var pitch = profile.pitch
        if (voice != null) {
            engine.setVoice(voice)
            // 음성의 성별이 확인되어 맞는 경우에는 음높이를 조금만 바꾼다
            if (profile.isFemale != null && genderOf(voice) == profile.isFemale) {
                pitch = 1f + (profile.pitch - 1f) * 0.5f
            }
        } else {
            val r = engine.setLanguage(profile.locale)
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                engine.setLanguage(Locale.US)
            }
        }
        engine.setPitch(pitch)
        engine.setSpeechRate(profile.rate)
    }

    // 기기에 설치된 영어 음성 중 이 사람에게 맞는 것을 고른다
    private fun pickVoice(engine: TextToSpeech, profile: VoiceProfile): Voice? {
        val english = try {
            engine.voices.orEmpty().filter {
                it.locale.language == "en" &&
                        !it.isNetworkConnectionRequired &&
                        it.features?.contains("notInstalled") != true
            }
        } catch (e: Exception) {
            emptyList()
        }
        if (english.isEmpty()) return null

        // 억양(나라)이 맞는 음성을 먼저, 없으면 영어 음성 전체에서
        val sameAccent = english.filter { it.locale.country == profile.locale.country }
        val pool = sameAccent.ifEmpty { english }.sortedBy { it.name }

        // 성별을 알 수 있는 음성이 있으면 같은 성별 중에서 사람마다 다른 음성을 고른다
        if (profile.isFemale != null) {
            val sameGender = pool.filter { genderOf(it) == profile.isFemale }
            if (sameGender.isNotEmpty()) {
                return sameGender[abs(profile.label.hashCode()) % sameGender.size]
            }
        }
        return pool.first()
    }

    // 음성 이름에 "female"/"male"이 들어 있으면 성별을 알 수 있다 (안드로이드는 성별 정보를 따로 주지 않는다)
    private fun genderOf(voice: Voice): Boolean? {
        val name = voice.name.lowercase()
        return when {
            "female" in name -> true
            "male" in name -> false
            else -> null
        }
    }

    // 확인용: 이 기기에 설치된 영어 음성 목록
    suspend fun voiceListLines(): List<String> {
        if (!ready.await()) return listOf("TTS를 사용할 수 없습니다")
        val engine = tts ?: return emptyList()
        return try {
            val head = "TTS 엔진: ${engine.defaultEngine}"
            val voices = engine.voices.orEmpty()
                .filter { it.locale.language == "en" }
                .sortedBy { it.name }
                .map {
                    val g = genderOf(it)?.let { f -> if (f) "여" else "남" } ?: "없음"
                    "${it.name} | ${it.locale} | 성별힌트=$g | 인터넷필요=${it.isNetworkConnectionRequired}"
                }
            listOf(head) + voices
        } catch (e: Exception) {
            listOf("음성 목록을 가져오지 못함: ${e.message}")
        }
    }

    fun stop() {
        tts?.stop()
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
    }
}