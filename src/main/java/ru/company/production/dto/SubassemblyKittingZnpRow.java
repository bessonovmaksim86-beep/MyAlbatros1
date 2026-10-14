package ru.company.production.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Строка списка комплектования подсборок: ЗНП, подсборки которого
 * принимает склад полуфабрикатов.
 *
 * Операцию приёмки раздел ищет у заказа (тип RECEIVING) либо в его
 * техпроцессе (тип KITTING_SUBASSEMBLY) — в обоих случаях она обязана
 * относиться к подразделению «Склад полуфабрикатов». Кладовщик открывает
 * заказ и вводит номера ячеек, которые есть на складе.
 *
 * Техпроцесс показан вместо родителя: групповой ЗНП заводят без выпуска
 * (V38), и колонка «Родитель» была бы в его строке прочерком, тогда как
 * маршрут заказа кладовщику полезен — по нему сверяют операцию приёмки.
 */
public record SubassemblyKittingZnpRow(
        Long id,
        String number,
        String znpKindDisplayName,
        String productTypeName,
        String techProcessCode,
        String operationName,
        String formattedOrderDate,
        String formattedDueDate,
        String statusDisplayName,
        String statusBadgeClass,
        String note,
        BigDecimal requiredQuantity,
        List<SubassemblyKittingCellRow> cells,
        List<SubassemblyKittingCellRow> attached
) {

    /*
     * Сколько ячеек уже оформлено в заказ.
     *
     * Считается по внесённому составу, а не по складскому перечню:
     * проведённая в ЗНП ячейка со склада снята и в свободном остатке
     * не числится, поэтому «взято» меряется матрицей заказа.
     */
    public long doneCount() {
        return attached == null ? 0L : attached.size();
    }

    /*
     * Сколько ячеек заказано.
     *
     * Готовность меряют заказом, а не складским остатком: на складе
     * лежит тридцать подсборок, а в заказ их входит пять, и «взято 2 из
     * 30» кладовщик прочёл бы как недоукомплектованный заказ.
     *
     * Количество задают только групповым и ремонтным видам. У заказа на
     * комплектования количества нет — там знаменателем остаётся всё,
     * что по этому изделию побывало на складе: свободный остаток плюс
     * уже оформленное в заказ (проведённую ячейку склад уже не показывает).
     */
    public long requiredCount() {
        return requiredQuantity == null
                ? doneCount() + availableCount()
                : requiredQuantity.setScale(0, java.math.RoundingMode.CEILING)
                .longValue();
    }

    /** Сколько свободных ячеек лежит на складе. */
    public long availableCount() {
        return cells == null ? 0L : cells.size();
    }

    /** Подпись готовности: «Взято X из Y». */
    public String readinessLabel() {
        return "Взято " + doneCount() + " из " + requiredCount();
    }

    /** Заказ укомплектован: взято заказанное количество. */
    public boolean completed() {
        long required = requiredCount();
        return required > 0 && doneCount() >= required;
    }
}