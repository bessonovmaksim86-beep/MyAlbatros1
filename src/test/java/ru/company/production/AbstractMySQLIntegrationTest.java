package ru.company.production;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * База интеграционных тестов, которым нужна настоящая MySQL: миграции
 * Flyway, репозитории, запросы на нативном SQL.
 *
 * <p>Контейнер один на все тесты этого вида: {@code static}-поле живёт до
 * конца класса-наследника, а образ поднимается один раз. Миграций в
 * проекте больше восьмидесяти, и каждая новая БД означала бы полный их
 * прогон перед первым тестом.
 *
 * <p>Имя базы совпадает с боевой ({@code production_system}) намеренно:
 * {@code application.yml} задаёт {@code hibernate.default_catalog} и
 * {@code flyway.schemas} тем же именем, и тест обязан проверять ровно ту
 * конфигурацию, на которой приложение работает, а не её упрощённый
 * вариант.
 *
 * <p>{@code disabledWithoutDocker}: без Docker тесты пропускаются, а не
 * падают. Иначе {@code mvn test} на машине без Docker (или на машине,
 * где демон ещё не поднят) перестал бы собирать проект целиком, и
 * запускать сборку стало бы неудобно.
 *
 * <p>Проверить покрытие этих тестов JaCoCo не может, пока Docker не
 * установлен: агент фиксирует только реально выполненный код.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
public abstract class AbstractMySQLIntegrationTest {

    static final String DATABASE = "production_system";

    @SuppressWarnings("unused")
    private static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>("mysql:8.0")
                    .withDatabaseName(DATABASE)
                    .withCommand("--character-set-server=utf8mb4",
                            "--collation-server=utf8mb4_unicode_ci");

    /**
     * URL собирается от реального маппинга порта, а не из
     * {@code getJdbcUrl()}: второй вариант отдаёт {@code jdbc:tc:...} и
     * требует отдельного Testcontainers-драйвера, который молча
     * подменяет контейнер на пул — отладить падение миграции в таком
     * случае заметно труднее.
     */
    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        MYSQL.start();
        registry.add("spring.datasource.url", () -> "jdbc:mysql://"
                + MYSQL.getHost() + ":" + MYSQL.getMappedPort(3306)
                + "/" + DATABASE
                + "?useUnicode=true&characterEncoding=utf8"
                + "&serverTimezone=UTC&allowPublicKeyRetrieval=true");
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }
}
