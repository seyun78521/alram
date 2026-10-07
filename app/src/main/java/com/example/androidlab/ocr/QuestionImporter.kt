package com.example.androidlab.ocr

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.example.androidlab.data.AppDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * 사진 여러 장 → OCR → 문제 분리 → DB 저장을 한 번에 해 주는 클래스.
 * 화면(MainActivity)은 이 클래스의 importFromUris()만 부르면 된다.
 */
class QuestionImporter(private val context: Context) {

    private val dao = AppDatabase.getInstance(context).questionDao() // DB에 문제를 넣고 빼는 도구
    private val ocr = QuestionOcr()                                  // 사진에서 글자를 읽는 도구

    // 처리 결과를 담는 상자
    // saved: 새로 저장한 문제 수 / failed: 읽지 못한 사진 / skipped: 문제로 못 만든 것
    data class Result(
        val saved: Int,
        val failed: List<String>,
        val skipped: List<String> = emptyList()
    )

    // ===== 사진 여러 장 처리 =====
    // uris = 사진 파일의 주소 목록 (갤러리/파일 선택 창에서 받은 것)
    // onProgress = 진행 상황을 알려 주는 함수 (예: "2 / 5")
    // suspend = 오래 걸리는 작업이라 코루틴(백그라운드) 안에서만 부를 수 있다.
    suspend fun importFromUris(
        uris: List<Uri>,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): Result {
        var saved = 0
        val failed = mutableListOf<String>()
        val skipped = mutableListOf<String>()

        // 사진을 한 장씩 처리한다. withIndex() = (번호, 값)을 같이 꺼내 준다.
        for ((index, uri) in uris.withIndex()) {
            onProgress(index, uris.size)

            try {
                // 1) 사진 파일을 읽어서 Bitmap(사진 데이터)으로 만든다.
                //    파일 읽기는 오래 걸리므로 Dispatchers.IO(파일용 백그라운드)에서 한다.
                val bitmap = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)
                        ?.use { BitmapLoader.decodeUpright(it) } // use = 다 쓰면 파일을 자동으로 닫는다
                        ?: throw IllegalStateException("이미지를 읽을 수 없습니다.")
                }

                try {
                    // 2) 사진 한 장을 처리한다 (OCR → 분리 → 저장)
                    val result = importFromBitmap(bitmap)
                    saved += result.saved
                    skipped.addAll(result.skipped)
                } finally {
                    bitmap.recycle() // 큰 사진은 메모리를 많이 쓰므로 다 쓰면 바로 비운다
                }

            } catch (e: CancellationException) {
                throw e // 화면이 닫혀서 작업이 취소된 경우는 그대로 중단한다
            } catch (e: Exception) {
                // 사진 한 장이 실패해도 멈추지 않고 이유만 적어 두고 다음 사진으로 간다
                failed.add("${index + 1}번째 이미지: ${e.message ?: "알 수 없는 오류"}")
            }

            onProgress(index + 1, uris.size)
        }

        return Result(saved, failed, skipped)
    }

    // ===== 사진 한 장 처리 =====
    // 한 페이지에 문제가 여러 개 있을 수 있다.
    private suspend fun importFromBitmap(bitmap: Bitmap): Result {
        // 1) OCR: 사진 → 읽는 순서대로 정렬된 글자 줄들
        val lines = ocr.recognizeLines(bitmap)

        // 2) 파서: 줄들 → 문제 목록 (문제 문장 + A/B/C)
        val parsed = QuestionParser.parse(lines)

        // 3) 이미 저장된 문제(같은 문제 문장)는 빼고, 새 문제만 남긴다
        val newQuestions = parsed.questions.filter {
            dao.countByQuestionText(it.questionText) == 0
        }

        // 4) 새 문제를 DB에 하나씩 저장한다
        for (question in newQuestions) {
            dao.insert(question)
        }

        return Result(newQuestions.size, emptyList(), parsed.skipped)
    }

    // 다 쓰면 OCR 도구를 닫는다 (화면이 닫힐 때 부른다)
    fun close() {
        ocr.close()
    }
}