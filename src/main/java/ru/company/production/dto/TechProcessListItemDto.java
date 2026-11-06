package ru.company.production.dto;

import ru.company.production.entity.ProductType;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Строка списка техпроцессов на главной странице раздела.
 *
 * В отличие от {@link ru.company.production.repository.TechProcessListItemView},
 * где строка — это пара «техпроцесс — изделие», маршрут показан один
 * раз, а все привязанные к нему изделия перечислены подписями.
 * Технолог видит сначала номер маршрута и его объём (число операций),
 * и только затем — для каких изделий он годится.
 *
 * @param id             идентификатор техпроцесса
 * @param code           код маршрута (ТП-NNN)
 * @param operationCount число операций в маршруте
 * @param classifiers    подписки привязанных изделий в порядке кода
 * @param productTypes   виды изделий маршрута: один маршрут может
 *                       выпускать и ячейку, и датчик, поэтому типов
 *                       несколько. Нужны для фильтра по типу
 */
public record TechProcessListItemDto(
        Long id,
        String code,
        long operationCount,
        List<String> classifiers,
        Set<ProductType> productTypes
) {

    /**
     * Попадает ли маршрут в выборку указанного типа.
     */
    public boolean hasType(ProductType type) {
        return type != null && productTypes.contains(type);
    }
}
