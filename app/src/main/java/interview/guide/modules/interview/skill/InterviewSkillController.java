package interview.guide.modules.interview.skill;

import interview.guide.common.annotation.RateLimit;
import interview.guide.common.result.Result;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 面试方向管理控制器。
 */
@RestController
@RequestMapping("/api/interview/skills")
@RequiredArgsConstructor
public class InterviewSkillController {

    private final InterviewSkillService skillService;

    /**
     * 查询所有面试方向。
     *
     * @return 面试方向列表
     */
    @GetMapping
    public Result<List<InterviewSkillService.SkillDTO>> listSkills() {
        return Result.success(skillService.getAllSkills());
    }

    /**
     * 解析岗位描述文本。
     *
     * @param request 岗位描述解析请求
     * @return 匹配的面试方向分类
     */
    @PostMapping("/parse-jd")
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 5)
    public Result<List<InterviewSkillService.CategoryDTO>> parseJd(
            @Valid @RequestBody ParseJdRequest request
    ) {
        return Result.success(skillService.parseJd(request.jdText()));
    }

    /**
     * 岗位描述解析请求体。
     *
     * @param jdText 岗位描述文本
     */
    public record ParseJdRequest(@NotBlank String jdText) {}
}
