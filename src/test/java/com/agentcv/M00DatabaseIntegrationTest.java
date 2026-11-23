package com.agentcv;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("m00")
@EnabledIfEnvironmentVariable(named = "AGENTCV_M00_DB_PASSWORD", matches = ".+")
class M00DatabaseIntegrationTest {
    @Autowired JdbcTemplate db;
    @Autowired Flyway flyway;

    @Test void isolatedMigrationIsRepeatable() {
        assertThat(db.queryForObject("select current_database()", String.class)).isEqualTo("agentcv_m00");
        assertThat(db.queryForObject("select current_user", String.class)).isEqualTo("agentcv_m00_app");
        assertThat(db.queryForObject("select current_schema()", String.class)).isEqualTo("m00_app");
        assertThat(db.queryForObject("select rolsuper from pg_roles where rolname=current_user", Boolean.class)).isFalse();
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(db.queryForObject("select marker from engineering_baseline where id=1", String.class))
                .isEqualTo("M00：隔离工程基线");
        assertThat(db.queryForObject("select count(*) from engineering_baseline", Integer.class)).isEqualTo(1);
    }
}