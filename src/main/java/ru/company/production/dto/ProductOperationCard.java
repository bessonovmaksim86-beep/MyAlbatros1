package ru.company.production.dto;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Одна операция изделия (датчика) — блок внутри панели раскрытия
 * строки-изделия.
 *
 * В группе «Операции для распределения» каждая строка таблицы —
 * конкретный датчик. Когда мастер раскрывает строку, он видит все
 * операции, которые цех должен выполнить над этим изделием.
 *
 * @param operationId   операция справочника — ключ назначения
 * @param operationName наименование операции
 * @param plannedDate   дата плана группы
 * @param totalHours    суммарная трудоёмкость группы
 * @param orderNumbers  заказы покупателя, которым принадлежат строки
 * @param rowCount      сколько экземпляров (изделий) в группе
 * @param assigned      операция уже закреплена за исполнителем
 * @param executors     сотрудники, способные выполнить операцию
 * @param instances     экземпляры операции: номер изделия и часы
 * @param serialNumbers серийные номера группы без повторов
 * @param executorLabel закреплённый исполнитель (для распределённых)
 */
public record ProductOperationCard(
        Long operationId,
        String operationName,
        LocalDate plannedDate,
        BigDecimal totalHours,
        List<String> orderNumbers,
        long rowCount,
        boolean assigned,
        List<AssignableExecutor> executors,
        List<OperationInstance> instances,
        List<String> serialNumbers,
        String executorLabel
) {

    private static final DateTimeFormatter RU_DATE =
            DateTimeFormatter.ofPattern("dd.MM.yyyy");

    /** Подпись операции: «Сборка узла». */
    public String title() {
        return operationName == null || operationName.isBlank()
                ? "операция " + operationId
                : operationName.trim();
    }

    /** Дата плана: «01.10.2026». */
    public String formattedDate() {
        return plannedDate == null ? "—" : plannedDate.format(RU_DATE);
    }

    /** Заказы одной строкой: «ЗП-12, ЗП-18». */
    public String ordersLabel() {
        return orderNumbers == null || orderNumbers.isEmpty()
                ? "—"
                : String.join(", ", orderNumbers);
    }

    /** Трудоёмкость группы: «16 ч.». */
    public String hoursLabel() {
        if (totalHours == null) {
            return "—";
        }

        return totalHours.setScale(1, RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString();
    }

    /** Ни один сотрудник не способен: блок рисуется с предупреждением. */
    public boolean hasNoExecutor() {
        return executors == null || executors.isEmpty();
    }

    /** Серийники группы: «121200022, 121200023». */
    public String serialsLabel() {
        if (serialNumbers == null || serialNumbers.isEmpty()) {
            return null;
        }

        String joined = String.join(", ",
                serialNumbers.stream().limit(4).toList());

        return serialNumbers.size() > 4
                ? joined + " … всего " + serialNumbers.size()
                : joined;
    }
}
