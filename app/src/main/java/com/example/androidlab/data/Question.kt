package com.example.androidlab.data

import androidx.room.Entity
import androidx.room.PrimaryKey

// DB의 "questions" 표 한 줄(= 문제 하나)을 나타내는 클래스.
// @Entity = "이 클래스를 DB 표로 만들어라"라는 표시 (Room이 읽는다)
// data class = 값을 담는 상자 전용 클래스. Java로 치면 getter, equals, toString을 자동으로 만들어 주는 클래스다.
@Entity(tableName = "questions")
data class Question(

    // 문제 고유 번호. autoGenerate = true 이면 저장할 때 1, 2, 3... 자동으로 붙는다.
    // val = 한 번 정하면 바꿀 수 없는 값 (Java의 final)
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

    // 해설. 지금은 쓰지 않고 항상 빈 문자열("")로 저장한다. (나중을 위한 빈 칸)
    // "= """ 는 값을 안 주면 빈 문자열을 쓴다는 기본값이다.
    val explanation: String = "",

    // 저장한 시각 (기본값: 저장하는 순간의 시간)
    val createdAt: Long = System.currentTimeMillis()
)