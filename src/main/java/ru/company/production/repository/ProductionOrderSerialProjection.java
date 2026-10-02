package ru.company.production.repository;

/**
 * Серийный номер выпускаемого экземпляра, привязанный к позиции
 * заказа покупателя и порядковому номеру внутри этой позиции.
 *
 * Строка графика (V43) планирует изделие ДО выпуска, серийного номера
 * у неё нет. Номер появляется в составе выпуска (ProductionOrderItem),
 * где каждая строка — один экземпляр, выросший из конкретной позиции
 * заказа покупателя (customerOrderItem) в определённом порядке.
 *
 * По этой проекции экземпляр графика (заказ + позиция + номер внутри
 * позиции) сопоставляется с его серийным номером и наименованием:
 *
 *   * customerOrderId, itemPosition, releasePosition — ключ
 *       сопоставления: заказ покупателя, позиция и порядок экземпляра
 *       внутри позиции (0-based после сортировки по releasePosition);
 *   * classifierCode, serial — половинки полного номера изделия
 *       (getFullNumber() собирает «1212» + «00022»);
 *   * fullName — полное обозначение позиции заказа покупателя «как в
 *       конструкторской документации» (CustomerOrderItem.fullName);
 *       заполняется диспетчером для датчиков и даёт именно то полное
 *       наименование, которое нужно в распределении;
 *   * sensorName, classifierName — наименование из классификатора:
 *       для датчика род из справочника датчиков, для остальных — имя
 *       классификатора; подставляются, когда fullName не заполнен.
 */
public interface ProductionOrderSerialProjection {

    Long getCustomerOrderId();

    Integer getItemPosition();

    Integer getReleasePosition();

    Integer getClassifierCode();

    Integer getSerial();

    String getFullName();

    String getSensorName();

    String getClassifierName();

    /* Полный номер изделия: 1212 + 00022 → «121200022». */
    default String getFullNumber() {
        Integer code = getClassifierCode();
        Integer serial = getSerial();

        if (code == null || serial == null) {
            return null;
        }

        return code + String.format("%05d", serial);
    }

    /*
     * Полное наименование изделия.
     *
     * Приоритет — fullName позиции заказа (полное обозначение «как в
     * КД», введённое диспетчером): для датчика классификатор даёт
     * только род, а полное обозначение с параметрами живёт именно там.
     * Когда fullName пуст, подставляется наименование классификатора.
     */
    default String getDisplayName() {
        String full = pick(getFullName());
        if (full != null) {
            return full;
        }

        String sensor = pick(getSensorName());
        if (sensor != null) {
            return sensor;
        }

        return pick(getClassifierName());
    }

    private static String pick(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}