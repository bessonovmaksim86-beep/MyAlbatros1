package ru.company.production.dto;

import java.util.List;

/**
 * Вид ячейки в карточке комплектования группового ЗНП на приборы.
 *
 * Технолог в операции маршрута задаёт контролируемые параметры
 * «Ячейка №» — по строке на каждый вид ячейки, входящий в прибор
 * (см. {@code TechProcessOperationControlParam.cellClassifier}).
 * Если один вид повторён в операции несколько раз, в один прибор
 * входит столько ячеек этого вида, сколько повторов.
 *
 * Требуемое количество на весь заказ = ячеек вида на один прибор
 * × число приборов заказа. Склад вносит номера ячеек этого вида с
 * полки; внесённый состав читается матрицей {@code SerialProductionOrder}
 * того же заказа, свободный остаток — складским перечнем по классификатору вида.
 *
 * @param classifierId       идентификатор классификатора вида ячейки
 * @param classifierCode     4-значный код вида (префикс полного номера)
 * @param classifierName     наименование вида ячейки
 * @param perInstrument      ячеек этого вида на один прибор (число повторов в операции)
 * @param requiredTotal      ячеек этого вида на весь заказ (perInstrument × приборов)
 * @param attachedCount      номеров этого вида уже внесено в заказ
 * @param attachedNumbers    внесённые полные номера этого вида
 * @param freeCount          свободных ячеек этого вида на складе
 * @param freeNumbers        свободные полные номера этого вида (для подсказки)
 */
public record InstrumentCellKindRow(
        Long classifierId,
        Integer classifierCode,
        String classifierName,
        int perInstrument,
        long requiredTotal,
        long attachedCount,
        List<String> attachedNumbers,
        long freeCount,
        List<String> freeNumbers
) {

    /** Вид укомплектован: внесено требуемое на заказ количество. */
    public boolean completed() {
        return requiredTotal > 0 && attachedCount >= requiredTotal;
    }

    /** Вносить нечего: требуемое уже внесено (или заказано ноль). */
    public boolean nothingToAdd() {
        return completed();
    }

    /** Подпись «внесено/требуется» для строки вида. */
    public String readinessLabel() {
        return attachedCount + " из " + requiredTotal;
    }
}
