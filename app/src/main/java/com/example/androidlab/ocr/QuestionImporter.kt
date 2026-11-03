package com.example.androidlab.ocr

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.example.androidlab.data.AppDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * 사진 여러 장 → OCR → 문제 분리 → 해설 붙이기 → DB 저장을 한 번에 해 주는 클래스.
 * 화면(MainActivity)은 이 클래스의 importFromUris()만 부르면 된다.
 *
 * 이 파일에 나오는 Kotlin 표기
 *  - suspend = 오래 걸리는 작업이라 코루틴(백그라운드 작업) 안에서만 부를 수 있는 함수
 *  - withContext(Dispatchers.IO) = 파일 읽기 같은 일을 전용 백그라운드에서 한다
 *  - data class = getter 등을 자동으로 만들어 주는 값 전용 클래스 (Java의 단순 VO)
 *  - ?: = 왼쪽이 null이면 오른쪽 실행
 */
class QuestionImporter(private val context: Context) {

    private val dao = AppDatabase.getInstance(context).questionDao() // DB에 문제를 넣고 빼는 도구
    private val ocr = QuestionOcr()                                  // 사진에서 글자를 읽는 도구

    // 처리 결과를 담는 상자
    // saved: 새로 저장한 문제 수 / failed: 읽지 못한 사진 / skipped: 문제로 못 만든 것
    data class Result(
        val saved: Int,
        val failed: List<String>,
        val skipped: List<String> = emptyList() // 값을 안 주면 빈 목록
    )

    // ===== 사진 여러 장 처리 =====
    // uris = 사진 파일의 주소 목록 (갤러리/파일 선택 창에서 받은 것)
    // onProgress = 진행 상황을 알려 주는 함수 (예: "2 / 5"). Java의 콜백과 같은 역할이다.
    suspend fun importFromUris(
        uris: List<Uri>,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): Result {
        var saved = 0
        val failed = mutableListOf<String>()  // mutable = 나중에 add 할 수 있는 목록
        val skipped = mutableListOf<String>()

        // 사진을 한 장씩 처리한다. withIndex() = (번호, 값)을 같이 꺼내 준다.
        for ((index, uri) in uris.withIndex()) {
            onProgress(index, uris.size)

            try {
                // 1) 사진 파일을 읽어서 Bitmap(사진 데이터)으로 만든다.
                val bitmap = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)
                        ?.use { BitmapLoader.decodeUpright(it) } // use = 다 쓰면 파일을 자동으로 닫는다 (Java의 try-with-resources)
                        ?: throw IllegalStateException("이미지를 읽을 수 없습니다.")
                }

                // 2) 사진 한 장을 처리한다 (OCR → 분리 → 해설 붙이기 → 저장)
                val result = importFromBitmap(bitmap)
                saved += result.saved
                skipped.addAll(result.skipped)

            } catch (e: CancellationException) {
                // 화면이 닫혀서 작업이 취소된 경우다. 오류가 아니므로 기록하지 않고 그대로 위로 던져 작업을 끝낸다.
                // (이걸 아래 Exception에서 잡아 버리면 취소가 제대로 안 된다)
                throw e
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
        // 1) 한글 OCR을 먼저 실행. 해설을 읽고, 쓰레기값 판정에 활용
        var koreanLines = emptyList<OcrLine>()
        try {
            koreanLines = ocr.recognizeKoreanLines(bitmap)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 해설 없이 계속 진행한다
        }

        // 2) 영어 OCR: 사진 → 읽는 순서대로 정렬된 글자 줄들
        val allLines = ocr.recognizeLines(bitmap)

        // 3) 한글이 있는 자리의 영어 줄(= 한글을 잘못 읽은 쓰레기)을 지운다
        val lines = removeHangulAreas(allLines, koreanLines)

        // 4) 파서: 줄들 → 번호가 붙은 문제 목록 (문제 문장 + A/B/C)
        val parsed = QuestionParser.parse(lines)

        // 5) 한글 줄들에서 해설을 번호별로 정리한다, 문제가 없으면 스킵
        var explanations = emptyMap<Int, String>()
        if (parsed.questions.isNotEmpty()) {
            explanations = QuestionParser.parseExplanations(koreanLines)
        }

        // 6) 번호가 같은 문제에만 해설을 붙인다
        val questions = QuestionParser.attachExplanations(parsed.questions, explanations)

        // 7) 이미 저장된 문제(같은 문제 문장)는 빼고, 새 문제만 남긴다
        //    filter { 조건 } = 조건이 true인 것만 남긴다
        val newQuestions = questions.filter {
            dao.countByQuestionText(it.questionText) == 0
        }

        // 8) 새 문제를 DB에 하나씩 저장한다
        for (question in newQuestions) {
            dao.insert(question)
        }

        return Result(newQuestions.size, emptyList(), parsed.skipped)
    }

    // 영어 OCR이 한글을 인식한 쓰레기값 삭제
    private fun removeHangulAreas(latin: List<OcrLine>, korean: List<OcrLine>): List<OcrLine> {
        // 한글이 2글자 이상 있는 줄만 "한글 줄"
        // count { 조건 } = 조건을 만족하는 글자 수
        val hangulLines = korean.filter { line -> line.text.count { it in '가'..'힣' } >= 2 }

        // none { 조건 } = 조건을 만족하는 것이 하나도 없으면 true
        // box.contains(x, y) = 점 (x, y)가 사각형 안에 있는가
        return latin.filter { l ->
            hangulLines.none { it.box.contains(l.box.centerX(), l.box.centerY()) }
        }
    }

    // 다 쓰면 OCR 도구를 닫는다 (화면이 닫힐 때 부른다)
    fun close() {
        ocr.close()
    }
}