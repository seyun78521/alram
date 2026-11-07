package com.example.androidlab.data

import java.security.MessageDigest

object QuestionHash {

    fun of(q: Question): String {
        val raw = listOf(q.questionText, q.choiceA, q.choiceB, q.choiceC)
            .joinToString("\u0001") { normalize(it) }
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun normalize(s: String): String =
        s.trim().replace(Regex("\\s+"), " ").lowercase()
}