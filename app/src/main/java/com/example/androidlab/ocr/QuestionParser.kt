package com.example.androidlab.ocr

import com.example.androidlab.data.Question

/*
 * OCR 결과를 여러 Question으로 변환한 결과
 */
data class ParseResult(
    val questions: List<Question>,
    val skipped: List<String>
)

/*
 * TOEIC Part 2용 간단한 파서
 *
 * 처리 흐름:
 *
 * 전체 페이지 OCR
 *     ↓
 * 문제 번호로 분리
 *     ↓
 * 문제 문장 추출
 *     ↓
 * A / B / C 추출
 *     ↓
 * 선택지가 다음 줄로 끊긴 경우 연결
 *     ↓
 * Question 생성
 *
 * 현재 minimum에서는:
 * - 정답 자동 인식 X
 * - 해설 인식 X
 * - 복잡한 OCR 오타 보정 X
 */
object QuestionParser {

    /*
     * 문제 번호
     *
     * 19
     * 20
     * 21.
     */
    private val numberRegex =
        Regex("""^\s*(\d{1,2})\s*[.)]?\s*$""")

    /*
     * 화자 표시
     *
     * M-Cn
     * W-Br
     * M-Au
     */
    private val speakerRegex =
        Regex("""^[MW]-[A-Za-z]{2,3}\s*""")

    /*
     * 선택지
     *
     * (A) 내용
     * A) 내용
     * A. 내용
     *
     * M-Au (A) 내용
     */
    private val choiceRegex =
        Regex(
            """^\s*(?:[MW]-[A-Za-z]{2,3}\s+)?\(?([ABC])\)?[.):]?\s+(.+)\s*$""",
            RegexOption.IGNORE_CASE
        )

    /*
     * Latin OCR에서 한글이 정상적으로 인식된 경우
     * 선택지 이어붙이기 대상에서 제외한다.
     */
    private val koreanRegex =
        Regex("[가-힣]")

    /*
     * 문제 번호인지 확인
     */
    private fun isQuestionNumber(text: String): Boolean =
        numberRegex.matches(text.trim())

    /*
     * 선택지를 파싱한다.
     *
     * 반환:
     * "A" to "선택지 내용"
     */
    private fun parseChoice(text: String): Pair<String, String>? {

        val match =
            choiceRegex.matchEntire(text.trim())
                ?: return null

        return match.groupValues[1].uppercase() to
                match.groupValues[2].trim()
    }

    /*
     * 전체 페이지를 여러 문제로 나눈다.
     */
    fun parse(
        lines: List<OcrLine>,
        category: String = "Part 2"
    ): ParseResult {

        if (lines.isEmpty()) {
            return ParseResult(
                emptyList(),
                listOf("OCR 결과가 없습니다.")
            )
        }

        /*
         * OCR 결과에서 문제 번호 위치를 찾는다.
         */
        val starts =
            lines.indices.filter {
                isQuestionNumber(lines[it].text)
            }

        if (starts.isEmpty()) {
            return ParseResult(
                emptyList(),
                listOf("문제 번호를 찾지 못했습니다.")
            )
        }

        val questions = mutableListOf<Question>()
        val skipped = mutableListOf<String>()

        /*
         * 문제 번호부터 다음 문제 번호 직전까지를
         * 하나의 문제 블록으로 처리한다.
         */
        starts.forEachIndexed { index, start ->

            val end =
                starts.getOrNull(index + 1)
                    ?: lines.size

            val question =
                parseBlock(
                    lines.subList(start + 1, end),
                    category
                )

            if (question != null) {
                questions.add(question)
            } else {
                skipped.add(
                    "${lines[start].text}번 문제를 인식하지 못했습니다."
                )
            }
        }

        return ParseResult(
            questions = questions,
            skipped = skipped
        )
    }

    /*
     * 문제 하나를 분석한다.
     */
    private fun parseBlock(
        lines: List<OcrLine>,
        category: String
    ): Question? {

        if (lines.isEmpty()) return null

        /*
         * 선택지와 시작 위치를 저장한다.
         *
         * 예:
         * A -> "The bookstore is closed."
         * B -> "Yes, that's a good idea."
         * C -> "A job as a graphic designer."
         */
        val choices = mutableMapOf<String, String>()
        val choiceIndices = mutableMapOf<String, Int>()

        lines.forEachIndexed { index, line ->

            val choice =
                parseChoice(line.text)
                    ?: return@forEachIndexed

            /*
             * 같은 A/B/C가 다시 발견되면
             * 첫 번째 것만 사용한다.
             */
            if (choice.first !in choices) {

                choices[choice.first] =
                    cleanEnglish(choice.second)

                choiceIndices[choice.first] =
                    index
            }
        }

        /*
         * Part 2는 A/B/C가 모두 있어야 한다.
         */
        if (
            "A" !in choices ||
            "B" !in choices ||
            "C" !in choices
        ) {
            return null
        }

        /*
         * 첫 번째 선택지 이전의 내용이
         * 문제 문장이다.
         */
        val firstChoiceIndex =
            choiceIndices.values.minOrNull()
                ?: return null

        val questionText =
            lines
                .subList(0, firstChoiceIndex)
                .map { removeSpeaker(it.text) }
                .filter { it.isNotBlank() }
                .joinToString(" ")
                .let(::cleanEnglish)

        if (questionText.isBlank()) return null

        /*
         * -----------------------------------------
         * 선택지가 다음 줄로 넘어가는 경우 처리
         * -----------------------------------------
         *
         * 예:
         *
         * (C) There should be a label stitched on the
         * edge.
         *
         * 위에서 첫 줄만 OCR되는 경우가 있기 때문에
         * 바로 다음 줄이 같은 선택지의 이어지는 줄인지
         * 간단하게 검사한다.
         *
         * 중요한 점:
         *
         * - 최대 2줄까지만
         * - 다음 선택지면 중단
         * - 문제 번호면 중단
         * - 화자면 중단
         * - 한글이면 중단
         * - 세로 간격이 멀면 중단
         * - 시작 위치가 너무 다르면 중단
         */
        choiceIndices.forEach { (letter, index) ->

            choices[letter] =
                appendContinuation(
                    lines = lines,
                    choiceIndex = index,
                    initialText = choices[letter].orEmpty()
                )
        }

        /*
         * 현재 minimum에서는
         *
         * 정답 = ""
         * 해설 = ""
         *
         * 로 저장한다.
         *
         * 정답은 나중에 수동 입력할 수 있다.
         */
        return Question(
            rawText = buildString {
                append(questionText)
                append("\n(A) ${choices["A"].orEmpty()}")
                append("\n(B) ${choices["B"].orEmpty()}")
                append("\n(C) ${choices["C"].orEmpty()}")
            },

            questionText = questionText,

            choiceA = choices["A"].orEmpty(),
            choiceB = choices["B"].orEmpty(),
            choiceC = choices["C"].orEmpty(),

            correctAnswer = "",
            explanation = "",

            category = category,
            imagePath = ""
        )
    }

