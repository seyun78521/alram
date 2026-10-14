package com.example.androidlab.ocr

import com.example.androidlab.data.Question

// 변환 결과를 담는 상자.
// data class = 값을 담는 상자 전용 클래스. Java로 치면 getter, equals, toString을 자동으로 만들어 주는 클래스다.
// questions: 만들어진 문제들 / skipped: 만들지 못한 문제와 이유
data class ParseResult(
    val questions: List<Question>,
    val skipped: List<String>
)

/*
 * TOEIC Part 2 간단한 파서
 *
 * 흐름: OCR이 읽은 줄들 → 문제 번호로 나누기 → 문제 문장 + A/B/C 찾기 → Question 만들기
 *
 * object = 객체가 딱 하나만 있는 클래스. Java의 static 메서드 모음과 비슷하다.
 */
object QuestionParser {

    // ===== 정규식 (Regex) 읽는 법 =====
    // 정규식 = "이런 모양의 글자를 찾아라"라고 적은 패턴이다.
    //   ^      줄의 맨 앞            $      줄의 맨 끝
    //   \d     숫자 한 글자(0~9)     \s     공백 한 글자
    //   [ABC]  A, B, C 중 한 글자    [A-Za-z] 영어 알파벳 한 글자
    //   *      바로 앞의 것이 0번 이상 반복
    //   ?      바로 앞의 것이 있어도 되고 없어도 됨
    //   {1,2}  바로 앞의 것이 1번~2번 반복
    // """...""" 는 \ 를 두 번 쓰지 않아도 되는 긴 문자열이다.

    // 문제 번호 줄: "19", "20.", "21)" 처럼 숫자만 있는 줄
    //   ^\s*      앞쪽 공백이 있어도 됨
    //   \d{1,2}   숫자 1~2개
    //   \s*[.)]?  뒤에 "." 또는 ")" 가 하나 있어도 되고 없어도 됨
    //   \s*$      뒤쪽 공백이 있어도 되고 줄 끝
    private val numberRegex = Regex("""^\s*\d{1,2}\s*[.)]?\s*$""")

    // 화자 표시: "M-Cn", "W-Br", "M-Au" 처럼 줄 맨 앞에 붙는 것 (문제 문장에서 지운다)
    //   [MW]        M 또는 W
    //   [A-Za-z]{2,3}  영어 글자 2~3개
    private val speakerRegex = Regex("""^[MW]-[A-Za-z]{2,3}\s*""")

    // ===== 작은 도우미 함수들 =====

    // 이 줄이 문제 번호인가?
    private fun isQuestionNumber(text: String): Boolean = numberRegex.matches(text)

    // 글 맨 앞의 화자 표시와 그 뒤 공백을 지운 글을 돌려준다. 표시가 없으면 그대로 돌려준다.
    // replaceFirst = 처음 찾은 한 곳만 바꾼다. 여기서는 "" (빈 글자)로 바꿔서 지우는 것이다.
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

        // 6) 4,5번이 모두 아니라면 셋째 글자부터 끝까지가 선택지 내용이다
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

    // ===== 1단계: 페이지 전체를 문제 단위로 나눈다 =====
    fun parse(lines: List<OcrLine>): ParseResult {
        // 문제 번호가 있는 줄의 위치(인덱스)를 모두 모은다. 예: [0, 7, 14]
        val starts = mutableListOf<Int>()
        for (i in lines.indices) { // lines.indices = 0부터 lines.size - 1 까지
            if (isQuestionNumber(lines[i].text)) {
                starts.add(i)
            }
        }

        if (starts.isEmpty()) {
            return ParseResult(emptyList(), listOf("문제 번호를 찾지 못했습니다."))
        }

        val questions = mutableListOf<Question>()
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

            if (question != null) {
                questions.add(question)
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
        // Map = 이름표(키)로 값을 찾는 목록. Java의 HashMap과 같다.
        val choices = mutableMapOf<String, String>()
        var firstChoice = -1 // 첫 번째 선택지가 몇 번째 줄인지 (-1 = 아직 못 찾음)

        // 줄을 위에서 아래로 하나씩 보면서 선택지를 찾는다
        for (i in lines.indices) {
            val choice = parseChoice(lines[i].text)

            // 선택지가 아니면 다음 줄로 넘어간다 (continue = Java와 같다)
            if (choice == null) continue

            val letter = choice.first   // "A" / "B" / "C"
            val content = choice.second // 선택지 내용

            // 같은 알파벳이 또 나오면 첫 번째만 쓴다 (예: 아래쪽 한글 번역 줄)
            if (choices.containsKey(letter)) continue

            choices[letter] = cleanEnglish(content)

            // 가장 처음 발견한 선택지의 줄 번호를 기억한다
            if (firstChoice == -1) {
                firstChoice = i
            }
        }

        // Part 2는 A, B, C가 모두 있어야 한다. 하나라도 없으면 포기(null).
        if (!choices.containsKey("A") || !choices.containsKey("B") || !choices.containsKey("C")) {
            return null
        }

        // 문제 문장 = 첫 번째 선택지 위쪽 줄들을 이어 붙인 것
        // StringBuilder = 글자를 계속 이어 붙이는 도구 (Java의 StringBuilder와 같다)
        val questionBuilder = StringBuilder()
        for (i in 0 until firstChoice) { // 0 until n = 0부터 n-1 까지
            // 화자 표시를 지우고 글자를 정리한다
            val lineText = cleanEnglish(removeSpeaker(lines[i].text))

            // 빈 줄은 건너뛴다
            if (lineText.isEmpty()) continue

            // 이미 앞에 글이 있으면 사이에 공백 하나를 넣는다
            if (questionBuilder.isNotEmpty()) {
                questionBuilder.append(" ")
            }
            questionBuilder.append(lineText)
        }

        // OCR이 선택지 표시 "(A)", "(B" 를 질문 끝에 붙여 읽은 경우가 있어서 그것을 지운다.
        //   1) "(A", "(B", "(C" 를 먼저 지워서 알파벳까지 같이 없앤다
        //   2) 그다음 남은 ")" 를 지운다 (예: "(A)" 의 닫는 괄호)
        // replace("찾을 글자", "바꿀 글자") = 모두 찾아서 바꾼다.
        // 지운 뒤 공백이 두 칸으로 남을 수 있어서 cleanEnglish 를 한 번 더 거친다.
        // 예: "(A How will we get this order filled in time?" → "How will we get this order filled in time?"
        val questionText = cleanEnglish(
            questionBuilder.toString()
                .replace("(A", "")
                .replace("(B", "")
                .replace("(C", "")
                .replace(")", "")
        )

        // 문제 문장이 비어 있으면 포기
        if (questionText.isEmpty()) return null

        // 정답과 해설은 비워 둔다 (""는 빈 문자열). 정답은 나중에 사람이 입력한다.
        // choices.getValue("A") = 지도에서 "A"의 값을 꺼낸다. 위에서 A, B, C가 있는 것을 확인했으므로 안전하다.
        return Question(
            questionText = questionText,
            choiceA = choices.getValue("A"),
            choiceB = choices.getValue("B"),
            choiceC = choices.getValue("C"),
            correctAnswer = "",
            explanation = ""
        )
    }
}