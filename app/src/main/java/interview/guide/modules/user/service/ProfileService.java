package interview.guide.modules.user.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import interview.guide.common.auth.CurrentUserContext;
import interview.guide.common.evaluation.EvaluationReport;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.interview.model.InterviewAnswerEntity;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.repository.InterviewAnswerRepository;
import interview.guide.modules.interview.repository.InterviewSessionRepository;
import interview.guide.modules.user.model.AuthUserDTO;
import interview.guide.modules.user.model.ProfileStatsResponse;
import interview.guide.modules.user.model.ProfileStatsResponse.GrowthTrendPointDTO;
import interview.guide.modules.user.model.ProfileStatsResponse.PeriodMetricDTO;
import interview.guide.modules.user.model.ProfileStatsResponse.WeaknessTrendDTO;
import interview.guide.modules.user.model.UserEntity;
import interview.guide.modules.user.repository.UserRepository;
import interview.guide.modules.voiceinterview.model.VoiceInterviewEvaluationEntity;
import interview.guide.modules.voiceinterview.model.VoiceInterviewSessionEntity;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewEvaluationRepository;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewSessionRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 个人中心服务
 * 负责用户资料维护（昵称、密码）以及面试统计数据（成长趋势、薄弱项分析）的查询与计算
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProfileService {

    private static final String DEFAULT_WEAKNESS_ITEM = "综合能力"; // 默认薄弱项分类名称
    // 追问分类匹配模式，用于将"XX 追问1"归一化为"XX"
    private static final Pattern FOLLOW_UP_CATEGORY_PATTERN =
            Pattern.compile("(?i)^(.*?)\\s*(?:follow[-_\\s]*up|追问)\\s*\\d+\\s*$");
    private static final int TREND_DAYS = 30; // 趋势图展示天数
    private static final int WEAKNESS_LIMIT = 5; // 薄弱项展示数量上限

    private final UserRepository userRepository;
    private final PasswordService passwordService;
    private final UserMapper userMapper;
    private final InterviewSessionRepository interviewSessionRepository;
    private final InterviewAnswerRepository interviewAnswerRepository;
    private final VoiceInterviewSessionRepository voiceSessionRepository;
    private final VoiceInterviewEvaluationRepository voiceEvaluationRepository;
    private final ObjectMapper objectMapper;

    /**
     * 修改当前用户昵称
     * 更新后返回最新的用户信息
     */
    @Transactional
    public AuthUserDTO updateDisplayName(String displayName) {
        UserEntity user = getCurrentUserEntity();
        user.setDisplayName(displayName.trim());
        user = userRepository.save(user);
        return userMapper.toAuthUserDTO(user);
    }

    /**
     * 修改当前用户密码
     * 验证旧密码后更新为新密码，并递增令牌版本使旧令牌失效
     */
    @Transactional
    public void updatePassword(String currentPassword, String newPassword) {
        UserEntity user = getCurrentUserEntity();
        // 验证当前密码是否正确
        if (!passwordService.matches(currentPassword, user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.USER_INVALID_CREDENTIALS, "当前密码错误");
        }
        // 加密并更新密码
        user.setPasswordHash(passwordService.hash(newPassword));
        // 递增令牌版本，使所有旧的认证令牌失效
        user.rotateTokenVersion();
        userRepository.save(user);
    }

    /**
     * 查询当前用户的面试统计概览
     * 包含7天/30天的面试次数与平均分对比、30天成长趋势以及薄弱项分析
     */
    @Transactional(readOnly = true)
    public ProfileStatsResponse getStats() {
        String userId = CurrentUserContext.getRequiredUserId();
        LocalDate today = LocalDate.now();
        LocalDateTime tomorrowStart = today.plusDays(1).atStartOfDay();
        // 查询足够多的历史数据用于本期和上期对比（最多需要60天）
        LocalDateTime earliestStart = today.minusDays(TREND_DAYS * 2L - 1).atStartOfDay();

        // 加载文本面试会话
        List<InterviewSessionEntity> textSessions =
                interviewSessionRepository.findByUserIdAndCreatedAtGreaterThanEqualOrderByCreatedAtAsc(
                        userId,
                        earliestStart
                );
        // 加载语音面试会话
        List<VoiceInterviewSessionEntity> voiceSessions =
                voiceSessionRepository.findByUserIdAndCreatedAtGreaterThanEqualOrderByCreatedAtAsc(
                        userId,
                        earliestStart
                );
        // 批量加载语音面试评估结果
        Map<Long, VoiceInterviewEvaluationEntity> voiceEvaluations =
                loadVoiceEvaluations(voiceSessions);

        // 统一收集面试发生时间和评分记录
        List<InterviewOccurrence> occurrences = new ArrayList<>();
        List<ScoreRecord> scores = new ArrayList<>();
        for (InterviewSessionEntity session : textSessions) {
            occurrences.add(new InterviewOccurrence(session.getCreatedAt()));
            if (session.getOverallScore() != null) {
                scores.add(new ScoreRecord(session.getCreatedAt(), session.getOverallScore()));
            }
        }
        for (VoiceInterviewSessionEntity session : voiceSessions) {
            occurrences.add(new InterviewOccurrence(session.getCreatedAt()));
            VoiceInterviewEvaluationEntity evaluation = voiceEvaluations.get(session.getId());
            if (evaluation != null && evaluation.getOverallScore() != null) {
                scores.add(new ScoreRecord(session.getCreatedAt(), evaluation.getOverallScore()));
            }
        }

        // 加载分类评分记录用于薄弱项分析
        List<CategoryScoreRecord> categoryScores =
                loadCategoryScores(userId, earliestStart, voiceSessions, voiceEvaluations);

        return new ProfileStatsResponse(
                buildPeriodMetric(7, occurrences, scores, today, tomorrowStart),
                buildPeriodMetric(30, occurrences, scores, today, tomorrowStart),
                buildGrowthTrend(occurrences, scores, today, tomorrowStart),
                buildWeaknessTrend(categoryScores, today, tomorrowStart)
        );
    }

    /**
     * 获取当前登录用户实体
     */
    private UserEntity getCurrentUserEntity() {
        Long userId = Long.valueOf(CurrentUserContext.getRequiredUserId());
        return userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND, "用户不存在"));
    }

    /**
     * 批量加载语音面试评估结果
     * 返回sessionId到评估实体的映射
     */
    private Map<Long, VoiceInterviewEvaluationEntity> loadVoiceEvaluations(
            List<VoiceInterviewSessionEntity> voiceSessions
    ) {
        List<Long> sessionIds = voiceSessions.stream()
                .map(VoiceInterviewSessionEntity::getId)
                .filter(Objects::nonNull)
                .toList();
        if (sessionIds.isEmpty()) {
            return Map.of();
        }
        return voiceEvaluationRepository.findBySessionIdIn(sessionIds).stream()
                .collect(
                        LinkedHashMap::new,
                        (map, evaluation) -> map.put(evaluation.getSessionId(), evaluation),
                        LinkedHashMap::putAll
                );
    }

    /**
     * 构建指定周期的面试统计指标
     * 计算本期与上期的面试次数、平均分及变化量
     */
    private PeriodMetricDTO buildPeriodMetric(
            int days,
            List<InterviewOccurrence> occurrences,
            List<ScoreRecord> scores,
            LocalDate today,
            LocalDateTime tomorrowStart
    ) {
        LocalDateTime currentStart = today.minusDays(days - 1L).atStartOfDay();
        LocalDateTime previousStart = currentStart.minusDays(days);

        long interviewCount = countOccurrences(occurrences, currentStart, tomorrowStart);
        long previousInterviewCount = countOccurrences(occurrences, previousStart, currentStart);
        Double averageScore = averageScore(scores, currentStart, tomorrowStart);
        Double previousAverageScore = averageScore(scores, previousStart, currentStart);
        // 计算平均分变化，仅当两期都有数据时才计算
        Double averageScoreChange = averageScore != null && previousAverageScore != null
                ? round(averageScore - previousAverageScore)
                : null;

        return new PeriodMetricDTO(
                days,
                interviewCount,
                previousInterviewCount,
                interviewCount - previousInterviewCount,
                averageScore,
                previousAverageScore,
                averageScoreChange
        );
    }

    /**
     * 构建最近30天的每日面试趋势
     * 每天一个数据点，包含当天面试次数和平均分
     */
    private List<GrowthTrendPointDTO> buildGrowthTrend(
            List<InterviewOccurrence> occurrences,
            List<ScoreRecord> scores,
            LocalDate today,
            LocalDateTime tomorrowStart
    ) {
        LocalDate startDate = today.minusDays(TREND_DAYS - 1L);
        List<GrowthTrendPointDTO> points = new ArrayList<>();
        for (int i = 0; i < TREND_DAYS; i++) {
            LocalDate date = startDate.plusDays(i);
            LocalDateTime start = date.atStartOfDay();
            LocalDateTime end = date.plusDays(1).atStartOfDay();
            // 未来日期截止到当前时刻
            if (end.isAfter(tomorrowStart)) {
                end = tomorrowStart;
            }
            points.add(new GrowthTrendPointDTO(
                    date,
                    countOccurrences(occurrences, start, end),
                    averageScore(scores, start, end)
            ));
        }
        return points;
    }

    /**
     * 汇总薄弱项并按平均分从低到高排序
     * 取平均分最低的几项作为需要提升的薄弱项
     */
    private List<WeaknessTrendDTO> buildWeaknessTrend(
            List<CategoryScoreRecord> records,
            LocalDate today,
            LocalDateTime tomorrowStart
    ) {
        LocalDateTime currentStart = today.minusDays(TREND_DAYS - 1L).atStartOfDay();
        LocalDateTime previousStart = currentStart.minusDays(TREND_DAYS);

        // 分别聚合本期和上期的分类分数
        Map<String, ScoreBucket> current = aggregateCategoryScores(records, currentStart, tomorrowStart);
        Map<String, ScoreBucket> previous = aggregateCategoryScores(records, previousStart, currentStart);

        return current.entrySet().stream()
                .map(entry -> {
                    String item = entry.getKey();
                    Double averageScore = entry.getValue().average();
                    ScoreBucket previousBucket = previous.get(item);
                    Double previousAverageScore = previousBucket == null ? null : previousBucket.average();
                    // 计算变化量
                    Double change = averageScore != null && previousAverageScore != null
                            ? round(averageScore - previousAverageScore)
                            : null;
                    return new WeaknessTrendDTO(
                            item,
                            averageScore,
                            previousAverageScore,
                            change,
                            entry.getValue().count()
                    );
                })
                // 按平均分升序排序（分数最低的排最前），同分时按样本量降序
                .sorted(Comparator
                        .comparing(WeaknessTrendDTO::averageScore, Comparator.nullsLast(Double::compareTo))
                        .thenComparing(WeaknessTrendDTO::sampleCount, Comparator.reverseOrder()))
                .limit(WEAKNESS_LIMIT)
                .toList();
    }

    /**
     * 加载分类评分记录
     * 合并文本面试的回答评分和语音面试的题目评估评分
     */
    private List<CategoryScoreRecord> loadCategoryScores(
            String userId,
            LocalDateTime earliestStart,
            List<VoiceInterviewSessionEntity> voiceSessions,
            Map<Long, VoiceInterviewEvaluationEntity> voiceEvaluations
    ) {
        List<CategoryScoreRecord> records = new ArrayList<>();
        // 加载文本面试中已评分的回答
        for (InterviewAnswerEntity answer :
                interviewAnswerRepository.findScoredAnswersByUserIdSince(userId, earliestStart)) {
            records.add(new CategoryScoreRecord(
                    answer.getSession().getCreatedAt(),
                    normalizeCategory(answer.getCategory()),
                    answer.getScore()
            ));
        }

        // 加载语音面试的题目评估评分
        Map<Long, LocalDateTime> voiceCreatedAt = new HashMap<>();
        for (VoiceInterviewSessionEntity session : voiceSessions) {
            voiceCreatedAt.put(session.getId(), session.getCreatedAt());
        }
        for (VoiceInterviewEvaluationEntity evaluation : voiceEvaluations.values()) {
            LocalDateTime createdAt = voiceCreatedAt.get(evaluation.getSessionId());
            if (createdAt == null || createdAt.isBefore(earliestStart)) {
                continue;
            }
            records.addAll(parseVoiceCategoryScores(evaluation, createdAt));
        }
        return records;
    }

    /**
     * 从语音面试评估JSON中解析每道题的分类和评分
     */
    private List<CategoryScoreRecord> parseVoiceCategoryScores(
            VoiceInterviewEvaluationEntity evaluation,
            LocalDateTime createdAt
    ) {
        if (evaluation.getQuestionEvaluationsJson() == null
                || evaluation.getQuestionEvaluationsJson().isBlank()) {
            return List.of();
        }
        try {
            List<EvaluationReport.QuestionEvaluation> questions = objectMapper.readValue(
                    evaluation.getQuestionEvaluationsJson(),
                    new TypeReference<>() {
                    }
            );
            return questions.stream()
                    .map(question -> new CategoryScoreRecord(
                            createdAt,
                            normalizeCategory(question.category()),
                            question.score()
                    ))
                    .toList();
        } catch (Exception e) {
            log.warn("语音面试评估分类解析失败: sessionId={}", evaluation.getSessionId(), e);
            return List.of();
        }
    }

    /**
     * 按分类聚合指定时间范围内的分数
     */
    private Map<String, ScoreBucket> aggregateCategoryScores(
            List<CategoryScoreRecord> records,
            LocalDateTime start,
            LocalDateTime end
    ) {
        Map<String, ScoreBucket> buckets = new HashMap<>();
        for (CategoryScoreRecord record : records) {
            if (!inRange(record.recordedAt(), start, end)) {
                continue;
            }
            buckets.computeIfAbsent(record.category(), ignored -> new ScoreBucket())
                    .add(record.score());
        }
        return buckets;
    }

    /**
     * 统计指定时间范围内的面试发生次数
     */
    private long countOccurrences(
            List<InterviewOccurrence> occurrences,
            LocalDateTime start,
            LocalDateTime end
    ) {
        return occurrences.stream()
                .filter(occurrence -> inRange(occurrence.createdAt(), start, end))
                .count();
    }

    /**
     * 计算指定时间范围内的平均分
     */
    private Double averageScore(List<ScoreRecord> scores, LocalDateTime start, LocalDateTime end) {
        List<ScoreRecord> filtered = scores.stream()
                .filter(score -> inRange(score.recordedAt(), start, end))
                .toList();
        if (filtered.isEmpty()) {
            return null;
        }
        double average = filtered.stream()
                .mapToInt(ScoreRecord::score)
                .average()
                .orElse(0);
        return round(average);
    }

    /**
     * 判断时间是否落在左闭右开区间内
     */
    private boolean inRange(LocalDateTime value, LocalDateTime start, LocalDateTime end) {
        return value != null && !value.isBefore(start) && value.isBefore(end);
    }

    /**
     * 归一化分类名称
     * 将追问分类（如"Java 追问1"）归并到原始分类（如"Java"）
     */
    static String normalizeCategory(String category) {
        if (category == null || category.isBlank()) {
            return DEFAULT_WEAKNESS_ITEM;
        }
        String trimmed = category.trim();
        Matcher matcher = FOLLOW_UP_CATEGORY_PATTERN.matcher(trimmed);
        if (!matcher.matches()) {
            return trimmed;
        }
        String baseCategory = matcher.group(1).trim();
        return baseCategory.isBlank() ? DEFAULT_WEAKNESS_ITEM : baseCategory;
    }

    /**
     * 四舍五入保留一位小数
     */
    private Double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    /**
     * 面试发生时间记录
     */
    private record InterviewOccurrence(LocalDateTime createdAt) {
    }

    /**
     * 评分记录
     */
    private record ScoreRecord(LocalDateTime recordedAt, int score) {
    }

    /**
     * 分类评分记录
     */
    private record CategoryScoreRecord(LocalDateTime recordedAt, String category, int score) {
    }

    /**
     * 分类分数聚合桶
     * 用于累计某分类下的分数并计算平均分
     */
    private static class ScoreBucket {

        private long count; // 样本数量
        private long total; // 分数总和

        /**
         * 累加一个分数
         */
        void add(int score) {
            count++;
            total += score;
        }

        long count() {
            return count;
        }

        /**
         * 计算平均分，保留一位小数
         */
        Double average() {
            if (count == 0) {
                return null;
            }
            return Math.round((total * 10.0) / count) / 10.0;
        }
    }
}