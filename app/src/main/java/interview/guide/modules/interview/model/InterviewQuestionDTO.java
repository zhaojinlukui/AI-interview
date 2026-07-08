package interview.guide.modules.interview.model;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 面试问题 DTO.
 */
public record InterviewQuestionDTO(
        int questionIndex,          // 问题索引
        String question,            // 问题内容
        String type,                // Skill category key，如 MYSQL、CSS、DP
        String category,            // 展示用分类标签
        String topicSummary,        // 知识点摘要
        String userAnswer,          // 用户答案
        Integer score,              // 得分
        String feedback,            // 评估反馈
        boolean isFollowUp,         // 是否追问题
        Integer parentQuestionIndex // 父问题索引
) {
    private static final Pattern LEGACY_FOLLOW_UP_CATEGORY_PATTERN =
            Pattern.compile("(?i)^(.*?)\\s*follow[-_\\s]*up\\s*(\\d+)\\s*$");

    public InterviewQuestionDTO {
        category = normalizeCategory(category);
    }

    public static String normalizeCategory(String category) {
        if (category == null || category.isBlank()) {
            return category;
        }

        String trimmed = category.trim();
        Matcher matcher = LEGACY_FOLLOW_UP_CATEGORY_PATTERN.matcher(trimmed);
        if (!matcher.matches()) {
            return trimmed;
        }

        String baseCategory = matcher.group(1).trim();
        String order = matcher.group(2);
        if (baseCategory.isBlank()) {
            return "追问" + order;
        }
        return baseCategory + " 追问" + order;
    }

    public static InterviewQuestionDTO create(
            int index, String question, String type, String category) {
        return new InterviewQuestionDTO(index, question, type, category, null, null, null, null, false,
                null);
    }

    public static InterviewQuestionDTO create(
            int index, String question, String type, String category, String topicSummary,
            boolean isFollowUp, Integer parentQuestionIndex) {
        return new InterviewQuestionDTO(index, question, type, category, topicSummary, null, null, null,
                isFollowUp, parentQuestionIndex);
    }

    public InterviewQuestionDTO withAnswer(String answer) {
        return new InterviewQuestionDTO(questionIndex, question, type, category, topicSummary, answer,
                score, feedback, isFollowUp, parentQuestionIndex);
    }

    public InterviewQuestionDTO withEvaluation(int score, String feedback) {
        return new InterviewQuestionDTO(questionIndex, question, type, category, topicSummary,
                userAnswer, score, feedback, isFollowUp, parentQuestionIndex);
    }
}
