package ru.company.production.dto;

import java.util.List;

/**
 * Групповой ЗНП — главная строка раздела распределения.
 *
 * Руководитель цеха видит заказ и его состав: номер, изделие,
 * количество и маршрут (V40). Раскрытие показывает заказанные
 * экземпляры изделия — по одному на каждую строку графика (V56).
 *
 * Разрез обратен основному распределению: там мастер выбирает изделие
 * и его операции, а здесь работа заявлена группой ячеек одного
 * наименования, и думать мастер начинает с того, какие ячейки уже на
 * участке.
 *
 * @param id                  идентификатор заказа
 * @param number              номер ЗНП («ЗНП-2026-014»)
 * @param znpKindDisplayName  вид заказа («Групповой ЗНП на ячейки»)
 * @param productTypeName     тип заказанного изделия («Ячейка»)
 * @param itemLabel           изделие заказа: «код — наименование»
 * @param quantityLabel       заказанное количество без хвостовых нулей
 * @param techProcessCode     маршрут заказа (V40)
 * @param priorityDisplayName приоритет планирования (V41)
 * @param priorityBadgeClass  CSS-модификатор приоритета
 * @param statusDisplayName   статус заказа
 * @param statusBadgeClass    CSS-модификатор статуса
 * @param serials             экземпляры заказа с их операциями
 * @param batchRows           сводка по операциям — пакетное назначение
 */
public record GroupZnpView(
        Long id,
        String number,
        String znpKindDisplayName,
        String productTypeName,
        String itemLabel,
        String quantityLabel,
        String techProcessCode,
        String priorityDisplayName,
        String priorityBadgeClass,
        String statusDisplayName,
        String statusBadgeClass,
        List<GroupZnpSerialRow> serials,
        List<GroupZnpBatchRow> batchRows
) {

    /** Сколько ячеек охвачено раскрытием. */
    public long serialCount() {
        return serials == null ? 0L : serials.size();
    }

    /** Сколько работ заказа ещё не закреплено за людьми. */
    public long unassignedCount() {
        if (batchRows == null) {
            return 0L;
        }

        return batchRows.stream()
                .mapToLong(row -> row.totalCount() - row.assignedCount())
                .sum();
    }

    /** Есть ли что распределять — по этой цифре рисуют бейдж строки. */
    public boolean hasUnassigned() {
        return unassignedCount() > 0;
    }

    /** Подпись статуса строки: «распределить», «частично», «распределено». */
    public String distributionLabel() {
        long total = batchRows == null
                ? 0L
                : batchRows.stream().mapToLong(GroupZnpBatchRow::totalCount).sum();

        if (total == 0) {
            return "нет работ";
        }

        long assigned = total - unassignedCount();

        if (assigned == 0) {
            return "распределить";
        }

        return assigned == total ? "распределено" : "частично";
    }

    /**
     * Цвет бейджа распределения. Подпись и цвет сходятся в одном
     * месте: «частично» обязано оставаться жёлтым и в заказе, и в
     * операции, иначе мастер прочитает два одинаковых слова по-разному.
     */
    public String distributionBadgeClass() {
        return GroupZnpBadge.of(distributionLabel());
    }

    /**
     * Цвет бейджа статуса заказа: модификатор enum'а переведён в ряд
     * бейджей раздела (см. {@link GroupZnpBadge#ofOrderStatus}).
     */
    public String statusBadgeModifier() {
        return GroupZnpBadge.ofOrderStatus(statusBadgeClass);
    }

    /** Подпись состава: «8 яч. из 10 заказано». */
    public String compositionLabel() {
        long count = serialCount();

        if (count == 0) {
            return "ячейки не внесены";
        }

        long attached = serials == null
                ? 0L
                : serials.stream().filter(GroupZnpSerialRow::attached).count();

        return quantityLabel == null || quantityLabel.isBlank()
                ? attached + " яч."
                : attached + " из " + quantityLabel.trim();
    }
}
