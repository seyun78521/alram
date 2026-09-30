package com.example.androidlab.ocr

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs

class QuestionOcr {

    private val recognizer =
        TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())

    // 이미지(Bitmap)를 넣으면 인식된 글자 결과(Text)를 돌려준다
    suspend fun recognize(bitmap: Bitmap): Text = suspendCancellableCoroutine { cont ->
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { cont.resume(it) }
            .addOnFailureListener { cont.resumeWithException(it) }
    }

    // 글자 위치를 이용해 "사람이 읽는 순서"로 정렬한 줄 목록을 돌려준다
    suspend fun recognizeOrdered(bitmap: Bitmap): List<String> {
        val result = recognize(bitmap)
        return ReadingOrder.sort(result.textBlocks.flatMap { it.lines }, bitmap.width)
    }

    fun close() {
        recognizer.close()
    }
}

// OCR이 준 줄들을 "왼쪽 단 위→아래, 그다음 오른쪽 단 위→아래" 순서로 다시 세운다
object ReadingOrder {

    private class Item(val text: String, val left: Int, val centerY: Int, val height: Int)

    fun sort(lines: List<Text.Line>, imageWidth: Int): List<String> {
        val all = lines.mapNotNull { line ->
            val box = line.boundingBox ?: return@mapNotNull null
            Item(line.text, box.left, box.centerY(), box.height())
        }

        // 이미지 오른쪽 끝 15%는 옆 페이지가 찍힌 조각이라 버린다
        val items = all.filter { it.left < imageWidth * 0.85 }
        if (items.isEmpty()) return emptyList()

        val medianHeight = items.map { it.height }.sorted()[items.size / 2]

        // 1) 줄의 왼쪽 시작 위치가 크게 벌어지는 곳을 기준으로 단(열)을 나눈다
        val byLeft = items.sortedBy { it.left }
        val columns = mutableListOf<MutableList<Item>>()
        var current = mutableListOf(byLeft.first())
        for (i in 1 until byLeft.size) {
            if (byLeft[i].left - byLeft[i - 1].left > imageWidth * 0.15) {
                columns.add(current)
                current = mutableListOf()
            }
            current.add(byLeft[i])
        }
        columns.add(current)

        // 2) 단마다 위→아래로 정렬 (왼쪽 단이 먼저)
        return columns.flatMap { sortColumn(it, medianHeight) }.map { it.text }
    }

    // 한 단 안에서 위→아래로 정렬하고, 같은 줄에 걸린 조각은 왼→오 순서로 놓는다
    private fun sortColumn(col: List<Item>, medianHeight: Int): List<Item> {
        val rows = mutableListOf<MutableList<Item>>()
        for (item in col.sortedBy { it.centerY }) {
            val row = rows.lastOrNull()
            if (row != null && abs(item.centerY - row.first().centerY) < medianHeight * 0.6) {
                row.add(item)
            } else {
                rows.add(mutableListOf(item))
            }
        }
        return rows.flatMap { row -> row.sortedBy { it.left } }
    }
}