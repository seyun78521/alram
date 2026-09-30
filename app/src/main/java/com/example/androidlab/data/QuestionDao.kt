package com.example.androidlab.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface QuestionDao {

    // 문제 1개 저장 (저장된 문제의 id를 돌려줌)
    @Insert
    suspend fun insert(question: Question): Long

    // 문제 여러 개 한 번에 저장
    @Insert
    suspend fun insertAll(questions: List<Question>)

    // id로 문제 1개 조회 (없으면 null)
    @Query("SELECT * FROM questions WHERE id = :id")
    suspend fun getById(id: Long): Question?

    // 전체에서 랜덤으로 count개 조회 (한 번 조회할 때 중복 없음)
    @Query("SELECT * FROM questions ORDER BY RANDOM() LIMIT :count")
    suspend fun getRandom(count: Int): List<Question>

    // 특정 category(예: "Part 3")에서만 랜덤으로 count개 조회
    @Query("SELECT * FROM questions WHERE category = :category ORDER BY RANDOM() LIMIT :count")
    suspend fun getRandomByCategory(category: String, count: Int): List<Question>

    // 같은 문제 문장이 이미 저장되어 있는지 (0이면 없음)
    @Query("SELECT COUNT(*) FROM questions WHERE questionText = :text")
    suspend fun countByQuestionText(text: String): Int

    // 저장된 문제가 총 몇 개인지
    @Query("SELECT COUNT(*) FROM questions")
    suspend fun count(): Int

    // 전체 삭제 (테스트용)
    @Query("DELETE FROM questions")
    suspend fun deleteAll()
}