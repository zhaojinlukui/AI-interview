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
 * 个人中心资料维护和统计数据服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProfileService {

    private static final String DEFAULT_WEAKNESS_ITEM = "综合能力";
    private static final Pattern FOLLOW_UP_CATEGORY_PATTERN =
            Pattern.compile("(?i)^(.*?)\\s*(?:follow[-_\\s]*up|追问)\\s*\\d+\\s*$");
    private static final int TREND_DAYS = 30;
    private static final int WEAKNESS_LIMIT = 5;

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
     */
    @Transactional
    public AuthUserDTO updateDisplayName(String displayName) {
        UserEntity user = getCurrentUserEntity();
        user.setDisplayName(displayName.trim());
        user = userRepository.save(user);
        return userMapper.toAuthUserDTO(user);
    }

    /**
     * 修改当前用户密码，并递增令牌版本
     */
    @Transactional
    public void updatePassword(String currentPassword, String newPassword) {
        UserEntity user = getCurrentUserEntity();
        if (!passwordService.matches(currentPassword, user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.USER_INVALID_CREDENTIALS, "当前密码错误");
        }
        user.setPasswordHash(passwordService.hash(newPassword));
        user.rotateTokenVersion();
        userRepository.save(user);
    }

    /**
     * 查询当前用户的面试统计、成长趋势和薄弱项
     */
    @Transactional(readOnly = true)
    public ProfileStatsResponse getStats() {
        String userId = CurrentUserContext.getRequiredUserId();
        LocalDate today = LocalDate.now();
        LocalDateTime tomorrowStart = today.plusDays(1).atStartOfDay();
        LocalDateTime earliestStart = today.minusDays(TREND_DAYS * 2L - 1).atStartOfDay();

        List<InterviewSessionEntity> textSessions =
                interviewSessionRepository.findByUserIdAndCreatedAtGreaterThanEqualOrderByCreatedAtAsc(
                        userId,
                        earliestStart
                );
        List<VoiceInterviewSessionEntity> voiceSessions =
                voiceSessionRepository.findByUserIdAndCreatedAtGreaterThanEqualOrderByCreatedAtAsc(
                        userId,
                        earliestStart
                );
        Map<Long, VoiceInterviewEvaluationEntity> voiceEvaluations =
                loadVoiceEvaluations(voiceSessions);

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
     * 获取当前用户实体
     */
    private UserEntity getCurrentUserEntity() {
        Long userId = Long.valueOf(CurrentUserContext.getRequiredUserId());
        return userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND, "用户不存在"));
    }

    /**
     * 批量加载语音面试评估结果
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
     * 构建指定周期与上一周期的面试数量和平均分对比
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
     * 构建最近 30 天的每日面试趋势
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
     * 汇总薄弱项并按平均分从低到高取前几项
     */
    private List<WeaknessTrendDTO> buildWeaknessTrend(
            List<CategoryScoreRecord> records,
            LocalDate today,
            LocalDateTime tomorrowStart
    ) {
        LocalDateTime currentStart = today.minusDays(TREND_DAYS - 1L).atStartOfDay();
        LocalDateTime previousStart = currentStart.minusDays(TREND_DAYS);

        Map<String, ScoreBucket> current = aggregateCategoryScores(records, currentStart, tomorrowStart);
        Map<String, ScoreBucket> previous = aggregateCategoryScores(records, previousStart, currentStart);

        return current.entrySet().stream()
                .map(entry -> {
                    String item = entry.getKey();
                    Double averageScore = entry.getValue().average();
                    ScoreBucket previousBucket = previous.get(item);
                    Double previousAverageScore = previousBucket == null ? null : previousBucket.average();
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
                .sorted(Comparator
                        .comparing(WeaknessTrendDTO::averageScore, Comparator.nullsLast(Double::compareTo))
                        .thenComparing(WeaknessTrendDTO::sampleCount, Comparator.reverseOrder()))
                .limit(WEAKNESS_LIMIT)
                .toList();
    }

    /**
     * 合并文本面试和语音面试中的分类评分记录
     */
    private List<CategoryScoreRecord> loadCategoryScores(
            String userId,
            LocalDateTime earliestStart,
            List<VoiceInterviewSessionEntity> voiceSessions,
            Map<Long, VoiceInterviewEvaluationEntity> voiceEvaluations
    ) {
        List<CategoryScoreRecord> records = new ArrayList<>();
        for (InterviewAnswerEntity answer :
                interviewAnswerRepository.findScoredAnswersByUserIdSince(userId, earliestStart)) {
            records.add(new CategoryScoreRecord(
                    answer.getSession().getCreatedAt(),
                    normalizeCategory(answer.getCategory()),
                    answer.getScore()
            ));
        }

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
     * 从语音面试评估 JSON 中解析每题分类评分
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
            log.warn("Voice evaluation category parse failed: sessionId={}", evaluation.getSessionId(), e);
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
     * 统计指定时间范围内的面试次数
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
     * 判断时间是否落在左闭右开的范围内
     */
    private boolean inRange(LocalDateTime value, LocalDateTime start, LocalDateTime end) {
        return value != null && !value.isBefore(start) && value.isBefore(end);
    }

    /**
     * 归一化分类名称，追问题归并到原始分类
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
     * 保留一位小数
     */
    private Double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private record InterviewOccurrence(LocalDateTime createdAt) {
    }

    private record ScoreRecord(LocalDateTime recordedAt, int score) {
    }

    private record CategoryScoreRecord(LocalDateTime recordedAt, String category, int score) {
    }

    /**
     * 分类分数聚合桶
     */
    private static class ScoreBucket {

        private long count;
        private long total;

        void add(int score) {
            count++;
            total += score;
        }

        long count() {
            return count;
        }

        Double average() {
            if (count == 0) {
                return null;
            }
            return Math.round((total * 10.0) / count) / 10.0;
        }
    }
}
