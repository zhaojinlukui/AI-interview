package interview.guide.modules.aisettings.service;

import interview.guide.infrastructure.mapper.AiSettingsMapper;
import interview.guide.modules.aisettings.model.SystemAiSettingsEntity;
import interview.guide.modules.aisettings.repository.SystemAiSettingsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 系统AI配置解析器
 * 负责从数据库中读取系统AI配置并解析为快照对象，
 * 数据库无记录时返回默认配置，通过Mapper统一进行实体与快照的转换
 */
@Service
@RequiredArgsConstructor
public class SystemAiSettingsResolver {

    private final SystemAiSettingsRepository settingsRepository;
    private final AiSettingsMapper aiSettingsMapper;

    /**
     * 解析当前系统AI配置
     * 优先从数据库读取，不存在时返回默认配置
     */
    public SystemAiSettingsSnapshot resolve() {
        return settingsRepository.findById(SystemAiSettingsEntity.SINGLETON_ID)
                .map(this::toSnapshot)
                .orElseGet(this::defaultSnapshot);
    }

    /**
     * 构建默认配置实体
     * 用于首次保存时将默认值写入数据库，通过Mapper统一转换
     */
    SystemAiSettingsEntity buildDefaultEntity() {
        return aiSettingsMapper.toSystemAiSettingsEntity(defaultSnapshot());
    }

    /**
     * 将实体对象转换为快照对象
     */
    private SystemAiSettingsSnapshot toSnapshot(SystemAiSettingsEntity entity) {
        return new SystemAiSettingsSnapshot(
                new ResumeWeightsSnapshot(
                        entity.getResumeProjectWeight(),
                        entity.getResumeSkillMatchWeight(),
                        entity.getResumeContentWeight(),
                        entity.getResumeStructureWeight(),
                        entity.getResumeExpressionWeight()
                ),
                new InterviewSnapshot(
                        entity.getInterviewResumeQuestionRatio(),
                        entity.getInterviewDirectionQuestionRatio(),
                        entity.getInterviewQuestionTemperature(),
                        entity.getInterviewFollowUpTemperature(),
                        entity.getInterviewScoringTemperature(),
                        entity.getInterviewCommentTemperature()
                ),
                new RagSearchSnapshot(
                        entity.getRagTopkShort(),
                        entity.getRagTopkMedium(),
                        entity.getRagTopkLong(),
                        entity.getRagMinScoreShort(),
                        entity.getRagMinScoreMedium(),
                        entity.getRagMinScoreLong()
                )
        );
    }

    /**
     * 返回系统默认配置
     * 简历权重：项目经验40%、技能匹配20%、内容质量15%、结构清晰度15%、表达能力10%
     * 面试题目：简历题60%、方向题40%，温度均为0.2
     * 知识库搜索：短问题Top20(相似度0.18)、中等问题Top12(相似度0.23)、长问题Top8(相似度0.28)
     */
    private SystemAiSettingsSnapshot defaultSnapshot() {
        return new SystemAiSettingsSnapshot(
                new ResumeWeightsSnapshot(40, 20, 15, 15, 10),
                new InterviewSnapshot(60, 40, 0.4, 0.6, 0.2, 0.5),
                new RagSearchSnapshot(20, 12, 8, 0.18, 0.23, 0.28)
        );
    }

    /**
     * 系统AI配置快照（不可变）
     */
    public record SystemAiSettingsSnapshot(
            ResumeWeightsSnapshot resumeWeights, // 简历评分权重
            InterviewSnapshot interview, // 面试题目生成参数
            RagSearchSnapshot ragSearch // 知识库搜索参数
    ) {
    }

    /**
     * 简历评分权重快照
     */
    public record ResumeWeightsSnapshot(
            int projectWeight, // 项目经验权重
            int skillMatchWeight, // 技能匹配权重
            int contentWeight, // 内容质量权重
            int structureWeight, // 结构清晰度权重
            int expressionWeight // 表达能力权重
    ) {
    }

    /**
     * 面试题目生成参数快照
     */
    public record InterviewSnapshot(
            int resumeQuestionRatio, // 简历题目占比
            int directionQuestionRatio, // 方向题目占比
            double questionTemperature, // 题目生成温度（0-2，越低越确定）
            double followUpTemperature, // 追问生成温度
            double scoringTemperature, // 评分温度
            double commentTemperature // 评语生成温度
    ) {
    }

    /**
     * 知识库搜索参数快照
     */
    public record RagSearchSnapshot(
            int topkShort, // 短问题返回TopK数量
            int topkMedium, // 中等问题返回TopK数量
            int topkLong, // 长问题返回TopK数量
            double minScoreShort, // 短问题最低相似度阈值
            double minScoreMedium, // 中等问题最低相似度阈值
            double minScoreLong // 长问题最低相似度阈值
    ) {
    }
}