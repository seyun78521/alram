package com.example.androidlab.ocr

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Point
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.hypot

// OCR이 읽은 줄 하나: 글자 + 그 줄의 네 모서리 위치 (기울어진 줄도 정확히 표현한다)
class OcrLine(val text: String, val corners: List<Point>)

class QuestionOcr {

    private val recognizer =
        TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())

    // 이미지(Bitmap)를 넣으면 인식된 글자 결과(Text)를 돌려준다
    suspend fun recognize(bitmap: Bitmap): Text = suspendCancellableCoroutine { cont ->
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { cont.resume(it) }
            .addOnFailureListener { cont.resumeWithException(it) }
    }

    // 글자와 위치를 "사람이 읽는 순서"로 정렬해서 돌려준다
    suspend fun recognizeLines(bitmap: Bitmap): List<OcrLine> {
        val result = recognize(bitmap)
        return ReadingOrder.sort(result.textBlocks.flatMap { it.lines }, bitmap.width)
    }

    fun close() {
        recognizer.close()
    }
}

// OCR이 준 줄들을 "왼쪽 단 위→아래, 그다음 오른쪽 단 위→아래" 순서로 다시 세운다
object ReadingOrder {

    private class Item(val line: OcrLine, val leftX: Int, val leftY: Int, val height: Int)

    fun sort(lines: List<Text.Line>, imageWidth: Int): List<OcrLine> {
        val all = lines.mapNotNull { toItem(it) }

        // 이미지 오른쪽 끝 15%는 옆 페이지가 찍힌 조각이라 버린다
        val items = all.filter { it.leftX < imageWidth * 0.85 }
        if (items.isEmpty()) return emptyList()

        val medianHeight = items.map { it.height }.sorted()[items.size / 2]

        // 1) 줄의 왼쪽 시작 위치가 크게 벌어지는 곳을 기준으로 단(열)을 나눈다
        val byLeft = items.sortedBy { it.leftX }
        val columns = mutableListOf<MutableList<Item>>()
        var current = mutableListOf(byLeft.first())
        for (i in 1 until byLeft.size) {
            if (byLeft[i].leftX - byLeft[i - 1].leftX > imageWidth * 0.15) {
                columns.add(current)
                current = mutableListOf()
            }
            current.add(byLeft[i])
        }
        columns.add(current)

        // 2) 단마다 위→아래로 정렬 (왼쪽 단이 먼저)
        return columns.flatMap { sortColumn(it, medianHeight) }.map { it.line }
    }

    // 줄의 "왼쪽 끝" 위치와 실제 글자 높이를 구한다. 사진이 기울어도 줄 사이 비교가 흔들리지 않는다.
    private fun toItem(line: Text.Line): Item? {
        val pts = line.cornerPoints
        if (pts != null && pts.size == 4) {
            val leftX = (pts[0].x + pts[3].x) / 2
            val leftY = (pts[0].y + pts[3].y) / 2
            val h = hypot(
                (pts[3].x - pts[0].x).toDouble(),
                (pts[3].y - pts[0].y).toDouble()
            ).toInt()
            return Item(OcrLine(line.text, pts.toList()), leftX, leftY, maxOf(h, 1))
        }
        val box = line.boundingBox ?: return null
        val corners = listOf(
            Point(box.left, box.top), Point(box.right, box.top),
            Point(box.right, box.bottom), Point(box.left, box.bottom)
        )
        return Item(OcrLine(line.text, corners), box.left, box.centerY(), maxOf(box.height(), 1))
    }

    // 한 단 안에서 위→아래로 정렬하고, 같은 줄에 걸린 조각은 왼→오 순서로 놓는다
    private fun sortColumn(col: List<Item>, medianHeight: Int): List<Item> {
        val rows = mutableListOf<MutableList<Item>>()
        for (item in col.sortedBy { it.leftY }) {
            val row = rows.lastOrNull()
            if (row != null && abs(item.leftY - row.first().leftY) < medianHeight * 0.6) {
                row.add(item)
            } else {
                rows.add(mutableListOf(item))
            }
        }
        return rows.flatMap { row -> row.sortedBy { it.leftX } }
    }
}

// 글자 색 판정: 한 줄 안의 글자 중 "빨간 글자"가 차지하는 비율
object InkColor {

    fun redRatio(bitmap: Bitmap, c: List<Point>): Float {
        if (c.size != 4) return 0f
        val p0 = c[0]
        val p1 = c[1]
        val p3 = c[3]
        var ink = 0
        var red = 0
        val nu = 200 // 줄 방향으로 200곳
        val nv = 8   // 줄 높이 방향으로 8곳
        for (iu in 0 until nu) {
            val u = (iu + 0.5f) / nu
            for (iv in 0 until nv) {
                val v = (iv + 0.5f) / nv
                val x = (p0.x + u * (p1.x - p0.x) + v * (p3.x - p0.x)).toInt()
                val y = (p0.y + u * (p1.y - p0.y) + v * (p3.y - p0.y)).toInt()
                if (x < 0 || y < 0 || x >= bitmap.width || y >= bitmap.height) continue

                val px = bitmap.getPixel(x, y)
                val r = Color.red(px)
                val g = Color.green(px)
                val b = Color.blue(px)
                val luminance = 0.299f * r + 0.587f * g + 0.114f * b
                if (luminance < 170f) {            // 어두운 픽셀 = 글자
                    ink++
                    if (r > 90 && r - g > 25 && r - b > 25) red++ // 붉은 기가 도는 글자
                }
            }
        }
        return if (ink > 30) red.toFloat() / ink else 0f
    }
}