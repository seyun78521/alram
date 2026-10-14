package com.example.androidlab.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

// DAO = Data Access Object. "DB에 무엇을 시킬 수 있는지" 목록만 적어 두는 인터페이스.
// 함수 몸체는 쓰지 않는다. Room이 컴파일할 때 알아서 만들어 준다. (Java의 interface와 같은 모양)
// suspend = 시간이 걸리는 작업이라 코루틴(백그라운드 작업) 안에서만 부를 수 있다.
// @Query 안의 글은 SQL이다. SELECT = 읽기, UPDATE = 바꾸기, :이름 = 함수 인자 값이 들어가는 자리.
@Dao
interface QuestionDao {

    // 문제 1개를 DB에 저장한다.
    @Insert
    suspend fun insert(question: Question)

    // 정답이 이미 입력된 문제 중에서 랜덤으로 1개를 꺼낸다. 없으면 null.
    @Query("SELECT * FROM questions WHERE correctAnswer != '' ORDER BY RANDOM() LIMIT 1")
    suspend fun getRandomWithAnswer(): Question?

    // 같은 문제 문장이 이미 저장되어 있는지 센다. (0이면 없음 → 중복 확인에 쓴다)
    @Query("SELECT COUNT(*) FROM questions WHERE questionText = :text")
    suspend fun countByQuestionText(text: String): Int

    // 정답이 아직 비어 있는 문제를 1개 꺼낸다. 없으면 null.
    @Query("SELECT * FROM questions WHERE correctAnswer = '' ORDER BY id LIMIT 1")
    suspend fun getNextWithoutAnswer(): Question?

    // 문제 하나(id)의 정답을 "A" / "B" / "C"로 저장한다. (UPDATE = 기존 값을 바꾼다)
    @Query("UPDATE questions SET correctAnswer = :answer WHERE id = :id")
    suspend fun setAnswer(id: Long, answer: String)
}