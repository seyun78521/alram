package com.example.androidlab.ocr

import com.example.androidlab.data.Question

// 문제 번호와 문제를 한 쌍으로 묶은 상자. 번호는 해설을 붙일 때만 쓴다.
data class NumberedQuestion(
    val number: Int,
    val question: Question
)

// 변환 결과를 담는 상자.
// data class = 값을 담는 상자 전용 클래스. Java로 치면 getter, equals, toString을 자동으로 만들어 주는 클래스다.
// questions: 번호가 붙은 문제들 / skipped: 만들지 못한 문제와 이유
data class ParseResult(
    val questions: List<NumberedQuestion>,
    val skipped: List<String>
)

/*
 * TOEIC Part 2 간단한 파서
 *
 * 흐름: OCR이 읽은 줄들 → 문제 번호로 나누기 → 문제 문장 + A/B/C 찾기 → Question 만들기
 * 해설: 한글 OCR 줄들 → 문제 번호로 나누기 → "해설"로 시작하는 줄부터 블록 끝까지 → 번호별 해설
 *
 * object = 객체가 딱 하나만 있는 클래스. Java의 static 메서드 모음과 비슷하다.
 */
object QuestionParser {

    // ===== 정규식 (Regex) 읽는 법 =====
    //   ^ 줄의 맨 앞   $ 줄의 맨 끝   \d 숫자 한 글자   \s 공백 한 글자
    //   [ABC] A, B, C 중 한 글자   [A-Za-z] 영어 알파벳 한 글자
    //   * 0번 이상 반복   ? 있어도 되고 없어도 됨   {1,2} 1번~2번 반복

    // 문제 번호 줄: "19", "20.", "21)" 처럼 숫자만 있는 줄
    private val numberRegex = Regex("""^\s*\d{1,2}\s*[.)]?\s*$""")

    // 화자 표시: "M-Cn", "W-Br", "M-Au" 처럼 줄 맨 앞에 붙는 것 (문제 문장에서 지운다)
    private val speakerRegex = Regex("""^[MW]-[A-Za-z]{2,3}\s*""")

    // ===== 작은 도우미 함수들 =====

    // 이 줄이 문제 번호인가?
    private fun isQuestionNumber(text: String): Boolean = numberRegex.matches(text)

    // 번호 줄의 글자를 숫자로 바꾼다. 예: "20." → 20
    // trimEnd('.', ')') = 끝에 붙은 "." 또는 ")" 를 모두 떼어 낸다. toIntOrNull = 숫자가 아니면 null
    private fun numberOf(text: String): Int? =
        text.trim().trimEnd('.', ')').trim().toIntOrNull()

    // 번호 줄이 있는 위치(인덱스)를 모두 모은다. 예: [0, 7, 14]
    private fun findNumberLines(lines: List<OcrLine>): List<Int> {
        val starts = mutableListOf<Int>()
        for (i in lines.indices) {
            if (isQuestionNumber(lines[i].text)) {
                starts.add(i)
            }
        }
        return starts
    }

    // 글 맨 앞의 화자 표시와 그 뒤 공백을 지운 글을 돌려준다. 표시가 없으면 그대로 돌려준다.
    private fun removeSpeaker(text: String): String =
        text.trim().replaceFirst(speakerRegex, "").trim()

    // 선택지 판별만 정규식 대신 if
    private fun parseChoice(text: String): Pair<String, String>? {
        // 1) 맨 앞에 화자 표시가 있으면 떼어 낸다. 예: "M-Au (A) 내용" → "(A) 내용"
        var s = removeSpeaker(text)

        // 2) "(" 로 시작하면 그 한 글자를 건너뛴다. 예: "(A) 내용" → "A) 내용"
        if (s.startsWith("(")) {
            s = s.substring(1)
        }

        // 3) 최소 3글자(예: "A.x")가 있어야 한다
        if (s.length < 3) return null

        // 4) 첫 글자가 A, B, C 중 하나인가?
        val letter = s[0]
        if (letter != 'A' && letter != 'B' && letter != 'C') return null

        // 5) 둘째 글자가 ")" 또는 "." 인가?
        if (s[1] != ')' && s[1] != '.') return null

        // 6) 셋째 글자부터 끝까지가 선택지 내용이다
        val content = s.substring(2).trim()
        if (content.isEmpty()) return null

        return letter.toString() to content
    }

