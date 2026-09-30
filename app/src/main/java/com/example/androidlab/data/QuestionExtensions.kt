package com.example.androidlab.data

// TTS가 읽어줄 문장 (문제 + 선택지, D가 비어 있으면 D는 뺀다)
fun Question.toSpeechText(): String {
    val sb = StringBuilder(questionText)
    sb.append("\nA. $choiceA\nB. $choiceB\nC. $choiceC")
    if (choiceD.isNotBlank()) sb.append("\nD. $choiceD")
    return sb.toString()
}

// 사용자가 고른 답("A"~"C")이 정답인지 확인
fun Question.isCorrect(selected: String): Boolean =
    selected.trim().equals(correctAnswer, ignoreCase = true)