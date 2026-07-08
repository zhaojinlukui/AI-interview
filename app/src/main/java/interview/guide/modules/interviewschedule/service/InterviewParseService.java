package interview.guide.modules.interviewschedule.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import interview.guide.common.ai.AiClientFactory;
import interview.guide.common.ai.PromptSanitizer;
import interview.guide.common.ai.PromptSecurityConstants;
import interview.guide.modules.interviewschedule.model.CreateInterviewRequest;
import interview.guide.modules.interviewschedule.model.ParseResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.template.st.StTemplateRenderer;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 面试邀约解析服务
 * 整合规则解析和AI解析两种方式，从面试邀约文本中提取公司、职位、时间等结构化信息，
 * 优先使用规则解析，失败时降级为AI解析
 */
@Slf4j
@Service
public class InterviewParseService {

    private final AiClientFactory aiClientFactory;
    private final ObjectMapper objectMapper;
    private final PromptSanitizer promptSanitizer;
    private final PromptTemplate parsePromptTemplate;
    private static final String PARSE_PROMPT_PATH = "classpath:prompts/interview-schedule-parse.st";

    private static final Map<String, Integer> CHINESE_NUMBERS = Map.of(
            "一", 1, "二", 2, "三", 3, "四", 4, "五", 5,
            "六", 6, "七", 7, "八", 8, "九", 9, "十", 10
    );

