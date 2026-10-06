package com.example.androidlab.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "questions")
data class Question(

    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    // OCR 원본
    val rawText: String,

    // 문제
    val questionText: String,

    // 선택지
    val choiceA: String,
    val choiceB: String,
    val choiceC: String,

    // 정답
    val correctAnswer: String,

    // 해설
    val explanation: String = "",

    // 문제 종류
    val category: String = "Part 2",

    // 원본 이미지
    val imagePath: String = "",

    // 저장 시간
    val createdAt: Long = System.currentTimeMillis()
) {

    /**
     * 사용자가 선택한 답이 정답인지 확인
     */
    fun isCorrect(
        selectedAnswer: String
    ): Boolean {

        return correctAnswer.equals(
            selectedAnswer.trim(),
            ignoreCase = true
        )
    }
}