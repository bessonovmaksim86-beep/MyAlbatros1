package ru.company.production.dto;

import ru.company.production.entity.ProductType;

/**
 * Выпуск готовых ячеек по позиции классификатора за период.
 *
 * @param classifierId  идентификатор классификатора
 * @param classifierCode  код классификатора
 * @param displayName   полное наименование (обозначение + имя)
 * @param productType   тип изделия (DEVICE, SENSOR, CELL, SYSTEM)
 * @param productTypeName локализованное наименование типа
 * @param totalProduced количество ячеек, ставших готовыми (cell_ready_at)
 */
public record ProductionOutputRow(
        Long classifierId,
        Integer classifierCode,
        String displayName,
        ProductType productType,
        String productTypeName,
        long totalProduced
) {
}
