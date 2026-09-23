package ru.company.production.service;

import ru.company.production.entity.ProductClassifier;
import ru.company.production.entity.ProductType;

/**
 * Область нумерации серийных номеров.
 *
 * Ряд номеров привязан не к типу изделия:
 *
 *   прибор, ячейка, система — ряд своего классификатора,
 *       888800122 → 888800123;
 *   датчик — ряд рода (sensor_catalog), общий для всех классификаторов
 *       рода: 119200111 (код 1192) → 119400112 (код 1194).
 *
 * То же правило в БД реализовано сгенерированным столбцом
 * product_serials.numbering_key (миграция V27), поэтому формула
 * здесь обязана совпадать с формулой в SQL. Держим её в одном месте:
 * и выдача номера заказом, и приход склада считают область отсюда.
 */
public final class SerialNumbering {

    /** Префикс области «классификатор». */
    private static final String CLASSIFIER_PREFIX = "C";

    /** Префикс области «род датчика». */
    private static final String SENSOR_KIND_PREFIX = "S";

    private SerialNumbering() {
    }

    /**
     * Ключ области нумерации классификатора.
     *
     * Для датчика без заполненного рода род нечем отличить один ряд от
     * другого, поэтому область откатывается к классификатору — ровно
     * как делает CASE в определении столбца.
     */
    public static String keyFor(ProductClassifier classifier) {
        if (classifier == null) {
            return null;
        }

        if (classifier.getProductType() == ProductType.SENSOR
                && classifier.getSensorCatalog() != null
                && classifier.getSensorCatalog().getId() != null) {

            return SENSOR_KIND_PREFIX + classifier.getSensorCatalog().getId();
        }

        return CLASSIFIER_PREFIX + classifier.getId();
    }
}
