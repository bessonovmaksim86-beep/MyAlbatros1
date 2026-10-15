package ru.company.production.dto;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Строка очереди распределения: одна операция одного дня плана.
 *
 * Единица показа — пара «операция + дата»: мастер читает список как
 * перечень работ, видит объём и часы, а решение о человеке принимает в
 * раскрытии, где блок отдан каждому датчику. Дата, заказ, серийник и
 * наименование изделия живут в блоке — там, где решение принимается, и
 * в строке таблицы они только спорили бы с соседними строками, у которых
 * заказов и датчиков несколько.
 *
 * @param operationId   операция справочника — часть ключа назначения
 * @param operationName наименование операции
 * @param plannedDate   дата плана группы
 * @param rowCount      сколько датчиков в группе
 * @param totalHours    суммарная трудоёмкость группы, человеко-часы
 * @param executors     сотрудники, способные выполнить операцию
 * @param items         датчики операции — по блоку на каждый экземпляр
 * @param assignedCount сколько датчиков уже закреплено за исполнителями
 */
public record OperationDistributionView(
        Long operationId,
        String operationName,
        LocalDate plannedDate,
        long rowCount,
        BigDecimal totalHours,
        List<AssignableExecutor> executors,
        List<DistributionItemCard> items,
        long assignedCount
) {

    /** Подпись строки: «Изготовление штанг». */
    public String title() {
        return operationName == null || operationName.isBlank()
                ? "операция " + operationId
                : operationName.trim();
    }

    /** Ни один сотрудник не способен: строка рисуется с предупреждением. */
    public boolean hasNoExecutor() {
        return executors == null || executors.isEmpty();
    }

    /**
     * Объём работы одной подписью: «5 датч. · 28 ч.».
     *
     * Строка обязана отвечать на вопрос «сколько работы», а не
     * «над чем именно»: датчики перечислены в раскрытии.
     */
    public String volumeLabel() {
        String hours = totalHours == null
                ? "—"
                : totalHours.setScale(1, RoundingMode.HALF_UP)
                        .stripTrailingZeros()
                        .toPlainString();

        return rowCount + " датч. · " + hours + " ч.";
    }

    /** Есть ли хотя бы один незакреплённый датчик. */
    public boolean hasUnassigned() {
        return items != null && assignedCount < items.size();
    }

    /**
     * Подпись статуса для бейджа строки.
     *
     * Считается по датчикам, а не по строкам плана: блок — один датчик,
     * поэтому «частично» означает «одни датчики операции уже у
     * сотрудников, другие ещё нет».
     */
    public String statusBadge() {
        if (items == null || items.isEmpty()) {
            return "нет датчиков";
        }

        if (assignedCount == 0) {
            return "распределить";
        }

        return assignedCount == items.size()
                ? "распределено"
                : "частично";
    }

    /** Исполнители группы одной строкой — колонка распределённых работ. */
    public String executorsLabel() {
        if (items == null) {
            return null;
        }

        List<String> names = new ArrayList<>();

        for (DistributionItemCard item : items) {
            String name = item.executorLabel();

            if (name != null && !name.isBlank() && !names.contains(name)) {
                names.add(name.trim());
            }
        }

        if (names.isEmpty()) {
            return null;
        }

        return names.size() > 2
                ? String.join(", ", names.stream().limit(2).toList())
                        + " … всего " + names.size()
                : String.join(", ", names);
    }
}
