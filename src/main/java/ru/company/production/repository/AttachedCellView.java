package ru.company.production.repository;

/**
 * Ячейка, внесённая складом в заказ, — скалярная проекция состава.
 *
 * Нужна там, где работу подписывают номером ячейки, а не серийным
 * номером выпуска: у группового ЗНП (V38) состав задаёт склад, внося
 * номера в матрицу комплектования (V20), и именно этот номер мастер и
 * исполнитель обязаны видеть над операцией.
 *
 * Форма та же, что у {@link ProductionOrderSerialProjection}: половинки
 * номера (код классификатора и серия) собираются в полный номер методом
 * {@link #getFullNumber()}, а наименование берётся из классификатора —
 * для датчика из справочника датчиков, для остальных из имени
 * классификатора. Скалярная проекция, а не сущность связи, держится
 * намеренно: список читает десятки заказов, и разыменовывать
 * lazy-связи ради двух колонок нечем (open-in-view отключён).
 *
 * Порядок строк запроса задаёт сопоставление ячейки экземпляру плана:
 * заказ, момент внесения, затем номер (см.
 * {@code GroupZnpDistributionService#toView}).
 */
public interface AttachedCellView {

    /** Заказ, в который внесена ячейка. */
    Long getOrderId();

    /** Идентификатор ячейки в реестре. */
    Long getSerialId();

    Integer getClassifierCode();

    Integer getSerial();

    String getClassifierName();

    String getSensorName();

    /** Полный номер ячейки: 1212 + 00022 → «121200022». */
    default String getFullNumber() {
        Integer code = getClassifierCode();
        Integer serial = getSerial();

        if (code == null || serial == null) {
            return null;
        }

        return code + String.format("%05d", serial);
    }

    /** Наименование ячейки: справочник датчиков либо классификатор. */
    default String getDisplayName() {
        String sensor = pick(getSensorName());

        return sensor != null ? sensor : pick(getClassifierName());
    }

    private static String pick(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