    private static final Pattern COMPANY_LABEL_PATTERN = Pattern.compile("(?m)^\\s*(?:面试)?(?:公司|单位|组织|企业|公司名称|招聘方)\\s*[：:]\\s*(\\S[^\\r\\n]{0,80})");
    private static final Pattern POSITION_LABEL_PATTERN = Pattern.compile("(?m)^\\s*(?:面试|应聘)?(?:岗位|职位|职务|岗位名称|职位名称)\\s*[：:]\\s*(\\S[^\\r\\n]{0,80})");
    private static final Pattern INTERVIEWER_LABEL_PATTERN = Pattern.compile("(?m)^\\s*(?:面试官|联系人|HR)\\s*[：:]\\s*(\\S[^\\r\\n]{0,80})");
    private static final Pattern NOTES_LABEL_PATTERN = Pattern.compile("(?m)^\\s*(?:备注|说明|注意事项)\\s*[：:]\\s*(\\S[^\\r\\n]{0,160})");
    private static final Pattern BRACKET_TITLE_PATTERN = Pattern.compile("(?m)^\\s*【([^】\\r\\n]{1,50})】\\s*([^\\r\\n]{0,100})");
    private static final Pattern TOPIC_PATTERN = Pattern.compile("(?im)^\\s*(?:主题|标题|Topic)\\s*[：:]\\s*([^\\r\\n]{2,120})");
    private static final Pattern DATE_TIME_PATTERN = Pattern.compile("(\\d{4})[-/](\\d{1,2})[-/](\\d{1,2})[ T\\s]+(?:周[一二三四五六日天]\\s*)?" + "(上午|下午|晚上|中午)?\\s*(\\d{1,2})[：:点](\\d{2})?");
    private static final Pattern CHINESE_DATE_TIME_PATTERN = Pattern.compile("(\\d{4})年(\\d{1,2})月(\\d{1,2})日?\\s*(?:周[一二三四五六日天]\\s*)?" + "(上午|下午|晚上|中午)?\\s*(\\d{1,2})[：:点](\\d{2})?");
    private static final Pattern MEETING_LINK_PATTERN = Pattern.compile("https?://(?:[\\w-]+\\.)*(?:meeting\\.feishu\\.cn|meeting\\.tencent\\.com|" + "voovmeeting\\.com|zoom\\.us|zoom\\.com)/[^\\s\\r\\n，,。)）>】]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern MEETING_ID_PATTERN = Pattern.compile("(?i)(?:会议号|会议\\s*ID|Meeting\\s*ID)\\s*[：:]?\\s*([\\d\\s-]{6,})");
    private static final Pattern PASSWORD_PATTERN = Pattern.compile("(?i)(?:密码|口令|Passcode|Password)\\s*[：:]?\\s*([A-Za-z0-9]{4,})");
    private static final Pattern ROUND_PATTERN = Pattern.compile("(?:第\\s*)?([一二三四五六七八九十\\d]+)\\s*(?:轮|面|场)");

    public InterviewParseService(
            AiClientFactory aiClientFactory,
            ObjectMapper objectMapper,
            PromptSanitizer promptSanitizer,
            ResourceLoader resourceLoader
    ) throws IOException {
        this.aiClientFactory = aiClientFactory;
        this.objectMapper = objectMapper;
        this.promptSanitizer = promptSanitizer;
        this.parsePromptTemplate = PromptTemplate.builder()
                .template(resourceLoader.getResource(PARSE_PROMPT_PATH)
                        .getContentAsString(StandardCharsets.UTF_8))
                .renderer(StTemplateRenderer.builder()
                        .startDelimiterToken('$')
                        .endDelimiterToken('$')
                        .build())
                .build();
    }

    /**
     * 解析面试邀约文本
     * 优先使用规则解析，失败时降级为AI解析
     */
    public ParseResponse parse(String rawText) {
        log.info("开始解析文本，文本长度: {}", rawText != null ? rawText.length() : 0);
        if (rawText == null || rawText.trim().isEmpty()) {
            log.warn("输入文本为空或为 null");
            return new ParseResponse(false, null, "none", "输入文本为空");
        }

        // 第一步：优先尝试规则解析，速度快且确定性高
        CreateInterviewRequest result = parseCommonRules(rawText);
        if (isValidResult(result)) {
            log.info("规则解析成功");
            return new ParseResponse(true, result, "rule", "规则解析成功");
        }

        // 第二步：规则解析失败时尝试AI解析，处理非结构化文本
        log.info("规则解析失败，尝试 AI 解析");
        result = parseWithAI(rawText);
        if (isValidResult(result)) {
            log.info("AI 解析成功");
            return new ParseResponse(true, result, "ai", "AI 解析成功");
        }

        // 第三步：两种解析方式均失败
        log.warn("所有解析方式均失败");
        return new ParseResponse(false, null, "none", "解析失败");
    }

    /**
     * 通用规则解析
     * 依次提取公司名称、职位、面试时间、会议链接、轮次、类型、面试官和备注
     */
    private CreateInterviewRequest parseCommonRules(String rawText) {
        CreateInterviewRequest request = new CreateInterviewRequest();
        TitleInfo titleInfo = extractTitleInfo(rawText);

        // 提取公司名称，优先使用标签匹配，其次使用标题中的公司名
        request.setCompanyName(firstNonBlank(extractLabeledValue(rawText, COMPANY_LABEL_PATTERN),
                titleInfo.companyName()));
        // 提取职位，优先使用标签匹配，其次使用标题中的职位
        request.setPosition(firstNonBlank(extractLabeledValue(rawText, POSITION_LABEL_PATTERN),
                titleInfo.position()));
        request.setInterviewTime(extractInterviewTime(rawText));
        request.setMeetingLink(extractMeetingLink(rawText));
        request.setRoundNumber(extractRoundNumber(rawText));
        // 根据文本内容和会议链接推断面试类型
        request.setInterviewType(resolveInterviewType(rawText, request.getMeetingLink()));
        request.setInterviewer(extractLabeledValue(rawText, INTERVIEWER_LABEL_PATTERN));
        request.setNotes(extractLabeledValue(rawText, NOTES_LABEL_PATTERN));
        return request;
    }

    /**
     * 从文本中提取标题信息（公司名和职位）
     * 支持【公司名】职位名、主题标签、以及包含邀请/邀约/通知关键词的行
     */
    private TitleInfo extractTitleInfo(String rawText) {
        // 尝试匹配【公司名】职位名 格式
        Matcher bracketMatcher = BRACKET_TITLE_PATTERN.matcher(rawText);
        if (bracketMatcher.find()) {
            return new TitleInfo(
                    cleanFieldValue(bracketMatcher.group(1)),
                    cleanTitlePosition(bracketMatcher.group(2))
            );
        }

        // 尝试匹配主题/标题标签
        Matcher topicMatcher = TOPIC_PATTERN.matcher(rawText);
        if (topicMatcher.find()) {
            return splitTitle(topicMatcher.group(1));
        }

        // 尝试匹配包含邀请/邀约/通知关键词的行
        for (String line : rawText.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.length() <= 120
                    && (trimmed.contains("邀请") || trimmed.contains("邀约") || trimmed.contains("通知"))) {
                TitleInfo titleInfo = splitTitle(trimmed);
                if (titleInfo.companyName() != null || titleInfo.position() != null) {
                    return titleInfo;
                }
            }
        }

        return new TitleInfo(null, null);
    }

