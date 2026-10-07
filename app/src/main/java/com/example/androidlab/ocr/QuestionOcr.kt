package com.example.androidlab.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// OCR이 읽은 줄 하나: 글자(text) + 그 줄이 사진에서 차지하는 사각형 위치(box)
// data class = 값을 담는 상자 전용 클래스. Java로 치면 필드, getter, equals, toString을
//              컴파일러가 자동으로 만들어 주는 클래스다. (val = 한 번 정하면 못 바꾸는 값, Java의 final)
// Rect는 left, top, right, bottom 네 숫자를 가진 안드로이드 기본 클래스다.
data class OcrLine(
    val text: String,
    val box: Rect
)

// 사진 → 글자를 읽는 클래스 (영어/Latin OCR)
class QuestionOcr {

    // ML Kit 글자 인식기. 한 번만 만들어 두고 계속 재사용한다.
    private val recognizer =
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    // 사진(Bitmap)을 넣으면 읽는 순서대로 정렬된 줄 목록을 돌려준다.
    // suspend = 시간이 걸리는 함수라서 코루틴(백그라운드 작업) 안에서만 부를 수 있다.
    // suspendCancellableCoroutine = ML Kit의 "다 끝나면 알려주기(콜백)" 방식을
    //                               "기다렸다가 결과를 받는" 방식으로 바꿔 준다.
    // { continuation -> ... } 는 "이름 없는 함수(람다)"다. Java의 (continuation) -> { ... } 와 같다.
    // continuation.resume(값) = "기다리던 쪽에 결과를 전달하고 다시 깨운다"
    suspend fun recognizeLines(bitmap: Bitmap): List<OcrLine> =
        suspendCancellableCoroutine { continuation ->

            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { result ->
                    // 글자 덩어리(block) 안의 줄(line)들을 전부 꺼내서 OcrLine으로 바꾼다.
                    val lines = result.textBlocks
                        .flatMap { it.lines }                 // 블록들 속의 줄들을 한 목록으로 펼치기
                        .mapNotNull { line ->                 // 하나씩 바꾸되, 결과가 null인 것은 버린다
                            val box = line.boundingBox         // 위치가 없으면 null
                            if (box != null && line.text.isNotBlank()) {
                                OcrLine(line.text.trim(), box)
                            } else null                        // 위치나 글자가 없는 줄은 버린다
                        }

                    // 2단 페이지를 왼쪽 단 → 오른쪽 단 순서로 정렬해서 돌려준다.
                    continuation.resume(ReadingOrder.sort(lines, bitmap.width))
                }
                .addOnFailureListener { error ->
                    continuation.resumeWithException(error)   // 실패하면 예외로 알린다
                }
        }

    // 다 쓰고 나면 인식기를 닫는다 (메모리 정리)
    fun close() {
        recognizer.close()
    }
}

// 책처럼 2단으로 된 페이지를 읽는 순서로 정렬한다: 왼쪽 단 위→아래, 그다음 오른쪽 단 위→아래
// object = 객체가 딱 하나만 있는 클래스. Java의 static 메서드 모음과 비슷하다.
object ReadingOrder {

    fun sort(lines: List<OcrLine>, imageWidth: Int): List<OcrLine> {
        val centerX = imageWidth / 2 // 사진 가운데 x 좌표

        // partition = 목록을 조건에 맞는 것 / 안 맞는 것 두 목록으로 나눈다.
        // val (left, right) = ... 는 두 결과를 한 번에 받는 문법이다.
        // 줄의 가운데가 사진 가운데보다 왼쪽이면 왼쪽 단, 아니면 오른쪽 단
        val (left, right) = lines.partition { it.box.centerX() < centerX }

        // 각 단 안에서 위에서 아래로 정렬 (높이가 같으면 왼쪽부터)
        val order = compareBy<OcrLine>({ it.box.top }, { it.box.left })
        return left.sortedWith(order) + right.sortedWith(order)
    }
}