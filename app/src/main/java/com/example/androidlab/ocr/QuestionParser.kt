package com.example.androidlab.ocr

import android.graphics.Bitmap
import android.graphics.Color
import com.example.androidlab.data.Question

/**
 * TOEIC Part 2 형식의 문제를 분석한다.
 *
 * 현재 지원:
 * - 한 페이지에 여러 문제
 * - 문제 번호 인식
 * - A/B/C 선택지 인식
 * - 빨간색으로 표시된 정답 인식
 */
class QuestionParser {

    /**
     * 문제 번호는 1~99까지만 사용한다.
     *
     * 예:
     * 19
     * 20
     * 21
     */
    private val questionNumberRegex =
        Regex("""^\s*(\d{1,2})\.?\s*$""")

    /**
     * 선택지 형식
     *
     * (A) 내용
     * (B) 내용
     * (C) 내용
     *
     * 또는
     *
     * A) 내용
     * B) 내용
     */
    private val optionRegex =
        Regex(
            """^\s*(?:\(([ABC])\)|([ABC])[\.\)])\s*(.+)\s*$"""
        )

    /**
     * 전체 페이지를 분석해서
     * 여러 개의 Question을 만든다.
     *
     * @param bitmap 원본 이미지
     * @param ocrLines OCR 결과
     * @param imagePath 원본 이미지 경로
     */
    fun parsePage(
        bitmap: Bitmap,
        ocrLines: List<QuestionOcr.OcrLine>,
        imagePath: String
    ): List<Question> {

        // 페이지를 왼쪽/오른쪽 영역으로 나눈다.
        // 네가 보여준 이미지처럼 2열짜리 문제집을 대상으로 한다.
        val leftLines = mutableListOf<QuestionOcr.OcrLine>()
        val rightLines = mutableListOf<QuestionOcr.OcrLine>()

        val centerX = bitmap.width / 2

        for (line in ocrLines) {

            val lineCenter =
                (line.left + line.right) / 2

            if (lineCenter < centerX) {
                leftLines.add(line)
            } else {
                rightLines.add(line)
            }
        }

        // 각 열에서 위 → 아래 순서로 정렬
        leftLines.sortBy { it.top }
        rightLines.sortBy { it.top }

        val questions = mutableListOf<Question>()

        // 왼쪽 열 처리
        questions.addAll(
            parseColumn(
                bitmap = bitmap,
                lines = leftLines,
                imagePath = imagePath
            )
        )

        // 오른쪽 열 처리
        questions.addAll(
            parseColumn(
                bitmap = bitmap,
                lines = rightLines,
                imagePath = imagePath
            )
        )

        return questions
    }

    /**
     * 한 열에 있는 문제들을 처리한다.
     */
    private fun parseColumn(
        bitmap: Bitmap,
        lines: List<QuestionOcr.OcrLine>,
        imagePath: String
    ): List<Question> {

        val result = mutableListOf<Question>()

        var currentQuestionNumber: Int? = null
        val currentLines = mutableListOf<QuestionOcr.OcrLine>()

        for (line in lines) {

            val questionNumber = getQuestionNumber(line.text)

            // 새로운 문제 번호를 발견한 경우
            if (questionNumber != null) {

                // 이전 문제가 존재하면 먼저 처리
                if (currentQuestionNumber != null) {

                    parseBlock(
                        bitmap = bitmap,
                        questionNumber = currentQuestionNumber!!,
                        lines = currentLines,
                        imagePath = imagePath
                    )?.let {
                        result.add(it)
                    }
                }

                // 새로운 문제 시작
                currentQuestionNumber = questionNumber
                currentLines.clear()

            } else {

                // 현재 문제에 속하는 OCR 줄 저장
                if (currentQuestionNumber != null) {
                    currentLines.add(line)
                }
            }
        }

        // 마지막 문제 처리
        if (currentQuestionNumber != null) {

            parseBlock(
                bitmap = bitmap,
                questionNumber = currentQuestionNumber!!,
                lines = currentLines,
                imagePath = imagePath
            )?.let {
                result.add(it)
            }
        }

        return result
    }

