package ru.company.production.dto;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Операция ячейки в составе группового ЗНП — строка раскрытия ячейки.
 *
 * Ячейка проходит маршрут заказа (V40), и каждая её работа — строка
 * плана (V56) со своей датой, трудоёмкостью и исполнителем. Назначение
 * ставится именно на строку плана: мастер закрепляет работу над
 * конкретной ячейкой, а не над всем заказом, — десять ячеек одного
 * заказа вправе вести десять человек.
 *
 * @param rowId          строка плана — ключ назначения и снятия
 * @param operationId    операция справочника — подписывает выбор в форме
 * @param operationName  наименование операции
 * @param plannedDate    дата плана этой работы
 * @param hours          трудоёмкость на ячейку
 * @param assigned       за работой уже закреплён исполнитель
 * @param executorId     идентификатор закреплённого исполнителя
 * @param executorName   его фамилия для подписи
 * @param completed      работа проведена
 * @param completedLabel подпись даты проведения
 * @param executors      способные сотрудники с загрузкой на дату плана
 */
public record GroupZnpOperationRow(
        Long rowId,
        Long operationId,
        String operationName,
        LocalDate plannedDate,
        BigDecimal hours,
        boolean assigned,
        Long executorId,
        String executorName,
        boolean completed,
        String completedLabel,
        List<AssignableExecutor> executors
) {

    private static final DateTimeFormatter RU_DATE =
            DateTimeFormatter.ofPattern("dd.MM.yyyy");

    /** Наименование операции — подпись колонки. */
    public String title() {
        return operationName == null || operationName.isBlank()
                ? "операция " + operationId
                : operationName.trim();
    }

    /** Дата плана: «23.10.2026». */
    public String plannedDateFormatted() {
        return plannedDate == null ? "—" : plannedDate.format(RU_DATE);
    }

    /** Трудоёмкость одной строкой: «1». */
    public String hoursLabel() {
        if (hours == null) {
            return "—";
        }

        return hours.setScale(1, RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString();
    }

    /** Ни один сотрудник не способен: блок рисуется с предупреждением. */
    public boolean hasNoExecutor() {
        return executors == null || executors.isEmpty();
    }
}
