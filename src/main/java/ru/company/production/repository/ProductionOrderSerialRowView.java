package ru.company.production.repository;

import ru.company.production.entity.ProductType;

/**
 * Серийный номер, относящийся к заказу на производство, вместе с типом
 * изделия — строка для колонки «Серийник(и)» и фильтра по типу в списке
 * заказов.
 *
 * Одну и ту же форму отдают два разных источника, и это намеренно:
 *
 *   * выпуск (ЗП) — номера выпускаемых экземпляров из состава заказа
 *       (ProductionOrderItem.serial); тип берётся у классификатора
 *       выпускаемого изделия;
 *   * заказ-комплектующий (ЗНП) — датчики, в которые заказ вошёл
 *       (SerialProductionOrder.serial); тип берётся у самого датчика.
 *
 * Полный номер не выбирается строкой, а собирается из code + serial по
 * тому же правилу, что ProductSerial.getFullNumber(): проекция не может
 * вызвать default-метод сущности, поэтому половинки отдаются отдельно, а
 * склейку делает сервис.
 */
public interface ProductionOrderSerialRowView {

    /* Заказ, к которому относится номер. */
    Long getOrderId();

    /* Код классификатора — старшая часть номера изделия. */
    Integer getClassifierCode();

    /* Серия — младшая часть (5 знаков). */
    Integer getSerial();

    /* Тип изделия: нужен фильтру «датчики / системы». */
    ProductType getProductType();

    /* Полный номер: 1212 + 00022 → «121200022». */
    default String getFullNumber() {
        Integer code = getClassifierCode();
        Integer serial = getSerial();

        if (code == null || serial == null) {
            return null;
        }

        return code + String.format("%05d", serial);
    }
}
