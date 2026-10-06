package com.example.androidlab.ocr

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * OCR 결과에서 한 줄의 텍스트와
 * 그 텍스트가 이미지에서 어디에 있는지를 같이 저장한다.
 *
 * 빨간색 정답을 찾으려면
 * "텍스트 내용"뿐 아니라 "이미지에서의 위치"가 필요하다.
 */
class QuestionOcr {

    // 영어(Latin) OCR 사용
    private val recognizer: TextRecognizer =
        TextRecognition.getClient(
            TextRecognizerOptions.DEFAULT_OPTIONS
        )

    /**
     * 이미지 한 장을 OCR해서
     * 줄 단위의 텍스트 + 위치 정보를 반환한다.
     */
    suspend fun recognize(bitmap: Bitmap): List<OcrLine> =
        suspendCoroutine { continuation ->

            val image = InputImage.fromBitmap(bitmap, 0)

            recognizer.process(image)
                .addOnSuccessListener { result ->

                    val lines = result.textBlocks
                        .flatMap { block -> block.lines }
                        .mapNotNull { line ->

                            val box = line.boundingBox

                            if (box != null && line.text.isNotBlank()) {
                                OcrLine(
                                    text = line.text.trim(),
                                    left = box.left,
                                    top = box.top,
                                    right = box.right,
                                    bottom = box.bottom
                                )
                            } else {
                                null
                            }
                        }

                    continuation.resume(lines)
                }
                .addOnFailureListener { error ->
                    continuation.resumeWithException(error)
                }
        }

    /**
     * OCR 종료
     */
    fun close() {
        recognizer.close()
    }

    /**
     * OCR 한 줄의 정보
     */
    data class OcrLine(
        val text: String,

        // 이미지에서의 위치
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    )
}