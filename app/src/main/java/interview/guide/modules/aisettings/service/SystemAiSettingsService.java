package interview.guide.modules.aisettings.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.infrastructure.mapper.AiSettingsMapper;
import interview.guide.modules.aisettings.dto.SystemAiParametersDTO;
import interview.guide.modules.aisettings.dto.SystemAiParametersDTO.InterviewParametersDTO;
import interview.guide.modules.aisettings.dto.SystemAiParametersDTO.RagSearchParametersDTO;
import interview.guide.modules.aisettings.dto.SystemAiParametersDTO.ResumeWeightsDTO;
import interview.guide.modules.aisettings.model.SystemAiSettingsEntity;
import interview.guide.modules.aisettings.repository.SystemAiSettingsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 系统AI配置管理服务
 * 负责系统级AI全局参数的查询与更新，
 * 包括简历评分权重、面试题目生成参数和知识库搜索参数，
 * 更新时进行完整性校验和数值范围校验，通过Mapper统一进行DTO与实体的转换
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SystemAiSettingsService {

    private final SystemAiSettingsRepository settingsRepository; // 系统AI配置数据访问层
    private final SystemAiSettingsResolver settingsResolver; // 系统AI配置解析器（含默认值和缓存）
    private final AiSettingsMapper aiSettingsMapper; // AI配置映射器

    /**
     * 获取系统AI全局参数
     * 从缓存/数据库中解析并返回当前生效的配置
     */
    public SystemAiParametersDTO getParameters() {
        return aiSettingsMapper.toSystemAiParametersDTO(settingsResolver.resolve());
    }

    /**
     * 更新系统AI全局参数
     * 先校验参数完整性和取值范围，再通过Mapper统一更新实体字段
     */
    @Transactional
    public void updateParameters(SystemAiParametersDTO request) {
        validate(request);
        // 获取现有配置或使用默认配置作为基础
        SystemAiSettingsEntity entity = settingsRepository.findById(SystemAiSettingsEntity.SINGLETON_ID)
                .orElseGet(settingsResolver::buildDefaultEntity);

        // 通过Mapper将请求参数映射到实体
        aiSettingsMapper.updateSystemAiSettingsEntity(request, entity);
        settingsRepository.save(entity);
        log.info("系统AI参数已更新");
    }

    /**
     * 校验请求参数的完整性和取值范围
     * 包括：所有模块参数不为空、权重总和为100、各字段在合法范围内
     */
    private void validate(SystemAiParametersDTO request) {
        // 检查三大模块参数是否完整
        if (request == null || request.resumeWeights() == null
                || request.interview() == null || request.ragSearch() == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "系统AI参数必须完整填写");
        }

        // 校验简历评分权重：各项在0-100之间且总和为100
        ResumeWeightsDTO resume = request.resumeWeights();
        validateIntRange(resume.projectWeight(), 0, 100, "项目经验权重");
        validateIntRange(resume.skillMatchWeight(), 0, 100, "技能匹配权重");
        validateIntRange(resume.contentWeight(), 0, 100, "内容质量权重");
        validateIntRange(resume.structureWeight(), 0, 100, "结构清晰度权重");
        validateIntRange(resume.expressionWeight(), 0, 100, "表达能力权重");
        int resumeTotal = resume.projectWeight()
                + resume.skillMatchWeight()
                + resume.contentWeight()
                + resume.structureWeight()
                + resume.expressionWeight();
        if (resumeTotal != 100) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "简历评分权重总和必须为100");
        }

        // 校验面试题目参数：比例总和为100，温度在0.0-2.0之间
        InterviewParametersDTO interview = request.interview();
        validateIntRange(interview.resumeQuestionRatio(), 0, 100, "简历题目比例");
        validateIntRange(interview.directionQuestionRatio(), 0, 100, "方向题目比例");
        if (interview.resumeQuestionRatio() + interview.directionQuestionRatio() != 100) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "面试题目比例总和必须为100");
        }
        validateDoubleRange(interview.questionTemperature(), 0.0, 1.0, "题目生成温度");
        validateDoubleRange(interview.followUpTemperature(), 0.0, 1.0, "追问生成温度");
        validateDoubleRange(interview.scoringTemperature(), 0.0, 1.0, "评分温度");
        validateDoubleRange(interview.commentTemperature(), 0.0, 1.0, "评语生成温度");

        // 校验知识库搜索参数
        RagSearchParametersDTO rag = request.ragSearch();
        validateIntRange(rag.topkShort(), 1, 50, "短问题TopK");
        validateIntRange(rag.topkMedium(), 1, 50, "中等问题TopK");
        validateIntRange(rag.topkLong(), 1, 50, "长问题TopK");
        validateDoubleRange(rag.minScoreShort(), 0.0, 1.0, "短问题最低相似度");
        validateDoubleRange(rag.minScoreMedium(), 0.0, 1.0, "中等问题最低相似度");
        validateDoubleRange(rag.minScoreLong(), 0.0, 1.0, "长问题最低相似度");
    }

    /**
     * 校验整数值是否在指定范围内
     */
    private void validateIntRange(Integer value, int min, int max, String name) {
        if (value == null || value < min || value > max) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, name + "必须在" + min + "到" + max + "之间");
        }
    }

    /**
     * 校验浮点数值是否在指定范围内
     */
    private void validateDoubleRange(Double value, double min, double max, String name) {
        if (value == null || value < min || value > max) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, name + "必须在" + min + "到" + max + "之间");
        }
    }
}