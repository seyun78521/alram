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
    private val ocr = QuestionOcr() // 여러 장을 처리할 때 재사용한다

    // 이미지 1장 → OCR → 문제 구조화 → DB 저장
    suspend fun importFromBitmap(bitmap: Bitmap, category: String = "Part 2"): ImportResult {
        // 1) 글자 인식 (위치 기준으로 읽는 순서 정렬)
        val lines = ocr.recognizeLines(bitmap)

        // 2) 문제/선택지/정답/해설로 구조화 (정답 선택지는 빨간 글자색으로도 확인)
        val parsed = QuestionParser.parse(lines.map { it.text }, category) { index ->
            InkColor.redRatio(bitmap, lines[index].corners)
        }

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
    }

    // 이미지를 (긴 변 2000px로 줄여서) 앱 내부 저장소에 보관하고, 그 경로를 돌려준다
    private suspend fun saveImage(bitmap: Bitmap): String = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, "question_images").apply { mkdirs() }
        val file = File.createTempFile("img_", ".jpg", dir) // 겹치지 않는 이름을 자동으로 만든다
        val scaled = scaleDown(bitmap, 2000)
        file.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        if (scaled != bitmap) scaled.recycle()
        file.absolutePath
    }

    private fun scaleDown(bitmap: Bitmap, maxSide: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxSide) return bitmap
        val ratio = maxSide.toFloat() / longest
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * ratio).toInt(),
            (bitmap.height * ratio).toInt(),
            true
        )
    }

    fun close() {
        ocr.close()
    }
}