    /**
     * 문제 하나를 분석한다.
     */
    private fun parseBlock(
        bitmap: Bitmap,
        questionNumber: Int,
        lines: List<QuestionOcr.OcrLine>,
        imagePath: String
    ): Question? {

        val options = mutableMapOf<String, ParsedOption>()
        val questionLines = mutableListOf<String>()

        var optionStarted = false

        // 위 → 아래 순서
        val sortedLines = lines.sortedBy { it.top }

        for (line in sortedLines) {

            val optionMatch =
                optionRegex.find(line.text)

            if (optionMatch != null) {

                val answer =
                    optionMatch.groupValues[1]
                        .ifBlank {
                            optionMatch.groupValues[2]
                        }

                val text =
                    optionMatch.groupValues[3].trim()

                options[answer] =
                    ParsedOption(
                        answer = answer,
                        text = text,
                        line = line
                    )

                optionStarted = true

            } else {

                // A가 나오기 전까지만
                // 문제 내용으로 사용한다.
                if (!optionStarted &&
                    !isSpeakerTag(line.text)
                ) {

                    questionLines.add(line.text)
                }
            }
        }

        // A/B/C가 모두 있어야 문제로 인정
        val optionA = options["A"]
        val optionB = options["B"]
        val optionC = options["C"]

        if (optionA == null ||
            optionB == null ||
            optionC == null
        ) {
            return null
        }

        // 문제 문장 합치기
        val questionText =
            questionLines.joinToString(" ").trim()

        if (questionText.isBlank()) {
            return null
        }

        // -----------------------------------------
        // 빨간색 정답 찾기
        // -----------------------------------------

        val redScores = listOf(
            optionA to redScore(bitmap, optionA.line),
            optionB to redScore(bitmap, optionB.line),
            optionC to redScore(bitmap, optionC.line)
        )

        val best =
            redScores.maxByOrNull { it.second }
                ?: return null

        // 빨간색 비율이 너무 낮으면
        // 정답을 확실히 찾지 못한 것으로 처리한다.
        if (best.second < 0.01) {
            return null
        }

        val correctAnswer = best.first.answer

        // Question 객체 생성
        return Question(
            id = 0,

            // OCR 전체 내용을 간단하게 저장
            rawText =
                buildString {
                    append(questionText)
                    append("\n(A) ")
                    append(optionA.text)
                    append("\n(B) ")
                    append(optionB.text)
                    append("\n(C) ")
                    append(optionC.text)
                },

            questionText = questionText,

            choiceA = optionA.text,
            choiceB = optionB.text,
            choiceC = optionC.text,

            correctAnswer = correctAnswer,

            // 현재 Latin OCR만 사용하므로
            // 한국어 해설은 일단 비워둔다.
            explanation = "",

            category = "Part 2",

            imagePath = imagePath,

            createdAt = System.currentTimeMillis()
        )
    }

    /**
     * OCR 결과에서 문제 번호를 찾는다.
     */
    private fun getQuestionNumber(text: String): Int? {

        val match =
            questionNumberRegex.matchEntire(text)
                ?: return null

        val number =
            match.groupValues[1].toIntOrNull()
                ?: return null

        // 페이지 번호 같은 숫자는 제외
        if (number !in 1..99) {
            return null
        }

        return number
    }

    /**
     * M-Au / W-Br 같은 화자 표시인지 확인한다.
     *
     * 문제 내용으로 저장하지 않기 위해 제거한다.
     */
    private fun isSpeakerTag(text: String): Boolean {

        return Regex(
            """^[MW]-[A-Za-z]{1,4}$"""
        ).matches(text.trim())
    }

    /**
     * 특정 OCR 줄에서
     * 빨간색 픽셀이 얼마나 많은지 계산한다.
     *
     * 반환값:
     * 0.0 = 빨간색 거의 없음
     * 0.1 = 전체 픽셀의 약 10%가 빨간색
     */
    private fun redScore(
        bitmap: Bitmap,
        line: QuestionOcr.OcrLine
    ): Double {

        // 텍스트 주변을 약간 넓게 검사
        val left =
            maxOf(0, line.left - 4)

        val top =
            maxOf(0, line.top - 4)

        val right =
            minOf(bitmap.width, line.right + 4)

        val bottom =
            minOf(bitmap.height, line.bottom + 4)

        if (left >= right || top >= bottom) {
            return 0.0
        }

        var totalPixels = 0
        var redPixels = 0

        // 모든 픽셀을 검사하면 느릴 수 있기 때문에
        // 2픽셀 간격으로 검사한다.
        val step = 2

        var y = top

        while (y < bottom) {

            var x = left

            while (x < right) {

                val pixel =
                    bitmap.getPixel(x, y)

                val red =
                    Color.red(pixel)

                val green =
                    Color.green(pixel)

                val blue =
                    Color.blue(pixel)

                /*
                 * 빨간색인지 확인
                 *
                 * 단순히 R이 크다고 보는 것이 아니라
                 * G/B보다 충분히 큰지를 같이 확인한다.
                 */
                val isRed =
                    red > 120 &&
                            red > green + 30 &&
                            red > blue + 30

                if (isRed) {
                    redPixels++
                }

                totalPixels++

                x += step
            }

            y += step
        }

        if (totalPixels == 0) {
            return 0.0
        }

        return redPixels.toDouble() /
                totalPixels.toDouble()
    }

    /**
     * 하나의 선택지 정보
     */
    private data class ParsedOption(
        val answer: String,
        val text: String,
        val line: QuestionOcr.OcrLine
    )
}