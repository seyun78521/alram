package com.example.androidlab.ocr

import android.content.Context
import android.net.Uri
import com.example.androidlab.data.AppDatabase

/**
 * 이미지에서 문제를 읽어서 Room DB에 저장한다.
 *
 * 한 이미지에 여러 문제가 있어도 처리한다.
 */
class QuestionImporter(
    context: Context
) {

    private val appContext =
        context.applicationContext

    private val database =
        AppDatabase.getInstance(appContext)

    private val questionDao =
        database.questionDao()

    private val ocr =
        QuestionOcr()

    private val parser =
        QuestionParser()

    /**
     * 여러 이미지 URI를 한꺼번에 처리한다.
     *
     * 예:
     *
     * [페이지1.jpg, 페이지2.jpg]
     *
     * 각 페이지 안의 문제들을 모두 DB에 넣는다.
     */
    suspend fun importFromUris(
        uris: List<Uri>
    ): Result {

        var saved = 0
        var failed = 0

        for (uri in uris) {

            try {

                // ---------------------------------
                // 1. 이미지 읽기
                // ---------------------------------
                val bitmap =
                    BitmapLoader.load(
                        appContext,
                        uri
                    )

                // ---------------------------------
                // 2. OCR
                // ---------------------------------
                val ocrLines =
                    ocr.recognize(bitmap)

                // ---------------------------------
                // 3. 문제 여러 개로 분리
                // ---------------------------------
                val questions =
                    parser.parsePage(
                        bitmap = bitmap,
                        ocrLines = ocrLines,
                        imagePath = uri.toString()
                    )

                // 문제를 하나도 못 찾은 경우
                if (questions.isEmpty()) {

                    failed++

                    continue
                }

                // ---------------------------------
                // 4. DB 저장
                // ---------------------------------
                for (question in questions) {

                    // 같은 문제는 중복 저장하지 않는다.
                    val duplicateCount =
                        questionDao.countByQuestionText(
                            question.questionText
                        )

                    if (duplicateCount == 0) {

                        questionDao.insert(question)

                        saved++
                    }
                }

            } catch (e: Exception) {

                // 한 페이지에서 오류가 나더라도
                // 다른 페이지는 계속 처리한다.
                failed++
            }
        }

        return Result(
            saved = saved,
            failed = failed
        )
    }

    /**
     * OCR 종료
     */
    fun close() {
        ocr.close()
    }

    /**
     * import 결과
     */
    data class Result(
        val saved: Int,
        val failed: Int
    )
}