    /**
     * 分割标题为公司名和职位
     * 按空格分割，第一部分为公司名，第二部分为职位
     */
    private TitleInfo splitTitle(String title) {
        String cleaned = cleanTitlePosition(title);
        if (cleaned == null) {
            return new TitleInfo(null, null);
        }

        String[] parts = cleaned.split("\\s+", 2);
        if (parts.length == 2) {
            return new TitleInfo(cleanFieldValue(parts[0]), cleanTitlePosition(parts[1]));
        }

        return new TitleInfo(null, cleaned);
    }

    /**
     * 使用正则模式从文本中提取标签值
     */
    private String extractLabeledValue(String rawText, Pattern pattern) {
        Matcher matcher = pattern.matcher(rawText);
        return matcher.find() ? cleanFieldValue(matcher.group(1)) : null;
    }

    /**
     * 提取面试时间
     * 优先匹配标准日期时间格式，其次匹配中文日期时间格式
     */
    private LocalDateTime extractInterviewTime(String rawText) {
        Matcher dateTimeMatcher = DATE_TIME_PATTERN.matcher(rawText);
        if (dateTimeMatcher.find()) {
            return buildDateTime(dateTimeMatcher);
        }

        Matcher chineseDateTimeMatcher = CHINESE_DATE_TIME_PATTERN.matcher(rawText);
        if (chineseDateTimeMatcher.find()) {
            return buildDateTime(chineseDateTimeMatcher);
        }

        return null;
    }

    /**
     * 提取会议链接
     * 优先匹配直接链接，其次提取会议号和密码组合
     */
    private String extractMeetingLink(String rawText) {
        Matcher linkMatcher = MEETING_LINK_PATTERN.matcher(rawText);
        if (linkMatcher.find()) {
            return stripTrailingPunctuation(linkMatcher.group());
        }

        // 提取会议号和密码并拼接
        Matcher meetingIdMatcher = MEETING_ID_PATTERN.matcher(rawText);
        Matcher passwordMatcher = PASSWORD_PATTERN.matcher(rawText);
        StringBuilder meetingInfo = new StringBuilder();
        if (meetingIdMatcher.find()) {
            meetingInfo.append("会议号: ").append(meetingIdMatcher.group(1).replaceAll("\\s+", ""));
        }
        if (passwordMatcher.find()) {
            if (meetingInfo.length() > 0) {
                meetingInfo.append(" ");
            }
            meetingInfo.append("密码: ").append(passwordMatcher.group(1));
        }

        return meetingInfo.length() > 0 ? meetingInfo.toString() : null;
    }

    /**
     * 提取面试轮次
     * 默认为第1轮
     */
    private Integer extractRoundNumber(String rawText) {
        Matcher matcher = ROUND_PATTERN.matcher(rawText);
        return matcher.find() ? parseRoundNumber(matcher.group(1)) : 1;
    }

