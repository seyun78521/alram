package com.example.androidlab.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/*
 * OCR 결과의 한 줄
 *
 * text:
 *   실제 인식된 글자
 *
 * box:
 *   이미지에서 그 글자가 위치한 영역
 */
data class OcrLine(
    val text: String,
    val box: Rect
)

/*
 * 영어 OCR 담당
 */
class QuestionOcr {

    // 영어/Latin 문자용 OCR
    private val recognizer: TextRecognizer =
        TextRecognition.getClient(
            TextRecognizerOptions.DEFAULT_OPTIONS
        )

    /*
     * Bitmap -> OCR 결과
     */
    suspend fun recognizeLines(
        bitmap: Bitmap
    ): List<OcrLine> =
        suspendCancellableCoroutine { continuation ->

            val image =
                InputImage.fromBitmap(
                    bitmap,
                    0
                )

            recognizer
                .process(image)
                .addOnSuccessListener { result ->

                    /*
                     * OCR 결과에서 모든 줄을 가져온다.
                     *
                     * 단순히 text만 가져오는 게 아니라
                     * 위치(box)도 같이 보관한다.
                     */
                    val lines =
                        result.textBlocks
                            .flatMap { block ->
                                block.lines
                            }
                            .mapNotNull { line ->

                                val box =
                                    line.boundingBox

                                if (
                                    box != null &&
                                    line.text.isNotBlank()
                                ) {

                                    OcrLine(
                                        text =
                                            line.text.trim(),
                                        box = box
                                    )

                                } else {
                                    null
                                }
                            }

                    /*
                     * 2열 페이지를 읽을 수 있게
                     * 위치를 이용해 정렬한다.
                     */
                    val sorted =
                        ReadingOrder.sort(
                            lines,
                            bitmap.width
                        )

                    continuation.resume(
                        sorted
                    )
                }
                .addOnFailureListener { error ->
                    continuation.resumeWithException(
                        error
                    )
                }
        }

    fun close() {
        recognizer.close()
    }
}

/*
 * 책처럼 2열로 되어 있는 페이지를
 *
 * 왼쪽 위 -> 아래
 * 오른쪽 위 -> 아래
 *
 * 순서로 정리한다.
 *
 * 이 기능은 네가 처음 사용했던 코드에서
 * 여러 문제를 읽을 때 사용하던 핵심 아이디어만
 * 간단하게 가져온 것이다.
 */
object ReadingOrder {

    fun sort(
        lines: List<OcrLine>,
        imageWidth: Int
    ): List<OcrLine> {

        if (lines.isEmpty()) {
            return emptyList()
        }

        /*
         * 페이지 가운데를 기준으로
         * 왼쪽/오른쪽 두 열로 나눈다.
         */
        val centerX =
            imageWidth / 2

        val left =
            lines
                .filter {
                    it.box.centerX() < centerX
                }
                .sortedWith(
                    compareBy<OcrLine> {
                        it.box.top
                    }.thenBy {
                        it.box.left
                    }
                )

        val right =
            lines
                .filter {
                    it.box.centerX() >= centerX
                }
                .sortedWith(
                    compareBy<OcrLine> {
                        it.box.top
                    }.thenBy {
                        it.box.left
                    }
                )

        return left + right
    }
}