package interview.guide.common.ai;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springaicommunity.agent.tools.SkillsTool;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

/**
 * Agent工具配置
 * 接入SkillsTool，复用resources/skills/{skillId}/SKILL.md技能定义文件，
 * 自动规范化技能根路径，支持classpath和文件系统两种资源位置
 */
@Configuration
@Slf4j
@RequiredArgsConstructor
public class AgentUtilsConfiguration {

    private final ResourceLoader resourceLoader; // 资源加载器
    private final AgentUtilsProperties agentUtilsProperties; // Agent工具配置属性

    /**
     * 创建面试技能工具回调Bean
     * 从配置的技能根目录加载所有SKILL.md文件，
     * 路径不存在时抛出异常提示检查配置
     */
    @Bean("interviewSkillsToolCallback")
    public ToolCallback interviewSkillsToolCallback() {
        String configuredSkillsRoot = agentUtilsProperties.getSkillsRoot();
        String normalizedSkillsRoot = normalizeSkillsRoot(configuredSkillsRoot);
        Resource skillsRootResource = resourceLoader.getResource(normalizedSkillsRoot);

        if (!skillsRootResource.exists()) {
            throw new IllegalStateException("未找到 skills 根目录，请检查配置: " + normalizedSkillsRoot);
        }

        log.info("AgentUtils SkillsTool 已启用，skillsRoot={}, configured={}", normalizedSkillsRoot, configuredSkillsRoot);

        return SkillsTool.builder()
                .addSkillsResource(skillsRootResource)
                .build();
    }

    /**
     * 规范化技能根路径
     * 处理以下情况：
     * - 空值默认使用classpath:skills
     * - 去除尾部/SKILL.md
     * - 去除通配符*
     * - 去除尾部斜杠
     * - 统一使用正斜杠
     */
    private String normalizeSkillsRoot(String raw) {
        if (raw == null || raw.isBlank()) {
            return "classpath:skills";
        }

        String normalized = raw.trim();
        // 统一路径分隔符
        normalized = normalized.replace('\\', '/');

        // 去除尾部/SKILL.md
        if (normalized.endsWith("/SKILL.md")) {
            normalized = normalized.substring(0, normalized.length() - "/SKILL.md".length());
        }

        // 去除通配符
        int wildcardIndex = normalized.indexOf('*');
        if (wildcardIndex >= 0) {
            normalized = normalized.substring(0, wildcardIndex);
        }

        // 去除尾部斜杠
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }

        return normalized.isBlank() ? "classpath:skills" : normalized;
    }
}