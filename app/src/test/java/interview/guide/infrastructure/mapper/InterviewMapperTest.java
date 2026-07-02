package interview.guide.infrastructure.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import interview.guide.modules.interview.model.InterviewAnswerEntity;
import interview.guide.modules.interview.model.InterviewDetailDTO;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

@DisplayName("面试映射器")
class InterviewMapperTest {

  private final InterviewMapper mapper = Mappers.getMapper(InterviewMapper.class);

  @Test
  @DisplayName("应将已回答和未回答问题合并为答案详情")
  void shouldMergeAnsweredAndUnansweredQuestions() {
    InterviewQuestionDTO answeredQuestion = new InterviewQuestionDTO(
        0,
        "Explain JVM memory areas",
        "JAVA",
        "Java",
        null,
        null,
        72,
        "baseline feedback",
        false,
        null
    );
    InterviewQuestionDTO unansweredQuestion = new InterviewQuestionDTO(
        1,
        "Explain Redis persistence",
        "REDIS",
        "Redis",
        null,
        null,
        null,
        "missing feedback",
        false,
        null
    );

    InterviewAnswerEntity answer = new InterviewAnswerEntity();
    answer.setQuestionIndex(0);
    answer.setQuestion("Explain JVM memory areas");
    answer.setCategory("Java");
    answer.setUserAnswer("heap and stack");
    answer.setScore(65);
    answer.setFeedback("needs metaspace");
    answer.setReferenceAnswer("heap, stack, method area");
    answer.setAnsweredAt(LocalDateTime.of(2026, 4, 1, 12, 0));

    List<InterviewDetailDTO.AnswerDetailDTO> details = mapper.toAnswerDetailDTOList(
        List.of(answeredQuestion, unansweredQuestion),
        List.of(answer),
        ignored -> List.of("heap", "stack")
    );

    assertThat(details).hasSize(2);
    assertThat(details.getFirst().questionIndex()).isEqualTo(0);
    assertThat(details.getFirst().category()).isEqualTo("Java");
    assertThat(details.getFirst().userAnswer()).isEqualTo("heap and stack");
    assertThat(details.getFirst().score()).isEqualTo(65);
    assertThat(details.getFirst().keyPoints()).containsExactly("heap", "stack");

    assertThat(details.get(1).questionIndex()).isEqualTo(1);
    assertThat(details.get(1).question()).isEqualTo("Explain Redis persistence");
    assertThat(details.get(1).userAnswer()).isNull();
    assertThat(details.get(1).score()).isZero();
    assertThat(details.get(1).feedback()).isEqualTo("missing feedback");
    assertThat(details.get(1).referenceAnswer()).isNull();
    assertThat(details.get(1).keyPoints()).isNull();
    assertThat(details.get(1).answeredAt()).isNull();
  }
}
