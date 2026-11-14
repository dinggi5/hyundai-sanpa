package studio.scopic.sanpa

/** 혼자일 때 차가 묻는 물음 하나. 한마디를 담아 떨어진 가족에게 보낸다. */
data class Question(val id: String, val text: String)

/** 혼자일 때의 물음. 같이 탔을 때의 질문은 [QuestionBank](질문_초벌.md 에서 만든다). */
object Questions {
    // 🔴 클로드 초벌(개발 3). 사장이 고른다.
    val all = listOf(
        Question("q-nag-worry", "잔소리와 걱정은 어디서 갈릴까요?"),
        Question("q-home-house", "집과 고향은 어디서 갈릴까요?"),
        Question("q-rest-idle", "쉬는 것과 노는 것은 어디서 갈릴까요?"),
        Question("q-thrift-stingy", "아끼는 것과 인색한 것은 어디서 갈릴까요?"),
        Question("q-wait-endure", "기다리는 것과 참는 것은 어디서 갈릴까요?"),
        Question("q-help-meddle", "돕는 것과 참견하는 것은 어디서 갈릴까요?"),
        Question("q-adult-old", "어른이 되는 것과 나이 드는 것은 어디서 갈릴까요?"),
        Question("q-alone-lonely", "혼자 있는 것과 외로운 것은 어디서 갈릴까요?"),
    )

    fun at(index: Int): Question = all[Math.floorMod(index, all.size)]
}
