package interview.guide.modules.interviewschedule.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import interview.guide.common.ai.AiClientFactory;
import interview.guide.common.ai.PromptSanitizer;
import interview.guide.common.config.AiProperties;
import interview.guide.modules.interviewschedule.model.ParseResponse;
import java.io.IOException;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

@DisplayName("面试邀约规则解析")
class InterviewParseServiceTest {

  private InterviewParseService parseService;

  @BeforeEach
  void setUp() throws IOException {
    parseService = new InterviewParseService(
        mock(AiClientFactory.class),
        new ObjectMapper(),
        new PromptSanitizer(new AiProperties()),
        new DefaultResourceLoader()
    );
  }

  @Test
  @DisplayName("解析飞书标签格式邀约")
  void parseFeishuInviteWithLabeledFields() {
    String rawText = """
        公司：字节跳动
        岗位：Java 后端工程师
        时间：2026/07/03 14:00
        飞书会议：https://meeting.feishu.cn/s/abc123
        面试轮次：第二轮技术面
        面试官：王老师
        备注：请提前 10 分钟入会
        """;

    ParseResponse response = parseService.parse(rawText);

    assertThat(response.getSuccess()).isTrue();
    assertThat(response.getParseMethod()).isEqualTo("rule");
    assertThat(response.getData().getCompanyName()).isEqualTo("字节跳动");
    assertThat(response.getData().getPosition()).isEqualTo("Java 后端工程师");
    assertThat(response.getData().getInterviewTime()).isEqualTo(LocalDateTime.of(2026, 7, 3, 14, 0));
    assertThat(response.getData().getMeetingLink()).isEqualTo("https://meeting.feishu.cn/s/abc123");
    assertThat(response.getData().getRoundNumber()).isEqualTo(2);
    assertThat(response.getData().getInterviewType()).isEqualTo("VIDEO");
  }

  @Test
  @DisplayName("解析腾讯会议标题格式邀约")
  void parseTencentInviteWithBracketTitle() {
    String rawText = """
        【阿里巴巴】后端开发工程师一面邀请
        候选人：张三
        面试时间：2026-04-15 19:30
        面试形式：视频面试（腾讯会议）
        会议链接：https://meeting.tencent.com/abc-defg-hij
        面试轮次：第一轮技术面
        面试官：李老师
        备注：请提前10分钟入会，准备项目介绍与系统设计案例。
        """;

    ParseResponse response = parseService.parse(rawText);

    assertThat(response.getSuccess()).isTrue();
    assertThat(response.getParseMethod()).isEqualTo("rule");
    assertThat(response.getData().getCompanyName()).isEqualTo("阿里巴巴");
    assertThat(response.getData().getPosition()).isEqualTo("后端开发工程师");
    assertThat(response.getData().getInterviewTime()).isEqualTo(LocalDateTime.of(2026, 4, 15, 19, 30));
    assertThat(response.getData().getMeetingLink()).isEqualTo("https://meeting.tencent.com/abc-defg-hij");
    assertThat(response.getData().getRoundNumber()).isEqualTo(1);
  }

  @Test
  @DisplayName("解析 Zoom Topic 格式邀约")
  void parseZoomInviteWithTopicTitle() {
    String rawText = """
        Topic: 字节跳动 Java 后端工程师 二面
        Time: 2026年7月3日 下午2:30
        Join Zoom Meeting
        https://us02web.zoom.us/j/123456789?pwd=abcdef
        Passcode: 9xYz12
        """;

    ParseResponse response = parseService.parse(rawText);

    assertThat(response.getSuccess()).isTrue();
    assertThat(response.getParseMethod()).isEqualTo("rule");
    assertThat(response.getData().getCompanyName()).isEqualTo("字节跳动");
    assertThat(response.getData().getPosition()).isEqualTo("Java 后端工程师");
    assertThat(response.getData().getInterviewTime()).isEqualTo(LocalDateTime.of(2026, 7, 3, 14, 30));
    assertThat(response.getData().getMeetingLink())
        .isEqualTo("https://us02web.zoom.us/j/123456789?pwd=abcdef");
    assertThat(response.getData().getRoundNumber()).isEqualTo(2);
  }
}