    /*
     * 선택지의 다음 줄을 필요한 경우에만 붙인다.
     *
     * 핵심은 "다음 줄이면 무조건 붙인다"가 아니다.
     *
     * 현재 선택지:
     *   There should be a label stitched on the
     *
     * 다음 OCR:
     *   edge.
     *
     * 라면 붙인다.
     *
     * 반대로 아래쪽 해설이나 다른 영역은
     * 조건에 걸리면 붙이지 않는다.
     */
    private fun appendContinuation(
        lines: List<OcrLine>,
        choiceIndex: Int,
        initialText: String
    ): String {

        var result = initialText
        var currentIndex = choiceIndex
        var added = 0

        /*
         * 너무 많은 줄을 붙이면
         * 해설까지 먹을 수 있으므로 최대 2줄.
         */
        while (added < 2) {

            val nextIndex = currentIndex + 1

            if (nextIndex >= lines.size) break

            val current = lines[currentIndex]
            val next = lines[nextIndex]

            /*
             * 다음 선택지면 종료
             */
            if (parseChoice(next.text) != null) break

            /*
             * 다음 문제 번호면 종료
             */
            if (isQuestionNumber(next.text)) break

            /*
             * 화자 표시가 나오면 종료
             */
            if (
                speakerRegex.containsMatchIn(
                    next.text.trim()
                )
            ) {
                break
            }

            /*
             * 한글이 정상적으로 인식된 경우
             * 번역/해설일 가능성이 높으므로 종료
             */
            if (
                koreanRegex.containsMatchIn(
                    next.text
                )
            ) {
                break
            }

            val text = next.text.trim()

            /*
             * 너무 짧거나 글자가 전혀 없으면
             * OCR 잡음으로 본다.
             */
            if (
                text.length < 2 ||
                !text.any { it.isLetter() }
            ) {
                break
            }

            /*
             * 현재 선택지가 이미 문장으로 끝났다면
             * 다음 줄을 붙이지 않는다.
             *
             * 예:
             * Please wait outside.
             */
            if (endsSentence(result)) break

            val currentBox = current.box
            val nextBox = next.box

            /*
             * 세로 간격 확인
             *
             * 바로 이어지는 줄만 허용한다.
             */
            val verticalGap =
                nextBox.top - currentBox.bottom

            val maxGap =
                maxOf(
                    currentBox.height(),
                    40
                )

            if (
                verticalGap < -20 ||
                verticalGap > maxGap
            ) {
                break
            }

            /*
             * 왼쪽 시작 위치 확인
             *
             * 줄바꿈된 문장은 보통
             * 비슷한 x좌표에서 시작한다.
             */
            val horizontalGap =
                kotlin.math.abs(
                    nextBox.left - currentBox.left
                )

            if (
                horizontalGap > currentBox.width()
            ) {
                break
            }

            /*
             * 모든 조건을 통과했다면
             * 같은 선택지의 다음 줄로 판단한다.
             */
            result =
                "$result ${cleanEnglish(text)}"

            currentIndex = nextIndex
            added++
        }

        return result.trim()
    }

    /*
     * 문장이 끝나는지 확인한다.
     *
     * 마침표, 물음표, 느낌표가 있으면
     * 이미 끝난 문장으로 본다.
     */
    private fun endsSentence(text: String): Boolean =
        Regex("""[.!?]["')\]]*$""")
            .containsMatchIn(text.trim())

    /*
     * M-Au / W-Br 같은 화자 표시 제거
     */
    private fun removeSpeaker(text: String): String =
        text.replaceFirst(
            speakerRegex,
            ""
        ).trim()

    /*
     * OCR 결과를 아주 조금만 정리한다.
     *
     * 복잡한 오타 보정은 하지 않는다.
     */
    private fun cleanEnglish(text: String): String =
        text
            .replace("|", "I")
            .replace('’', '\'')
            .replace(Regex("\\s+"), " ")
            .trim()
}