package ru.company.production.dto;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Сводная строка операции группового ЗНП — блок пакетного назначения.
 *
 * Операция маршрута выполняется над каждой ячейкой заказа, и мастер
 * видит её одной сводной строкой: дата плана, трудоёмкость на всю
 * группу и перечень ячеек с чекбоксами. Чекбокс «выделить все»
 * собирает отмеченные ячейки в одно назначение, когда работу ведёт
 * один человек, а снятие отметки оставляет закреплённые только за
 * выбранными ячейками.
 *
 * @param operationId   операция справочника — подписывает форму
 * @param operationName её наименование
 * @param plannedDate   дата плана операции
 * @param hoursLabel    трудоёмкость на ячейку
 * @param totalHours    суммарная трудоёмкость всех ячеек операции
 * @param assignRows    ячейки операции — строки назначения
 * @param assignedCount из них уже закреплено за сотрудниками
 * @param executors     способные сотрудники с загрузкой на дату плана
 */
public record GroupZnpBatchRow(
        Long operationId,
        String operationName,
        LocalDate plannedDate,
        String hoursLabel,
        java.math.BigDecimal totalHours,
        List<GroupZnpAssignRow> assignRows,
        long assignedCount,
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
    public String formattedDate() {
        return plannedDate == null ? "—" : plannedDate.format(RU_DATE);
    }

    /** Сколько ячеек охвачено операцией. */
    public long totalCount() {
        return assignRows == null ? 0L : assignRows.size();
    }

    /** Есть ли ячейки, которые ещё не закреплены. */
    public boolean hasUnassigned() {
        return assignedCount < totalCount();
    }

    /** Ни один сотрудник не способен: блок рисуется с предупреждением. */
    public boolean hasNoExecutor() {
        return executors == null || executors.isEmpty();
    }

    /** Подпись распределения для бейджа операции. */
    public String distributionLabel() {
        long total = totalCount();

        if (total == 0) {
            return "нет ячеек";
        }

        if (assignedCount == 0) {
            return "распределить";
        }

        return assignedCount == total ? "распределено" : "частично";
    }

    /** Цвет бейджа операции — тот же ряд, что у бейджа заказа. */
    public String distributionBadgeClass() {
        return GroupZnpBadge.of(distributionLabel());
    }

    /** Суммарная трудоёмкость без хвостовых нулей: «5». */
    public String totalHoursLabel() {
        if (totalHours == null) {
            return "—";
        }

        return totalHours.setScale(1, java.math.RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString();
    }
}