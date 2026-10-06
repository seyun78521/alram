package com.example.androidlab.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface QuestionDao {

    @Insert
    suspend fun insert(
        question: Question
    )

    @Query("""
        SELECT * FROM questions
        ORDER BY RANDOM()
        LIMIT :count
    """)
    suspend fun getRandom(
        count: Int
    ): List<Question>

    @Query("""
        SELECT COUNT(*) 
        FROM questions
        WHERE questionText = :text
    """)
    suspend fun countByQuestionText(
        text: String
    ): Int

    @Query("""
        SELECT COUNT(*) 
        FROM questions
    """)
    suspend fun count(): Int
}