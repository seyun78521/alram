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
    val category: String = "",          // 예: "Part 3"
    val difficulty: Int = 0,
    val imagePath: String = "",
    val createdAt: Long = System.currentTimeMillis()
)