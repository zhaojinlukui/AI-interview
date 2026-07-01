package interview.guide.modules.voiceinterview.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.modules.voiceinterview.listener.VoiceEvaluateStreamProducer;
import interview.guide.modules.voiceinterview.model.VoiceInterviewSessionEntity;
import interview.guide.modules.voiceinterview.model.VoiceInterviewSessionStatus;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewEvaluationRepository;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewMessageRepository;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewSessionRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@ExtendWith(MockitoExtension.class)
@DisplayName("语音面试服务")
class VoiceInterviewServiceTest {

  private static final String USER_ID = "user-1";

  @Mock
  private VoiceInterviewSessionRepository sessionRepository;

  @Mock
  private VoiceInterviewMessageRepository messageRepository;

  @Mock
  private VoiceInterviewEvaluationRepository evaluationRepository;

  @Mock
  private RedissonClient redissonClient;

  @Mock
  @SuppressWarnings("rawtypes")
  private RBucket sessionBucket;

  @Mock
  private VoiceEvaluateStreamProducer voiceEvaluateStreamProducer;

  private VoiceInterviewService service;

  @BeforeEach
  void setUp() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setAttribute("userId", USER_ID);
    request.setAttribute("username", "tester");
    request.setAttribute("userRole", "USER");
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    when(redissonClient.getBucket(org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(sessionBucket);

    service = new VoiceInterviewService(
        sessionRepository,
        messageRepository,
        evaluationRepository,
        redissonClient,
        voiceEvaluateStreamProducer,
        new interview.guide.modules.voiceinterview.config.VoiceInterviewProperties()
    );
  }

  @Test
  @DisplayName("结束会话后应在提交后发布评估任务")
  void endSessionShouldRegisterEvaluateTaskAfterCommit() {
    VoiceInterviewSessionEntity session = VoiceInterviewSessionEntity.builder()
        .id(1L)
        .userId(USER_ID)
        .roleType("java-backend")
        .skillId("java-backend")
        .status(VoiceInterviewSessionStatus.IN_PROGRESS)
        .currentPhase(VoiceInterviewSessionEntity.InterviewPhase.TECH)
        .startTime(LocalDateTime.now().minusMinutes(3))
        .build();
    when(sessionRepository.findById(1L)).thenReturn(Optional.of(session));

    TransactionSynchronizationManager.initSynchronization();
    try {
      service.endSession("1");

      verify(voiceEvaluateStreamProducer, never()).sendEvaluateTask("1");
      var synchronizations = TransactionSynchronizationManager.getSynchronizations();
      assertThat(synchronizations).hasSize(1);

      synchronizations.forEach(TransactionSynchronization::afterCommit);

      verify(voiceEvaluateStreamProducer).sendEvaluateTask("1");
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
      RequestContextHolder.resetRequestAttributes();
    }
  }

  @Test
  @DisplayName("无事务上下文时应直接发布评估任务")
  void triggerEvaluationShouldSendImmediatelyWithoutTransaction() {
    VoiceInterviewSessionEntity session = VoiceInterviewSessionEntity.builder()
        .id(2L)
        .userId(USER_ID)
        .roleType("java-backend")
        .skillId("java-backend")
        .evaluateStatus(AsyncTaskStatus.PENDING)
        .build();
    when(sessionRepository.findById(2L)).thenReturn(Optional.of(session));

    service.triggerEvaluation(2L);

    verify(voiceEvaluateStreamProducer).sendEvaluateTask("2");
  }
}
