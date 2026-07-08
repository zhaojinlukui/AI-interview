package interview.guide.modules.interviewschedule.service;

import jakarta.annotation.PostConstruct;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class InterviewScheduleSchemaRepair {

    private static final String POSTGRESQL = "postgresql";
    private static final String DROP_STATUS_CHECK = """
      ALTER TABLE interview_schedule
      DROP CONSTRAINT IF EXISTS interview_schedule_status_check
      """;
    private static final String ADD_STATUS_CHECK = """
      ALTER TABLE interview_schedule
      ADD CONSTRAINT interview_schedule_status_check
      CHECK (status IN ('PENDING', 'COMPLETED', 'CANCELLED', 'EXPIRED'))
      """;

    private final JdbcTemplate jdbcTemplate;
    private final DataSource dataSource;

    @PostConstruct
    public void repairInterviewScheduleStatusConstraint() {
        if (!isPostgreSql()) {
            return;
        }
        try {
            jdbcTemplate.execute(DROP_STATUS_CHECK);
            jdbcTemplate.execute(ADD_STATUS_CHECK);
            log.info("面试安排状态检查约束已修复");
        } catch (Exception e) {
            log.warn("修复面试安排状态检查约束失败", e);
        }
    }

    private boolean isPostgreSql() {
        try (Connection connection = dataSource.getConnection()) {
            String productName = connection.getMetaData().getDatabaseProductName();
            return productName != null && productName.toLowerCase().contains(POSTGRESQL);
        } catch (SQLException e) {
            log.warn("无法检测用于计划约束修复的数据库产品", e);
            return false;
        }
    }
}
