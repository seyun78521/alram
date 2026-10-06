package com.example.androidlab.ocr

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.example.androidlab.data.AppDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/*
 * 문제 이미지 여러 장을
 * OCR -> 문제 분리 -> DB 저장한다.
 */
class QuestionImporter(
    private val context: Context
) {

    private val dao =
        AppDatabase
            .getInstance(context)
            .questionDao()

    private val ocr =
        QuestionOcr()

    /*
     * 가져오기 결과
     */
    data class Result(
        val saved: Int,
        val failed: List<String>,
        val skipped: List<String> = emptyList()
    )

    /*
     * 여러 이미지 처리
     */
    suspend fun importFromUris(
        uris: List<Uri>,
        onProgress: (
            done: Int,
            total: Int
        ) -> Unit = { _, _ -> }
    ): Result {

        var saved = 0

        val failed =
            mutableListOf<String>()

        val skipped =
            mutableListOf<String>()

        /*
         * 이미지 한 장씩 처리한다.
         */
        for (
        (index, uri)
        in uris.withIndex()
        ) {

            onProgress(
                index,
                uris.size
            )

            try {

                /*
                 * 1. 이미지 읽기
                 */
                val bitmap =
                    withContext(
                        Dispatchers.IO
                    ) {

                        context
                            .contentResolver
                            .openInputStream(uri)
                            ?.use {
                                BitmapLoader
                                    .decodeUpright(it)
                            }
                            ?: throw IllegalStateException(
                                "이미지를 읽을 수 없습니다."
                            )
                    }

                try {

                    /*
                     * 2. 한 페이지에서
                     *    여러 문제를 OCR
                     */
                    val result =
                        importFromBitmap(
                            bitmap
                        )

                    saved +=
                        result.saved

                    skipped.addAll(
                        result.skipped
                    )

                } finally {

                    /*
                     * 사진이 크기 때문에
                     * 사용 후 메모리에서 제거
                     */
                    bitmap.recycle()
                }

            } catch (
                e: CancellationException
            ) {

                throw e

            } catch (
                e: Exception
            ) {

                failed.add(
                    "${index + 1}번째 이미지: " +
                            (e.message
                                ?: "알 수 없는 오류")
                )
            }

            onProgress(
                index + 1,
                uris.size
            )
        }

        return Result(
            saved = saved,
            failed = failed,
            skipped = skipped
        )
    }

    /*
     * 페이지 한 장 처리
     *
     * 한 페이지 안에
     * 문제 여러 개가 있을 수 있다.
     */
    private suspend fun importFromBitmap(
        bitmap: Bitmap
    ): Result {

        /*
         * 1. OCR
         */
        val lines =
            ocr.recognizeLines(
                bitmap
            )

        /*
         * 2. 여러 문제로 분리
         */
        val parsed =
            QuestionParser.parse(
                lines
            )

        /*
         * 3. 문제 저장
         */
        var saved = 0

        for (
        question in parsed.questions
        ) {

            /*
             * 같은 문제인지 확인
             */
            val duplicateCount =
                dao.countByQuestionText(
                    question.questionText
                )

            if (
                duplicateCount > 0
            ) {
                continue
            }

            /*
             * 문제와 함께
             * 원본 페이지 이미지 경로 저장
             */
            val imagePath =
                saveImage(bitmap)

            dao.insert(
                question.copy(
                    imagePath = imagePath
                )
            )

            saved++
        }

        return Result(
            saved = saved,
            failed = emptyList(),
            skipped = parsed.skipped
        )
    }

    /*
     * 원본 이미지를
     * 앱 내부 저장소에 저장한다.
     */
    private suspend fun saveImage(
        bitmap: Bitmap
    ): String =
        withContext(
            Dispatchers.IO
        ) {

            val directory =
                File(
                    context.filesDir,
                    "question_images"
                )

            if (
                !directory.exists()
            ) {
                directory.mkdirs()
            }

            /*
             * 이미지마다 다른 파일 이름
             */
            val file =
                File.createTempFile(
                    "question_",
                    ".jpg",
                    directory
                )

            /*
             * 너무 큰 사진은 줄인다.
             */
            val scaled =
                scaleDown(
                    bitmap,
                    2000
                )

            file.outputStream()
                .use { output ->

                    scaled.compress(
                        Bitmap.CompressFormat.JPEG,
                        85,
                        output
                    )
                }

            if (
                scaled != bitmap
            ) {
                scaled.recycle()
            }

            file.absolutePath
        }

    /*
     * 긴 변이 2000px보다 크면 축소
     */
    private fun scaleDown(
        bitmap: Bitmap,
        maxSide: Int
    ): Bitmap {

        val longest =
            maxOf(
                bitmap.width,
                bitmap.height
            )

        if (
            longest <= maxSide
        ) {
            return bitmap
        }

        val ratio =
            maxSide.toFloat() /
                    longest.toFloat()

        val width =
            (bitmap.width * ratio)
                .toInt()

        val height =
            (bitmap.height * ratio)
                .toInt()

        return Bitmap.createScaledBitmap(
            bitmap,
            width,
            height,
            true
        )
    }

    /*
     * OCR 리소스 종료
     */
    fun close() {
        ocr.close()
    }
}