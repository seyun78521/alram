package com.example.androidlab.data

import androidx.room.Entity
import androidx.room.PrimaryKey

// DB의 "questions" 표 한 줄(= 문제 하나)을 나타내는 클래스.
// @Entity = "이 클래스를 DB 표로 만들어라"라는 표시 (Room이 읽는다)
@Entity(tableName = "questions")
data class Question(

    // 문제 고유 번호. autoGenerate = true 이면 저장할 때 1, 2, 3... 자동으로 붙는다.
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    // 질문 문장 (화자 표시는 빠져 있다)
    val questionText: String,

    // 선택지 A, B, C
    val choiceA: String,
    val choiceB: String,
    val choiceC: String,

    // 정답 "A" / "B" / "C". 저장할 때는 비워 두고(""), 나중에 사람이 입력한다.
    val correctAnswer: String,

    // 저장한 시각
    val createdAt: Long = System.currentTimeMillis()
) {

    // 사용자가 고른 답이 정답인지 확인한다.
    // 정답이 비어 있으면 항상 false가 된다.
    fun isCorrect(selectedAnswer: String): Boolean =
        correctAnswer.equals(selectedAnswer.trim(), ignoreCase = true)
}