    /**
     * 根据文本内容和会议链接判断面试类型
     * 支持现场面试、电话面试和视频面试
     */
    private String resolveInterviewType(String rawText, String meetingLink) {
        String normalizedText = rawText.toLowerCase();
        // 包含现场/线下/地址等关键词判断为现场面试
        if (rawText.contains("现场") || rawText.contains("线下") || rawText.contains("到场")
                || rawText.contains("地址")) {
            return "ONSITE";
        }
        // 包含电话关键词判断为电话面试
        if (rawText.contains("电话") || normalizedText.contains("phone")) {
            return "PHONE";
        }
        // 有会议链接或包含视频会议相关关键词判断为视频面试
        if (meetingLink != null || rawText.contains("视频") || rawText.contains("腾讯会议")
                || rawText.contains("飞书") || normalizedText.contains("zoom")) {
            return "VIDEO";
        }
        // 默认视频面试
        return "VIDEO";
    }

    // ========== AI 解析 ==========

    /**
     * 使用AI解析面试邀约文本
     * 调用大模型从非结构化文本中提取结构化信息
     */
    private CreateInterviewRequest parseWithAI(String rawText) {
        try {
            // 构造用户提示词，包含当前日期和清洗后的文本
            String currentDate = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
            String safeRawText = promptSanitizer.sanitize(rawText);
            String prompt = parsePromptTemplate.render(Map.of(
                    "currentDate", currentDate,
                    "parseInput",
                    PromptSecurityConstants.DATA_BOUNDARY_INSTRUCTION + "\n"
                            + promptSanitizer.wrapWithDelimiters("parse-input", safeRawText)
            ));

            // 调用AI进行解析
            ChatClient chatClient = aiClientFactory.getDefaultChatClient();
            String content = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();
            if (content == null || content.trim().isEmpty()) {
                log.error("AI 解析返回内容为空");
                return null;
            }

            // 从Markdown代码块中提取JSON内容
            String jsonContent = content.trim();
            if (jsonContent.contains("```")) {
                Pattern pattern = Pattern.compile("```(?:json)?\\s*([\\s\\S]*?)\\s*```");
                Matcher matcher = pattern.matcher(jsonContent);
                if (matcher.find()) {
                    jsonContent = matcher.group(1).trim();
                }
            }
            log.debug("提取到的 JSON 内容: {}", jsonContent);
            Map<String, Object> result = objectMapper.readValue(jsonContent, new TypeReference<>() {});
            if (result == null || result.isEmpty()) {
                log.error("JSON 解析返回空结果");
                return null;
            }

            // 将AI返回的JSON字段映射到请求对象
            CreateInterviewRequest request = new CreateInterviewRequest();
            if (result.get("companyName") != null) {
                request.setCompanyName(result.get("companyName").toString().trim());
            }
            if (result.get("position") != null) {
                request.setPosition(result.get("position").toString().trim());
            }
            if (result.get("interviewTime") != null) {
                try {
                    String timeStr = result.get("interviewTime").toString().trim();
                    // 处理YYYY-MM-DDTHH:MM格式，补充秒数
                    if (timeStr.length() == 16) {
                        request.setInterviewTime(LocalDateTime.parse(timeStr + ":00"));
                    } else {
                        request.setInterviewTime(LocalDateTime.parse(timeStr));
                    }
                } catch (Exception e) {
                    log.error("AI 返回的时间格式不正确: {}", result.get("interviewTime"));
                }
            }
            if (result.get("interviewType") != null) {
                request.setInterviewType(result.get("interviewType").toString().trim());
            }
            if (result.get("meetingLink") != null) {
                request.setMeetingLink(result.get("meetingLink").toString().trim());
            }
            if (result.get("roundNumber") != null) {
                try {
                    String roundStr = result.get("roundNumber").toString().trim();
                    request.setRoundNumber(Integer.parseInt(roundStr));
                } catch (Exception e) {
                    request.setRoundNumber(1);
                }
            }
            if (result.get("interviewer") != null) {
                request.setInterviewer(result.get("interviewer").toString().trim());
            }
            if (result.get("notes") != null) {
                request.setNotes(result.get("notes").toString().trim());
            }
            log.info("AI 解析成功: {}", request.getCompanyName());
            return request;
        } catch (Exception e) {
            log.error("AI 解析异常: {}", e.getMessage(), e);
            return null;
        }
    }

    // ========== 辅助方法 ==========

