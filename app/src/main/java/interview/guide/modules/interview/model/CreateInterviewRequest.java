package interview.guide.modules.interview.model;

import interview.guide.modules.interview.skill.InterviewSkillService.CategoryDTO;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.util.List;

public record CreateInterviewRequest(
    String resumeText,                  // 简历文本
    @Min(value = 3, message = "题目数量最少 3 题")
    @Max(value = 20, message = "题目数量最多 20 题")
    int questionCount,                  // 题目数量
    Long resumeId,                      // 简历 ID
    Boolean forceCreate,                // 是否强制创建
    @NotBlank(message = "面试主题不能为空")
    String skillId,                     // 面试技能 ID
    String difficulty,                  // 面试难度
    List<CategoryDTO> customCategories, // 自定义面试分类
    String jdText                       // 岗位描述文本
) {
}
