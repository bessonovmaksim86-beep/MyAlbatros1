package ru.company.production.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.company.production.config.OneCProperties;
import ru.company.production.dto.OneCZpRow;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Чтение заказов из базы 1С через JDBC.
 *
 * Соединение открывается на время одного запроса и закрывается сразу
 * после чтения. Это не бин-пул, не JPA-кэш и не фоновая задача:
 * приложение не держит соединения между запросами и не влияет
 * на нагрузку 1С.
 *
 * Запрос задаётся в {@code application.yml} переменной
 * {@code application.onec.zp-query}: таблицы и имена колонок
 * в базе 1С формируются автоматически при загрузке конфигурации,
 * и заранее узнать их невозможно.
 *
 * Если 1С недоступна или запрос возвращает ошибку, сервис пишет
 * сообщение в лог и возвращает пустой список — интерфейс работает
 * как обычно, показывая данные из собственной базы.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OneCJdbcClient {

    /** 1С выключена в настройках — ничего не пытаемся делать. */
    public static final int STATUS_DISABLED = 0;

    /** Соединение установлено, данных нет (пустой результат). */
    public static final int STATUS_EMPTY = 1;

    /** Соединение установлено, данные загружены успешно. */
    public static final int STATUS_OK = 2;

    /** Ошибка подключения или запроса — 1С недоступна. */
    public static final int STATUS_ERROR = 3;

    private final OneCProperties oneCProperties;

    /**
     * Выгружает ЗП из базы 1С.
     *
     * @param from начало периода (включительно)
     * @param to   конец периода (включительно)
     * @return результат с кодом состояния и списком строк
     */
    public OneCLoadResult loadZp(LocalDate from, LocalDate to) {
        if (!oneCProperties.isConfigured()) {
            return new OneCLoadResult(STATUS_DISABLED, List.of());
        }

        String query = buildQuery();
        log.info("Запрос к базе 1С: {}", truncate(query, 200));

        try (Connection conn = DriverManager.getConnection(
                oneCProperties.getUrl(),
                oneCProperties.getUsername(),
                oneCProperties.getPassword()
        )) {
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(query)) {

                List<OneCZpRow> rows = new ArrayList<>();

                while (rs.next()) {
                    rows.add(mapRow(rs));
                }

                /*
                 * Фильтруем по периоду на Java: структура 1С неизвестна,
                 * а подставлять даты в чужой SQL — хрупко и небезопасно.
                 */
                List<OneCZpRow> filtered = filterByPeriod(rows, from, to);

                if (filtered.isEmpty() && rows.isEmpty()) {
                    return new OneCLoadResult(STATUS_EMPTY, List.of());
                }

                if (filtered.isEmpty()) {
                    log.info("Заказы из 1С найдены, но все вне периода: {} строк", rows.size());
                    return new OneCLoadResult(STATUS_EMPTY, List.of());
                }

                log.info("Получено записей из 1С (после фильтра): {}", filtered.size());
                return new OneCLoadResult(STATUS_OK, filtered);
            }
        } catch (Exception ex) {
            log.error("Ошибка чтения из базы 1С [{}]: {}",
                    oneCProperties.getUrl(), ex.getMessage());
            return new OneCLoadResult(STATUS_ERROR, List.of());
        }
    }

    /**
     * Формирует SQL-запрос.
     *
     * Если в настройках задан пользовательский запрос — возвращает его.
     * Иначе — стандартный запрос к справочнику документов «Заказы на
     * производство». Имена таблиц и колонок нужно будет уточнить после
     * просмотра структуры базы 1С.
     */
    private String buildQuery() {
        String userQuery = oneCProperties.getZpQuery();

        if (userQuery != null && !userQuery.isBlank()) {
            return userQuery;
        }

        /*
         * Запрос по умолчанию: предполагается, что 1С хранит заказы
         * в справочнике документов с полями «Номер», «Дата», «Контрагент».
         * Требуется ровно пять колонок в таком порядке.
         */
        return "SELECT \"_document_number\", "
                + "\"_document_date\", "
                + "\"_customer_order_number\", "
                + "\"_customer_name\", "
                + "\"_status\" "
                + "FROM \"_production_orders\" "
                + "ORDER BY \"_document_date\" DESC, \"_document_number\" DESC "
                + "LIMIT " + oneCProperties.getMaxRows();
    }

    /**
     * Фильтрует строки по дате на Java.
     *
     * Фильтр включается, если оба края периода заданы. Если задан
     * только один край — все строки пропускаются.
     */
    private List<OneCZpRow> filterByPeriod(List<OneCZpRow> rows,
                                           LocalDate from, LocalDate to) {
        if (from == null && to == null) {
            return rows;
        }

        List<OneCZpRow> result = new ArrayList<>();

        for (OneCZpRow row : rows) {
            if (row.orderDate() == null) {
                continue;
            }

            if (from != null && row.orderDate().isBefore(from)) {
                continue;
            }

            if (to != null && row.orderDate().isAfter(to)) {
                continue;
            }

            result.add(row);
        }

        return result;
    }

    /**
     * Маппинг строки результата в DTO.
     *
     * Колонки ожидаются в порядке, заданном в запросе:
     * 1) номер   2) дата  3) заказ покупателя  4) клиент  5) статус
     */
    private OneCZpRow mapRow(ResultSet rs) throws Exception {
        String number = rs.getString(1);
        LocalDate orderDate = null;

        try {
            orderDate = LocalDate.parse(rs.getString(2));
        } catch (Exception ignored) {
            // нечитаемая дата — не ошибка, строка всё равно показывается
        }

        String customerOrderNumber = rs.getString(3);
        String customerName = rs.getString(4);
        String status = rs.getString(5);

        return new OneCZpRow(number, orderDate, customerOrderNumber,
                customerName, status);
    }

    private static String truncate(String s, int maxLen) {
        return s.length() <= maxLen
                ? s
                : s.substring(0, maxLen) + "...";
    }

    /**
     * Результат загрузки из 1С.
     */
    public record OneCLoadResult(
            int status,
            List<OneCZpRow> rows
    ) {
        public boolean isOk() {
            return status == STATUS_OK;
        }

        public boolean isEmpty() {
            return status == STATUS_EMPTY;
        }

        public boolean isError() {
            return status == STATUS_ERROR;
        }

        public boolean isDisabled() {
            return status == STATUS_DISABLED;
        }
    }
}
