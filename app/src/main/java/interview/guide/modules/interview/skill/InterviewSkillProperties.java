package interview.guide.modules.interview.skill;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class InterviewSkillProperties {

    /**
     * SKILL.md 描述字段
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SkillFrontMatterDefinition {
        private String name;
        private String description;
    }

    /**
     * skill.meta.yml
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SkillMetaDefinition {
        private String displayName;
        private DisplayDef display;
        private List<CategoryDef> categories = new ArrayList<>();
    }

    /**
     * SKILL.md ＋ skill.meta.yml
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SkillDefinition {
        private String name;
        private String description;
        private String persona;

        private String displayName;
        private DisplayDef display;
        private List<CategoryDef> categories = new ArrayList<>();
    }

    // skill.meta.yml 前端展示模型
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DisplayDef {
        private String icon;
        private String gradient;
        private String iconBg;
        private String iconColor;
    }

    // skill.meta.yml 分类部分
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CategoryDef {
        private String key;
        private String label;
        private String priority;
        private String ref;
        private Boolean shared;
    }
}
