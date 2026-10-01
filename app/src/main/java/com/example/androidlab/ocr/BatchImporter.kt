package com.example.androidlab.ocr

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// 여러 장 가져오기 결과
data class BatchResult(
    val saved: Int,                   // 새로 저장한 문제 수
    val duplicates: Int,              // 이미 있어서 건너뛴 문제 수
    val skipped: List<String>,        // 문제로 만들지 못한 것과 이유 (사진 잘림 등)
    val failedImages: List<String>    // 아예 읽지 못한 사진
)

class BatchImporter(private val context: Context) {

    private val importer = QuestionImporter(context)

    // 사진 여러 장(Uri 목록)을 차례로 OCR → 구조화 → DB 저장한다
    suspend fun importFromUris(
        uris: List<Uri>,
        category: String = "Part 2",
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): BatchResult {
        var saved = 0
        var duplicates = 0
        val skipped = mutableListOf<String>()
        val failed = mutableListOf<String>()

        for ((index, uri) in uris.withIndex()) {
            onProgress(index, uris.size)
            val label = "사진 ${index + 1}"
            try {
                // 사진을 똑바로 세워서 불러온다 (회전 정보 적용)
                val bitmap = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { BitmapLoader.decodeUpright(it) }
                        ?: throw IllegalStateException("파일을 열 수 없음")
                }
                try {
                    val r = importer.importFromBitmap(bitmap, category)
                    saved += r.saved
                    duplicates += r.duplicates
                    r.skipped.forEach { skipped.add("$label - $it") }
                } finally {
                    bitmap.recycle() // 큰 사진을 바로 메모리에서 비운다
                }
            } catch (e: CancellationException) {
                throw e // 화면이 닫혀서 취소된 경우는 그대로 중단
            } catch (e: Exception) {
                failed.add("$label: ${e.message}") // 한 장이 실패해도 다음 사진으로 계속 진행
            }
        }
        onProgress(uris.size, uris.size)
        return BatchResult(saved, duplicates, skipped, failed)
    }

    fun close() {
        importer.close()
    }
}