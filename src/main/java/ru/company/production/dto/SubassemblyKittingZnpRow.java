package ru.company.production.dto;

import java.util.List;

/**
 * Строка списка комплектования подсборок: заказ-комплектующий с операцией
 * приёмки на склад полуфабрикатов.
 *
 * Список строится по ЗНП, у которых комплектовочная операция имеет тип
 * RECEIVING («Приёмка на склад») и относится к подразделению
 * «Склад полуфабрикатов». Кладовщик открывает ЗНП и вводит номера
 * ячеек, которые есть на складе.
 */
public record SubassemblyKittingZnpRow(
        Long id,
        String number,
        String znpKindDisplayName,
        String parentOrderNumber,
        String operationName,
        String formattedOrderDate,
        String formattedDueDate,
        String statusDisplayName,
        String statusBadgeClass,
        String note,
        List<SubassemblyKittingCellRow> cells
) {

    /** Сколько ячеек взято — для карточки готовности. */
    public long doneCount() {
        return cells == null ? 0L : cells.stream()
                .filter(SubassemblyKittingCellRow::taken)
                .count();
    }

    /** Сколько всего ячеек на складе. */
    public long availableCount() {
        return cells == null ? 0L : cells.size();
    }

    /** Подпись готовности: «Взято X из Y». */
    public String readinessLabel() {
        long done = doneCount();
        long avail = availableCount();
        return "Взято " + done + " из " + avail;
    }

    /** Заказ укомплектован: все ячейки взяты. */
    public boolean completed() {
        long avail = availableCount();
        return avail > 0 && doneCount() >= avail;
    }
}
