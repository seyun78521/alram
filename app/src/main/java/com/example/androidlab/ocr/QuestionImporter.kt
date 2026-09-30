package com.example.androidlab.ocr

import android.content.Context
import android.graphics.Bitmap
import com.example.androidlab.data.AppDatabase
import com.example.androidlab.data.Question
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

// 가져오기 결과: 새로 저장한 개수 / 이미 있어서 건너뛴 개수 / 구조화 실패한 문제와 이유
data class ImportResult(
    val saved: Int,
    val duplicates: Int,
    val skipped: List<String>
)

class QuestionImporter(private val context: Context) {

    private val dao = AppDatabase.getInstance(context).questionDao()

    // 이미지 1장 → OCR → 문제 구조화 → DB 저장
    suspend fun importFromBitmap(bitmap: Bitmap, category: String = "part 2"): ImportResult {
        val ocr = QuestionOcr()
        try {
            // 1) 글자 인식
            val lines = ocr.recognizeOrdered(bitmap)

            // 2) 문제/선택지/정답/해설로 구조화
            val parsed = QuestionParser.parse(lines, category)

            // 3) 이미 저장된 문제는 제외
            val newOnes = mutableListOf<Question>()
            var duplicates = 0
            for (q in parsed.questions) {
                if (dao.countByQuestionText(q.questionText) > 0) duplicates++ else newOnes.add(q)
            }

            // 4) 저장할 문제가 있을 때만 이미지를 보관하고 DB에 저장
            if (newOnes.isNotEmpty()) {
                val path = saveImage(bitmap)
                dao.insertAll(newOnes.map { it.copy(imagePath = path) })
            }

            return ImportResult(newOnes.size, duplicates, parsed.skipped)
        } finally {
            ocr.close()
        }
    }

    // 이미지를 앱 내부 저장소에 복사해 두고, 그 경로를 돌려준다
    private suspend fun saveImage(bitmap: Bitmap): String = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, "question_images").apply { mkdirs() }
        val file = File(dir, "q_${System.currentTimeMillis()}.jpg")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        file.absolutePath
    }
}