    // ===== OCR 글자 정리 =====
    //  - 세로선 "|" 는 대문자 I로 잘못 읽힌 것이므로 "I"로 바꾼다
    //  - 굽은 따옴표 ’ 는 보통 따옴표 ' 로 바꾼다
    //  - 공백이 여러 개 연속이면 하나로 줄인다
    private fun cleanEnglish(text: String): String {
        val replaced = text
            .replace("|", "I")
            .replace('’', '\'')

        // 공백, 탭, 줄바꿈을 기준으로 단어를 쪼갠다. 연속 공백 사이에는 빈 조각이 생긴다.
        val pieces = replaced.split(' ', '\t', '\n', '\r')

        // 빈 조각은 버리고, 남은 단어들을 공백 하나로 다시 이어 붙인다
        val words = mutableListOf<String>() // mutable = 나중에 add 할 수 있는 목록
        for (piece in pieces) {
            if (piece.isNotEmpty()) {
                words.add(piece)
            }
        }
        return words.joinToString(" ")
    }

    // ===== 1단계: 영어 OCR 줄들을 문제 단위로 나눈다 =====
    fun parse(lines: List<OcrLine>): ParseResult {
        val starts = findNumberLines(lines)

        if (starts.isEmpty()) {
            return ParseResult(emptyList(), listOf("문제 번호를 찾지 못했습니다."))
        }

        val questions = mutableListOf<NumberedQuestion>()
        val skipped = mutableListOf<String>()

        // 문제 번호 위치마다 반복한다. (index = 몇 번째 번호인지, start = 그 번호 줄의 위치)
        for ((index, start) in starts.withIndex()) {

            // 이 문제의 끝 = 다음 문제 번호 바로 앞. 마지막 문제면 페이지 끝까지.
            var end = lines.size
            if (index + 1 < starts.size) {
                end = starts[index + 1]
            }

            // 번호 줄 다음부터 end 전까지가 이 문제의 줄들이다. (subList = 부분 목록)
            val question = parseBlock(lines.subList(start + 1, end))
            val number = numberOf(lines[start].text)

            if (question != null && number != null) {
                questions.add(NumberedQuestion(number, question))
            } else {
                skipped.add("${lines[start].text}번 문제를 인식하지 못했습니다.")
            }
        }
        return ParseResult(questions, skipped)
    }

    // ===== 2단계: 문제 하나(줄 묶음)를 Question으로 만든다 =====
    // 만들 수 없으면 null을 돌려준다.
    private fun parseBlock(lines: List<OcrLine>): Question? {

        // 선택지 내용을 담는 지도(Map). 예: "A" → "The bookstore is closed."
        val choices = mutableMapOf<String, String>()
        var firstChoice = -1 // 첫 번째 선택지가 몇 번째 줄인지 (-1 = 아직 못 찾음)

        // 줄을 위에서 아래로 하나씩 보면서 선택지를 찾는다
        for (i in lines.indices) {
            val choice = parseChoice(lines[i].text)

            // 선택지가 아니면 다음 줄로 넘어간다
            if (choice == null) continue

            val letter = choice.first   // "A" / "B" / "C"
            val content = choice.second // 선택지 내용

            // 같은 알파벳이 또 나오면 첫 번째만 쓴다
            if (choices.containsKey(letter)) continue

            choices[letter] = cleanEnglish(content)

            if (firstChoice == -1) {
                firstChoice = i
            }
        }

        // Part 2는 A, B, C가 모두 있어야 한다. 하나라도 없으면 포기(null).
        if (!choices.containsKey("A") || !choices.containsKey("B") || !choices.containsKey("C")) {
            return null
        }

        // 문제 문장 = 첫 번째 선택지 위쪽 줄들을 이어 붙인 것
        val questionBuilder = StringBuilder()
        for (i in 0 until firstChoice) {
            val lineText = cleanEnglish(removeSpeaker(lines[i].text))
            if (lineText.isEmpty()) continue

            if (questionBuilder.isNotEmpty()) {
                questionBuilder.append(" ")
            }
            questionBuilder.append(lineText)
        }

        // OCR이 선택지 표시 "(A)", "(B" 를 질문 끝에 붙여 읽은 경우가 있어서 그것을 지운다.
        val questionText = cleanEnglish(
            questionBuilder.toString()
                .replace("(A", "")
                .replace("(B", "")
                .replace("(C", "")
                .replace(")", "")
        )

        if (questionText.isEmpty()) return null

        // 정답과 해설은 비워 둔다. 정답은 사람이 입력하고, 해설은 나중에 attachExplanations 가 채운다.
        return Question(
            questionText = questionText,
            choiceA = choices.getValue("A"),
            choiceB = choices.getValue("B"),
            choiceC = choices.getValue("C"),
            correctAnswer = "",
            explanation = ""
        )
    }

