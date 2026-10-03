package com.example.androidlab.tts

import java.util.Locale

// 한 사람의 목소리 설정
data class VoiceProfile(
    val label: String,        // 예: "W-Br"
    val locale: Locale,       // 억양 (미국/영국/호주/캐나다)
    val isFemale: Boolean?    // true=여성, false=남성, null=모름
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
        return VoiceProfile(label, locale, female)
    }

    // 응답하는 사람을 모를 때: 질문하는 사람과 다른 성별의 사람으로 정한다
    fun fallbackAnswerer(asker: VoiceProfile): VoiceProfile =
        if (asker.isFemale == false) profileFor("W-Am") else profileFor("M-Au")
}