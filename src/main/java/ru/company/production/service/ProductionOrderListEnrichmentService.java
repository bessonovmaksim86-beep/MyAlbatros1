package ru.company.production.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.company.production.entity.ProductType;
import ru.company.production.entity.ProductionOrderStatus;
import ru.company.production.repository.ProductionOrderItemRepository;
import ru.company.production.repository.ProductionOrderListItemView;
import ru.company.production.repository.ProductionOrderSerialRowView;
import ru.company.production.repository.SerialProductionOrderRepository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Колонка «Серийник(и)» и фильтры списка заказов на производство.
 *
 * Серийники заказа приходят из двух разных мест, и это не дубли, а два
 * разных факта (см. SerialProductionOrder):
 *
 *   * выпуск (ЗП) — номера выпускаемых экземпляров из состава заказа
 *       (production_order_items.serial);
 *   * заказ-комплектующий (ЗНП) — датчики, в которые заказ вошёл
 *       (serial_production_orders): состава выпуска у него нет, его
 *       «серийники» — то, под что он комплектует.
 *
 * Оба источника читаются одним вызовом на всю страницу: список
 * показывает десятки заказов, и запрос на строку дал бы N+1.
 */
@Service
@RequiredArgsConstructor
public class ProductionOrderListEnrichmentService {

    private final ProductionOrderItemRepository productionOrderItemRepository;
    private final SerialProductionOrderRepository serialProductionOrderRepository;

    /**
     * Готовые данные списка: строки после фильтров, перечень серийников
     * по идентификатору заказа и выпуски для селекта «Заказ на
     * производство».
     */
    public record Enriched(
            List<ProductionOrderListItemView> rows,
            Map<Long, String> serialsByOrder,
            List<ProductionOrderListItemView> releases
    ) {
    }

    /**
     * Собирает список по параметрам фильтра.
     *
     * Фильтры сужают список, а не уходят в drill-down: выпуск, статус и
     * тип изделия можно применить одновременно, пустой выбор оставляет
     * список без изменений.
     *
     * Тип изделия берётся из самих серийников: у выпуска это тип
     * выпускаемого экземпляра, у ЗНП — тип датчика, под который он
     * комплектует. Заказ без серийников под фильтр по типу не попадает
     * — показать его «датчиком» или «системой» нечем.
     *
     * Фильтр по выпуску работает по номеру родителя: проекция строки
     * (ProductionOrderListItemView) отдаёт parentOrderNumber, но не id
     * родителя, а раздувать проекцию ради одного селекта незачем.
     */
    @Transactional(readOnly = true)
    public Enriched enrich(
            List<ProductionOrderListItemView> rows,
            ProductType productType,
            ProductionOrderStatus status,
            Long releaseOrderId
    ) {

        List<ProductionOrderListItemView> releases = releasesOf(rows);

        if (rows == null || rows.isEmpty()) {
            return new Enriched(List.of(), Map.of(), releases);
        }

        /*
         * Номера читаются до фильтрации: фильтр по выпуску выбирает
         * номер выпуска по его id, а сам выпуск может не пройти
         * остальные условия — тогда строки остались бы без номера.
         */
        Map<Long, List<ProductionOrderSerialRowView>> serialRows =
                loadSerials(rows);

        String releaseNumber = releaseNumber(releases, releaseOrderId);

        List<ProductionOrderListItemView> filtered = new ArrayList<>();
        Map<Long, String> serialsByOrder = new LinkedHashMap<>();

        for (ProductionOrderListItemView row : rows) {

            if (status != null && row.getStatus() != status) {
                continue;
            }

            List<ProductionOrderSerialRowView> numbers =
                    serialRows.getOrDefault(row.getId(), List.of());

            if (productType != null
                    && !hasType(numbers, productType)) {
                continue;
            }

            if (releaseNumber != null
                    && !belongsToRelease(row, releaseNumber)) {
                continue;
            }

            filtered.add(row);
            serialsByOrder.put(row.getId(), join(numbers));
        }

        return new Enriched(filtered, serialsByOrder, releases);
    }

