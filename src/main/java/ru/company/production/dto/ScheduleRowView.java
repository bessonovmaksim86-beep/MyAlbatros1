package ru.company.production.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Строка предварительного графика заказа покупателя.
 *
 * VIEW, а не сущность: предпросмотр показывается до сохранения, и
 * вёрстка не должна зависеть от того, лежит строка в базе или только
 * что посчитана.
 *
 * @param itemPosition   номер позиции заказа
 * @param instanceIndex  номер экземпляра внутри позиции (0-based)
 * @param instanceLabel  подпись экземпляра для таблиц: «1/10»
 * @param classifierCode код изделия
 * @param itemName       наименование изделия
 * @param techProcessCode код техпроцесса
 * @param seq            порядок операции в маршруте
 * @param operationCode  шифр операции
 * @param operationName  наименование операции
 * @param unitId         подразделение — хозяин операции
 * @param unitName       наименование подразделения
 * @param laborHours     трудоёмкость на экземпляр, человеко-часы
 * @param plannedDate    назначенная дата
 * @param loadPercent    загрузка подразделения в этот день по итогам расчёта
 * @param note           пояснение расчёта или причина сдвига
 */
public record ScheduleRowView(
        int itemPosition,
        int instanceIndex,
        String instanceLabel,
        Integer classifierCode,
        String itemName,
        String techProcessCode,
        int seq,
        String operationCode,
        String operationName,
        Long unitId,
        String unitName,
        BigDecimal laborHours,
        LocalDate plannedDate,
        Integer loadPercent,
        String note
) {

    private static final DateTimeFormatter RU_DATE =
            DateTimeFormatter.ofPattern("dd.MM.yyyy");

    /** Дата для таблиц: диалект Thymeleaf java8time не подключён. */
    public String formattedDate() {
        return plannedDate == null ? "—" : plannedDate.format(RU_DATE);
    }

    /** Трудоёмкость без хвостовых нулей: «1», а не «1.000». */
    public String formattedHours() {
        if (laborHours == null) {
            return "—";
        }

        return laborHours.stripTrailingZeros().toPlainString();
    }

    /** Загрузка дня в процентах, если расчёт её определял. */
    public String formattedLoad() {
        return loadPercent == null ? "—" : loadPercent + "%";
    }
}