package com.example.helpdesk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.sql.DriverManager;
import java.util.Properties;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class MigrationIsolationTest {
  @Test
  void businessMigrationsPreserveAnAlreadyPopulatedPythonSchema() throws Exception {
    String url =
        "jdbc:h2:mem:isolation_"
            + UUID.randomUUID()
            + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
    try (var connection = DriverManager.getConnection(url, "sa", "")) {
      connection
          .createStatement()
          .execute(
              "create table public.ai_existing_document (id integer primary key, content"
                  + " varchar(100))");
      connection
          .createStatement()
          .execute(
              "insert into public.ai_existing_document values (1, 'Preserve this Python data')");
    }

    Properties config = new Properties();
    try (var input = getClass().getResourceAsStream("/application.properties")) {
      config.load(input);
    }
    Flyway flyway =
        Flyway.configure()
            .dataSource(url, "sa", "")
            .defaultSchema(config.getProperty("spring.flyway.default-schema", "public"))
            .schemas(config.getProperty("spring.flyway.schemas", "public").split(","))
            .createSchemas(
                Boolean.parseBoolean(config.getProperty("spring.flyway.create-schemas", "true")))
            .table(config.getProperty("spring.flyway.table"))
            .locations("classpath:db/migration")
            .load();

    assertThatCode(flyway::migrate).doesNotThrowAnyException();
    try (var connection = DriverManager.getConnection(url, "sa", "")) {
      var foreignData =
          connection
              .createStatement()
              .executeQuery("select content from public.ai_existing_document where id=1");
      assertThat(foreignData.next()).isTrue();
      assertThat(foreignData.getString(1)).isEqualTo("Preserve this Python data");
      var business =
          connection.createStatement().executeQuery("select count(*) from hd_business.hd_users");
      assertThat(business.next()).isTrue();
      assertThat(business.getInt(1)).isZero();
      var accidentalPublicTables =
          connection
              .createStatement()
              .executeQuery(
                  "select count(*) from information_schema.tables where table_schema='PUBLIC' and"
                      + " table_name like 'HD_%'");
      accidentalPublicTables.next();
      assertThat(accidentalPublicTables.getInt(1)).isZero();
    }
    assertThatCode(flyway::migrate).doesNotThrowAnyException();
  }
}