    /*
     * Выпуски (ЗП) того же списка — источник варианта для селекта
     * «Заказ на производство». Отдельный запрос не нужен: раздел и так
     * читает верхние уровни, а групповые заказы в этот набор не входят
     * (их строки помечены как комплектующие).
     */
    private List<ProductionOrderListItemView> releasesOf(
            List<ProductionOrderListItemView> rows) {

        if (rows == null) {
            return List.of();
        }

        return rows.stream()
                .filter(row -> !row.isComponent())
                .toList();
    }

    /*
     * Серийники всех строк страницы двумя запросами — по выпускам и по
     * заказам-комплектующим. Заказ может оказаться в обоих: выпуск,
     * который сам вошёл в чужое изделие, перечисляет и свои номера, и
     * датчики — объединение держит это в одной клетке без повторов.
     */
    private Map<Long, List<ProductionOrderSerialRowView>> loadSerials(
            List<ProductionOrderListItemView> rows) {

        List<Long> orderIds = rows.stream()
                .map(ProductionOrderListItemView::getId)
                .filter(Objects::nonNull)
                .toList();

        if (orderIds.isEmpty()) {
            return Map.of();
        }

        Map<Long, List<ProductionOrderSerialRowView>> grouped =
                new LinkedHashMap<>();

        append(
                grouped,
                productionOrderItemRepository
                        .findSerialsByOrderIds(orderIds)
        );

        append(
                grouped,
                serialProductionOrderRepository
                        .findSerialsByOrderIds(orderIds)
        );

        return grouped;
    }

    private void append(
            Map<Long, List<ProductionOrderSerialRowView>> grouped,
            List<ProductionOrderSerialRowView> rows) {

        for (ProductionOrderSerialRowView row : rows) {

            if (row.getOrderId() == null) {
                continue;
            }

            grouped
                    .computeIfAbsent(
                            row.getOrderId(),
                            key -> new ArrayList<>())
                    .add(row);
        }
    }

    private boolean hasType(
            List<ProductionOrderSerialRowView> numbers,
            ProductType productType) {

        for (ProductionOrderSerialRowView number : numbers) {

            if (number.getProductType() == productType) {
                return true;
            }
        }

        return false;
    }

    /*
     * Номер выпуска по его идентификатору. null — фильтр не выбран;
     * пустая строка — выпуск выбран, но в текущем наборе строк его нет
     * (заказ снят с активности): условие заведомо невыполнимо, и список
     * обязан опустеть, а не показать всё подряд.
     */
    private String releaseNumber(
            List<ProductionOrderListItemView> releases,
            Long releaseOrderId) {

        if (releaseOrderId == null) {
            return null;
        }

        for (ProductionOrderListItemView release : releases) {

            if (releaseOrderId.equals(release.getId())) {
                return release.getNumber();
            }
        }

        return "";
    }

    /*
     * Заказ относится к выбранному выпуску: сам выпуск либо вложенный
     * в него заказ. Сравнение по номеру — см. комментарий enrich().
     */
    private boolean belongsToRelease(
            ProductionOrderListItemView row,
            String releaseNumber) {

        if (releaseNumber.isEmpty()) {
            return false;
        }

        if (releaseNumber.equals(row.getNumber())) {
            return true;
        }

        String parent = row.getParentOrderNumber();

        return parent != null && releaseNumber.equals(parent);
    }

    /*
     * Перечень через запятую без повторов: один и тот же датчик мог
     * попасть в выборку из обоих источников.
     */
    private String join(List<ProductionOrderSerialRowView> numbers) {

        if (numbers.isEmpty()) {
            return "—";
        }

        Set<String> distinct = new LinkedHashSet<>();

        for (ProductionOrderSerialRowView number : numbers) {

            String full = number.getFullNumber();

            if (full != null) {
                distinct.add(full);
            }
        }

        return distinct.isEmpty() ? "—" : String.join(", ", distinct);
    }
}