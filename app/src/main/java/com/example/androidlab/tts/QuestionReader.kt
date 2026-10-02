package com.example.androidlab.tts

import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import com.example.androidlab.data.Question
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class QuestionReader(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("tts_voices", Context.MODE_PRIVATE) // 이 폰에 저장되는 음성 선택

    private var tts: TextToSpeech? = null
    private val ready = CompletableDeferred<Boolean>()                 // TTS 준비 완료 여부
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val counter = AtomicInteger()
    private var currentVoiceName: String? = null                       // 지금 적용된 음성 (같으면 다시 바꾸지 않는다)

    // 폰마다 음성 이름이 같도록 Google 음성 엔진을 지정한다 (없으면 기본 엔진으로 자동 전환)
    private val googleEngine = "com.google.android.tts"

    // 개발 기기에서 직접 들어보고 정한 기본 음성 (같은 이름의 음성이 있는 폰에서는 설정 없이 바로 쓰인다)
    private val voiceByLabel = mapOf(
        "W-Am" to "en-us-x-tpf-local",
        "W-Br" to "en-us-x-tpc-local",
        "M-Au" to "en-us-x-tpd-local",
        "M-Cn" to "en-us-x-msm00013-local"
    )
    private val speakerLabels = voiceByLabel.keys.toList()

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
        start(googleEngine)
    }

    // TTS를 시작한다. 지정한 엔진을 쓸 수 없으면 기본 엔진으로 다시 시도한다.
    private fun start(engineName: String?) {
        tts = TextToSpeech(appContext, { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.setOnUtteranceProgressListener(listener)
                Log.d("TTS", "TTS 시작 (요청한 엔진: ${engineName ?: "기본"})")
                ready.complete(true)
            } else if (engineName != null) {
                Log.d("TTS", "$engineName 엔진을 쓸 수 없어 기본 엔진으로 다시 시도합니다")
                tts?.shutdown()
                start(null)
            } else {
                ready.complete(false)
            }
        }, engineName)
    }

    // 문제 하나를 소리 내어 읽는다: 질문(질문하는 사람 목소리) → 선택지(응답하는 사람 목소리)
    suspend fun read(q: Question): Boolean {
        if (!ready.await()) return false
        val engine = tts ?: return false

        val asker = SpeakerVoices.profileFor(q.questionSpeaker.ifEmpty { "W-Am" })
        val answerer =
            if (q.answerSpeaker.isNotEmpty()) SpeakerVoices.profileFor(q.answerSpeaker)
            else SpeakerVoices.fallbackAnswerer(asker)

        // 질문하는 사람과 응답하는 사람은 항상 다른 음성이 되게 고른다
        val askerVoice = voiceFor(engine, asker, null)
        val answererVoice = voiceFor(engine, answerer, askerVoice)

        if (!speakAll(listOf(q.questionText), asker, askerVoice, 0)) return false
        delay(700)

        // 선택지는 같은 사람이 말하므로 한 번에 이어서 읽는다
        val choices = listOf("A" to q.choiceA, "B" to q.choiceB, "C" to q.choiceC, "D" to q.choiceD)
            .filter { it.second.isNotBlank() }
            .map { "${it.first}. ${it.second}" }
        return speakAll(choices, answerer, answererVoice, 600)
    }

    // 확인용: 네 사람이 같은 문장을 차례로 말한다. 목소리가 다르게 들리는지 귀로 확인한다.
    suspend fun demo(): Boolean {
        if (!ready.await()) return false
        val engine = tts ?: return false
        for (label in speakerLabels) {
            val profile = SpeakerVoices.profileFor(label)
            val sentence = "Hello, this is $label. Did you receive the e-mail I sent?"
            if (!speakAll(listOf(sentence), profile, voiceFor(engine, profile, null), 0)) return false
            delay(600)
        }
        return true
    }

    // 확인용: 이 기기에 설치된 영어 음성을 하나씩 차례로 들려준다.
    suspend fun audition(onEach: (index: Int, total: Int, description: String) -> Unit): Boolean {
        if (!ready.await()) return false
        val engine = tts ?: return false

        val voices = auditionVoices(engine)
        if (voices.isEmpty()) return false

        for ((i, v) in voices.withIndex()) {
            val net = if (v.isNetworkConnectionRequired) ", 인터넷 필요" else ""
            val description = "${v.name} (${v.locale}$net)"
            Log.d("VOICES", "[${i + 1}/${voices.size}] $description")
            onEach(i + 1, voices.size, description)

            engine.setVoice(v)
            currentVoiceName = v.name
            engine.setPitch(1.0f)
            engine.setSpeechRate(1.0f)
            val played = playQueue(engine, listOf("Number ${i + 1}. Did you receive the e-mail I sent?"), 0)
            if (!played) Log.d("VOICES", "[${i + 1}] 재생 실패 (인터넷 필요 음성이거나 음성 데이터 문제)")
            delay(400)
        }
        return true
    }

    // ===== 폰별 음성 설정 =====

    // 이 폰에 저장된 선택 (없으면 null)
    fun savedVoiceName(label: String): String? = prefs.getString("voice_$label", null)

    fun saveVoice(label: String, voiceName: String) {
        prefs.edit().putString("voice_$label", voiceName).apply()
    }

    fun clearSavedVoices() {
        prefs.edit().clear().apply()
    }

    // 설정 화면에 보여줄 음성 목록: 인터넷 없이 쓸 수 있는 설치된 영어 음성
    suspend fun installedEnglishVoices(): List<Voice> {
        if (!ready.await()) return emptyList()
        val engine = tts ?: return emptyList()
        return installedLocalEnglish(engine).sortedBy { it.name }
    }

    // 이 폰에서 음성 설정이 필요한가? (네 사람 중 쓸 음성을 못 찾는 사람이 있으면 true)
    suspend fun needsVoiceSetup(): Boolean {
        if (!ready.await()) return true
        val engine = tts ?: return true
        val installed = installedLocalEnglish(engine)
        return speakerLabels.any { label ->
            val saved = savedVoiceName(label)
            val known = voiceByLabel[label]
            installed.none { it.name == saved || it.name == known }
        }
    }

    // 선택한 음성을 미리 들려준다
    suspend fun preview(voice: Voice, label: String): Boolean {
        if (!ready.await()) return false
        val engine = tts ?: return false
        engine.setVoice(voice)
        currentVoiceName = voice.name
        engine.setPitch(1.0f)
        engine.setSpeechRate(1.0f)
        return playQueue(engine, listOf("Hello, this is $label. Did you receive the e-mail I sent?"), 0)
    }

    // 영어 음성 데이터가 없을 때 설치 화면을 연다
    fun requestVoiceInstall() {
        try {
            appContext.startActivity(
                Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            Log.d("TTS", "음성 설치 화면을 열 수 없음: ${e.message}")
        }
    }

    // ===== 읽기 내부 동작 =====

    // 같은 사람의 문장들을 이어서 읽고, 마지막 문장이 끝날 때까지 기다린다
    private suspend fun speakAll(texts: List<String>, profile: VoiceProfile, voice: Voice?, gapMs: Long): Boolean {
        val engine = tts ?: return false
        applyProfile(engine, profile, voice)
        return playQueue(engine, texts, gapMs)
    }

    // 문장들을 순서대로 재생하고 마지막 문장이 끝나면 돌아온다
    private suspend fun playQueue(engine: TextToSpeech, texts: List<String>, gapMs: Long): Boolean {
        val lastDone = CompletableDeferred<Boolean>()
        var lastId = ""
        for (index in texts.indices) {
            val id = "utt" + counter.incrementAndGet()
            val isLast = index == texts.lastIndex
            if (isLast) {
                lastId = id
                pending[id] = lastDone
            }
            val mode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            if (engine.speak(texts[index], mode, null, id) != TextToSpeech.SUCCESS) {
                pending.remove(lastId)
                return false
            }
            if (!isLast && gapMs > 0) {
                engine.playSilentUtterance(gapMs, TextToSpeech.QUEUE_ADD, "sil" + counter.incrementAndGet())
            }
        }
        return try {
            lastDone.await()
        } catch (e: CancellationException) {
            engine.stop()
            pending.remove(lastId)
            throw e
        }
    }

    // 이 사람의 목소리를 적용한다. 고른 음성이 있으면 그 음성을 그대로(음높이 변경 없이) 쓴다.
    private fun applyProfile(engine: TextToSpeech, profile: VoiceProfile, voice: Voice?) {
        val pitch: Float
        if (voice != null) {
            if (voice.name != currentVoiceName) {
                engine.setVoice(voice)
                currentVoiceName = voice.name
            }
            pitch = 1.0f
        } else {
            // 쓸 음성을 못 찾은 경우: 언어만 맞추고 음높이로 구분한다 (성별 구분이 약하다)
            val r = engine.setLanguage(profile.locale)
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                engine.setLanguage(Locale.US)
            }
            currentVoiceName = null
            pitch = profile.pitch
        }
        engine.setPitch(pitch)
        engine.setSpeechRate(profile.rate)

        Log.d(
            "TTS",
            "적용: ${profile.label} | 음성=${voice?.name ?: "(없음: 언어만 설정)"} | 억양=${voice?.locale ?: profile.locale} " +
                    "| 음높이=$pitch | 속도=${profile.rate}"
        )
    }

    // 인터넷 없이 쓸 수 있는, 설치된 영어 음성
    private fun installedLocalEnglish(engine: TextToSpeech): List<Voice> = try {
        engine.voices.orEmpty().filter {
            it.locale.language == "en" &&
                    !it.isNetworkConnectionRequired &&
                    it.features?.contains("notInstalled") != true
        }
    } catch (e: Exception) {
        emptyList()
    }

    // "설치된 영어 음성 전부 들어보기"에 쓰는 목록 (인터넷 필요한 음성도 포함)
    private fun auditionVoices(engine: TextToSpeech): List<Voice> = try {
        engine.voices.orEmpty()
            .filter { it.locale.language == "en" && it.features?.contains("notInstalled") != true }
            .sortedBy { it.name }
    } catch (e: Exception) {
        emptyList()
    }

    // 이 사람에게 쓸 음성을 고른다.
    // 순서: ① 이 폰에 저장한 선택 ② 정해 둔 기본 음성 ③ 같은 성별 후보 ④ (없으면 null → 음높이 방식)
    // 앞사람(avoid)과 같은 음성이면 같은 성별의 다른 음성으로 바꾼다.
    private fun voiceFor(engine: TextToSpeech, profile: VoiceProfile, avoid: Voice?): Voice? {
        val installed = installedLocalEnglish(engine)
        fun byName(name: String?): Voice? = installed.firstOrNull { it.name == name }

        val prefix = profile.label.firstOrNull()?.toString() // "W" 또는 "M"

        // 같은 성별 후보: 이 폰에 저장된 음성 → 정해 둔 음성 → 이름에 성별 힌트가 있는 음성
        val sameGender: List<Voice> =
            if (prefix == null || profile.isFemale == null) emptyList()
            else {
                val saved = prefs.all.filterKeys { it.startsWith("voice_$prefix-") }
                    .values.mapNotNull { byName(it as? String) }
                val known = voiceByLabel.filterKeys { it.startsWith("$prefix-") }
                    .values.mapNotNull { byName(it) }
                val hinted = installed.filter { genderOf(it) == profile.isFemale }
                (saved + known + hinted).distinctBy { it.name }
            }

        var voice = byName(savedVoiceName(profile.label))
            ?: byName(voiceByLabel[profile.label])
            ?: sameGender.firstOrNull()

        if (voice != null && avoid != null && voice.name == avoid.name) {
            voice = sameGender.firstOrNull { it.name != avoid.name } ?: voice
        }
        return voice
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