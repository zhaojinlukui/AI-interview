package interview.guide.modules.user.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("个人中心统计服务")
class ProfileServiceTest {

  @Nested
  @DisplayName("薄弱项类别归一")
  class NormalizeCategory {

    @Test
    @DisplayName("英文 follow-up 类别归入原始类别")
    void shouldGroupEnglishFollowUpCategoryIntoBaseCategory() {
      assertThat(ProfileService.normalizeCategory("MySQL follow-up 1")).isEqualTo("MySQL");
      assertThat(ProfileService.normalizeCategory("MySql follow_up 2")).isEqualTo("MySql");
      assertThat(ProfileService.normalizeCategory("Redis follow up 3")).isEqualTo("Redis");
    }

    @Test
    @DisplayName("中文追问类别归入原始类别")
    void shouldGroupChineseFollowUpCategoryIntoBaseCategory() {
      assertThat(ProfileService.normalizeCategory("MySQL 追问1")).isEqualTo("MySQL");
      assertThat(ProfileService.normalizeCategory("Spring 追问 2")).isEqualTo("Spring");
    }

    @Test
    @DisplayName("空类别归入综合能力")
    void shouldUseDefaultCategoryWhenCategoryIsBlank() {
      assertThat(ProfileService.normalizeCategory(null)).isEqualTo("综合能力");
      assertThat(ProfileService.normalizeCategory("   ")).isEqualTo("综合能力");
      assertThat(ProfileService.normalizeCategory("follow-up 1")).isEqualTo("综合能力");
    }
  }
}
