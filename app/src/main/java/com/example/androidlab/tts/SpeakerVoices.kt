package com.example.androidlab.tts

import java.util.Locale

// 한 사람의 목소리 설정
data class VoiceProfile(
    val label: String,        // 예: "W-Br"
    val locale: Locale,       // 억양 (미국/영국/호주/캐나다)
    val isFemale: Boolean?,   // true=여성, false=남성, null=모름
    val pitch: Float,         // 음높이 (1.0이 기본)
    val rate: Float           // 말하는 속도 (1.0이 기본)
)

object SpeakerVoices {

    // "W-Br" 같은 화자 표시를 목소리 설정으로 바꾼다
    fun profileFor(label: String): VoiceProfile {
        val female = when (label.firstOrNull()) {
            'W' -> true
            'M' -> false
            else -> null
        }
        val locale = when (label.substringAfter('-', "").lowercase()) {
            "br" -> Locale.forLanguageTag("en-GB")
            "au" -> Locale.forLanguageTag("en-AU")
            "cn" -> Locale.forLanguageTag("en-CA")
            else -> Locale.US // "Am" 이거나 모르는 경우
        }
        // 같은 성별이어도 사람마다 다르게 들리도록 음높이와 속도를 조금씩 다르게 한다
        val (pitch, rate) = when (label) {
            "W-Am" -> 1.10f to 1.00f
            "W-Br" -> 1.30f to 0.95f
            "M-Au" -> 0.85f to 1.00f
            "M-Cn" -> 0.70f to 0.95f
            else -> when (female) {
                true -> 1.20f to 1.00f
                false -> 0.80f to 1.00f
                null -> 1.00f to 1.00f
            }
        }
        return VoiceProfile(label, locale, female, pitch, rate)
    }

    // 응답하는 사람을 모를 때: 질문하는 사람과 다른 성별의 사람으로 정한다
    fun fallbackAnswerer(asker: VoiceProfile): VoiceProfile =
        if (asker.isFemale == false) profileFor("W-Am") else profileFor("M-Au")
}