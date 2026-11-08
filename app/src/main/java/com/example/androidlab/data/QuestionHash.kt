package com.example.androidlab.data

import java.security.MessageDigest

// 문제 하나를 문자열로 바꿔 주고 이를 통해 중복을 체크
object QuestionHash {

    fun of(q: Question): String {
        // 1) 질문과 보기를 정리해서 하나의 글로 이어 붙인다. ("\u0001" = 글 사이를 나누는 보이지 않는 문자)
        val raw = listOf(q.questionText, q.choiceA, q.choiceB, q.choiceC)
            .joinToString("\u0001") { normalize(it) }

        // 2) 글을 바이트로 바꿔 SHA-256(해시값으로 변환)을 적용한다.
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(Charsets.UTF_8))

        // 3) 32바이트를 두 자리 16진수 글자로 바꿔 이어 붙인다
        return bytes.joinToString("") { "%02x".format(it) }
    }

    // 공백과 대소문자 차이를 없애서 사실상 같은 문제를 같게 본다.
    private fun normalize(s: String): String =
        s.trim().replace(Regex("\\s+"), " ").lowercase()
}