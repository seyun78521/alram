package com.example.androidlab.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "questions")
data class Question(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val questionText: String,
    val choiceA: String,
    val choiceB: String,
    val choiceC: String,
    val choiceD: String,
    val correctAnswer: String,          // "A", "B", "C", "D" 중 하나
    val explanation: String = "",
    val category: String = "",          // 예: "Part 2"
    val difficulty: Int = 0,
    val imagePath: String = "",
    val questionSpeaker: String = "",   // 질문하는 사람 (예: "W-Br")
    val answerSpeaker: String = "",     // 선택지(응답)를 말하는 사람 (예: "M-Au")
    val createdAt: Long = System.currentTimeMillis()
)