    /**
     * 根据正则匹配结果构建LocalDateTime对象
     */
    private LocalDateTime buildDateTime(Matcher matcher) {
        int year = Integer.parseInt(matcher.group(1));
        int month = Integer.parseInt(matcher.group(2));
        int day = Integer.parseInt(matcher.group(3));
        String period = matcher.group(4); // 上午/下午/晚上/中午
        int hour = adjustHourByPeriod(period, Integer.parseInt(matcher.group(5)));
        int minute = matcher.group(6) != null ? Integer.parseInt(matcher.group(6)) : 0;
        try {
            return LocalDateTime.of(year, month, day, hour, minute);
        } catch (Exception e) {
            log.error("时间解析失败: {}", matcher.group(), e);
            return null;
        }
    }

    /**
     * 根据上下午时段调整小时数
     * 下午/晚上且小时小于12时加12，中午且小时小于11时加12
     */
    private int adjustHourByPeriod(String period, int hour) {
        if (period == null) {
            return hour;
        }
        if (("下午".equals(period) || "晚上".equals(period)) && hour < 12) {
            return hour + 12;
        }
        if ("中午".equals(period) && hour < 11) {
            return hour + 12;
        }
        return hour;
    }

    /**
     * 解析轮次数字，支持阿拉伯数字和中文数字
     */
    private int parseRoundNumber(String text) {
        if (text == null) return 1;

        text = text.trim();

        // 阿拉伯数字直接解析
        if (text.matches("\\d+")) {
            return Integer.parseInt(text);
        }

        // 尝试解析中文数字
        Integer chineseNumber = parseChineseNumber(text);
        if (chineseNumber != null) {
            return chineseNumber;
        }

        return 1;
    }

    /**
     * 解析中文数字为阿拉伯数字
     * 支持"一"到"十"以及"十一"到"九十九"
     */
    private Integer parseChineseNumber(String text) {
        if (CHINESE_NUMBERS.containsKey(text)) {
            return CHINESE_NUMBERS.get(text);
        }
        // 处理"十几"格式，如"十二"
        if (text.startsWith("十") && text.length() == 2) {
            return 10 + CHINESE_NUMBERS.getOrDefault(text.substring(1), 0);
        }
        // 处理"几十"格式，如"二十"
        if (text.endsWith("十") && text.length() == 2) {
            return CHINESE_NUMBERS.getOrDefault(text.substring(0, 1), 1) * 10;
        }
        return null;
    }

    /**
     * 清洗职位名称文本，去除面试邀请等无关词汇
     */
    private String cleanTitlePosition(String text) {
        String value = cleanFieldValue(text);
        if (value == null) {
            return null;
        }
        // 去除开头的分隔符
        value = value.replaceFirst("^(?:[-—:：\\s]+)", "");
        // 去除轮次信息
        value = value.replaceFirst("(?:第\\s*)?[一二三四五六七八九十\\d]+\\s*(?:轮|面|场).*$", "");
        // 去除面试邀请等关键词
        value = value.replaceFirst("(?:面试邀请|面试通知|面试邀约|邀请|邀约|通知|Interview).*$", "");
        return cleanFieldValue(value);
    }

    /**
     * 清洗字段值，去除首尾空格和尾部标点符号
     */
    private String cleanFieldValue(String text) {
        if (text == null) {
            return null;
        }
        String value = text.trim();
        value = stripTrailingPunctuation(value);
        return value.isBlank() ? null : value;
    }

    /**
     * 去除文本尾部的标点符号
     */
    private String stripTrailingPunctuation(String text) {
        return text == null ? null : text.trim().replaceAll("[，,。；;、）)】>]+$", "");
    }

    /**
     * 返回第一个非空字符串，优先使用第一个参数
     */
    private String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    /**
     * 判断解析结果是否有效
     */
    private boolean isValidResult(CreateInterviewRequest result) {
        return result != null
                && result.getCompanyName() != null
                && result.getPosition() != null
                && result.getInterviewTime() != null;
    }

    /**
     * 标题信息记录类
     * 存储从标题中提取的公司名称和职位信息
     */
    private record TitleInfo(String companyName, String position) {
    }
}