    // ===== 3단계: 한글 OCR 줄들에서 번호별 해설을 뽑는다 =====
    // 반환: 번호 → 해설 글 (예: 9 → "프레젠테이션 슬라이드를 ... 정답.")
    // 같은 번호가 한 사진에 두 번 이상 나오면 어느 것이 맞는지 알 수 없으므로 그 번호는 뺀다.
    fun parseExplanations(lines: List<OcrLine>): Map<Int, String> {
        val starts = findNumberLines(lines)

        val result = mutableMapOf<Int, String>()
        val seen = mutableSetOf<Int>() // 지금까지 본 번호들 (Set = 중복 없는 목록)

        for ((index, start) in starts.withIndex()) {
            val number = numberOf(lines[start].text) ?: continue

            // 이 블록의 끝 = 다음 번호 줄 바로 앞. 마지막이면 페이지 끝까지.
            var end = lines.size
            if (index + 1 < starts.size) {
                end = starts[index + 1]
            }

            // seen.add(number) = 번호를 넣는다. 처음 넣으면 true, 이미 있으면 false.
            // false 면 같은 번호가 또 나온 것이므로, 앞에서 저장한 해설도 지우고 이 블록도 건너뛴다.
            // ("해설"이 없는 블록이어도 번호는 본 것으로 치려고, 해설을 찾기 전에 검사한다)
            if (!seen.add(number)) {
                result.remove(number)
                continue
            }

            // 번호 다음부터 end 전까지 중에서 "해설"로 시작하는 첫 줄을 찾는다
            var explanationStart = -1
            for (i in start + 1 until end) {
                if (lines[i].text.startsWith("해설")) {
                    explanationStart = i
                    break
                }
            }

            // "해설"이 없으면 이 번호는 해설 없음
            if (explanationStart == -1) continue

            // "해설" 줄부터 블록 끝까지를 공백 하나로 이어 붙인다. 맨 앞의 "해설" 글자는 뗀다.
            val builder = StringBuilder()
            for (i in explanationStart until end) {
                if (builder.isNotEmpty()) {
                    builder.append(" ")
                }
                builder.append(lines[i].text.trim())
            }
            result[number] = builder.toString().removePrefix("해설").trim()
        }

        return result
    }

    // ===== 4단계: 번호가 같은 문제에만 해설을 붙인다 =====
    // 문제 쪽 번호도 한 사진에서 두 번 이상 나오면 어긋날 위험이 있어 붙이지 않는다.
    // 번호가 안 맞거나 해설이 없으면 해설은 빈 문자열로 둔다. (틀린 해설보다 빈 해설이 낫다)
    fun attachExplanations(
        questions: List<NumberedQuestion>,
        explanations: Map<Int, String>
    ): List<Question> {
        val result = mutableListOf<Question>()

        for (item in questions) {
            // 이 번호가 문제 목록에서 몇 번 나오는지 센다
            var count = 0
            for (other in questions) {
                if (other.number == item.number) count++
            }

            val explanation = explanations[item.number] // 없으면 null
            if (count == 1 && explanation != null) {
                result.add(item.question.copy(explanation = explanation))
            } else {
                result.add(item.question)
            }
        }
        return result
    }
}