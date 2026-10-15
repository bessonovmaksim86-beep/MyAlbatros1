package ru.company.production.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.company.production.entity.CustomerOrder;
import ru.company.production.entity.OrderScheduleRow;
import ru.company.production.entity.ProductionOrder;
import ru.company.production.repository.AttachedCellView;
import ru.company.production.repository.CustomerOrderItemRepository;
import ru.company.production.repository.OrderItemNameView;
import ru.company.production.repository.ProductionOrderItemRepository;
import ru.company.production.repository.ProductionOrderSerialProjection;
import ru.company.production.repository.SerialProductionOrderRepository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Подвязка серийных номеров и полных наименований к экземплярам
 * графика в разделе распределения.
 *
 * Класс намеренно вынесен из {@link WorkshopDistributionService}: тот
 * считает планирование, загрузки и подбор исполнителей, а здесь —
 * только обогащение готовых строк выпускаемыми реквизитами. Разделение
 * держит тяжёлый сервис распределения неизменным по логике и не
 * заставляет таблицу распределения обращаться к базе ради каждой
 * строки.
 *
 * Как экземпляр графика находит свой серийник. Строка графика (V43)
 * планирует изделие до выпуска и номера не хранит; номер рождается в
 * составе выпуска (ProductionOrderItem), где каждая позиция — один
 * экземпляр, выросший из позиции заказа покупателя. Общая у них — пара
 * «заказ покупателя + позиция». Порядок экземпляров внутри позиции
 * восстанавливается по сквозному полю position выпуска; он и есть
 * 0-based номер экземпляра, совпадающий с {@code instanceIndex} строки
 * графика. Поэтому сопоставление идёт по ключу (заказ, позиция, индекс)
 * без обращения к базе ради каждой строки.
 */
@Service
@RequiredArgsConstructor
public class WorkshopDistributionSerialService {

    private final ProductionOrderItemRepository productionItemRepo;
    private final CustomerOrderItemRepository customerItemRepo;
    private final SerialProductionOrderRepository serialLinkRepo;

    /**
     * Серийник и полное наименование для каждого экземпляра графика.
     *
     * Ключ результата — {@code rowId} строки графика; значение —
     * проекция выпуска (полный номер через {@code getFullNumber()},
     * полное наименование через {@code getDisplayName()}). Экземпляры,
     * чей выпуск ещё не создан или чей номер не выпущен, в карту не
     * попадают: для них распределение откатывается на плановую подпись.
     *
     * Один пакетный запрос на всю страницу: собираются идентификаторы
     * заказов, по ним читаются серийники, а затем экземплярам
     * назначаются порядковые номера внутри позиции.
     */
    @Transactional(readOnly = true)
    public Map<Long, ProductionOrderSerialProjection> resolve(
            List<OrderScheduleRow> rows
    ) {
        if (rows == null || rows.isEmpty()) {
            return Map.of();
        }

        Set<Long> orderIds = rows.stream()
                .map(OrderScheduleRow::getCustomerOrder)
                .filter(Objects::nonNull)
                .map(CustomerOrder::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        if (orderIds.isEmpty()) {
            return Map.of();
        }

        /*
         * Выборка уже отсортирована по (заказ, позиция, position
         * выпуска), поэтому индекс элемента внутри группы «заказ +
         * позиция» — это и есть порядковый номер экземпляра (0-based).
         */
        List<ProductionOrderSerialProjection> serials =
                productionItemRepo.findSerialsByCustomerOrderIds(orderIds);

        Map<String, List<ProductionOrderSerialProjection>> byPosition =
                new LinkedHashMap<>();

        for (ProductionOrderSerialProjection serial : serials) {
            byPosition
                    .computeIfAbsent(
                            key(serial.getCustomerOrderId(),
                                    serial.getItemPosition()),
                            k -> new ArrayList<>()
                    )
                    .add(serial);
        }

        Map<Long, ProductionOrderSerialProjection> result = new HashMap<>();

        for (OrderScheduleRow row : rows) {
            CustomerOrder order = row.getCustomerOrder();

            if (order == null || order.getId() == null
                    || row.getItemPosition() == null
                    || row.getInstanceIndex() == null) {
                continue;
            }

            List<ProductionOrderSerialProjection> group = byPosition.get(
                    key(order.getId(), row.getItemPosition())
            );

            if (group == null) {
                continue;
            }

            int index = row.getInstanceIndex();

            if (index < 0 || index >= group.size()) {
                continue;
            }

            result.put(row.getId(), group.get(index));
        }

        return result;
    }

    /* Ключ группы экземпляров одной позиции одного заказа покупателя. */
    private static String key(Long orderId, Integer position) {
        return orderId + "|" + position;
    }

    /**
     * Номер и наименование внесённой ячейки для каждого экземпляра
     * графика по групповому ЗНП.
     *
     * Это второй источник подписи изделия — для строк, у которых
     * основание есть групповой заказ (V38). У таких заказов состава
     * выпуска нет вовсе: {@link #resolve} по заказу покупателя там
     * возвращает пусто, и таблица осталась бы с прочерком в колонке
     * номера. Состав задаёт склад, внося номера ячеек в матрицу
     * комплектования (V20), — их и подставляет этот метод.
     *
     * Ключ результата — {@code rowId} строки графика, как у
     * {@link #resolve}, чтобы сборщик списка читал обе карты одинаково.
     *
     * Сопоставление экземпляру — по порядку внесения: склад закрывает
     * ближайший незакрытый экземпляр (см.
     * {@code SubassemblyKittingService#completeScheduleRow}), поэтому
     * i-я по внесению ячейка принадлежит экземпляру с номером i. Отдель
     * колонки-связи в схеме нет, и тот же порядок использует раздел
     * распределения групповых заказов — два экрана обязаны подписывать
     * работу одним номером.
     *
     * Экземпляр без ячейки в карту не попадает: кладовщик вправе внести
     * не все номера, а работы незакрытого экземпляра существуют до
     * внесения. Прочерк честнее выдуманного номера.
     *
     * Один пакетный запрос на всю страницу: собираются идентификаторы
     * заказов-оснований, по ним читаются внесённые номера.
     */
    @Transactional(readOnly = true)
    public Map<Long, AttachedCellView> resolveAttachedCells(
            List<OrderScheduleRow> rows
    ) {
        if (rows == null || rows.isEmpty()) {
            return Map.of();
        }

        Set<Long> orderIds = rows.stream()
                .map(OrderScheduleRow::getProductionOrder)
                .filter(Objects::nonNull)
                .map(ProductionOrder::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        if (orderIds.isEmpty()) {
            return Map.of();
        }

        Map<Long, List<AttachedCellView>> cellsByOrder = new LinkedHashMap<>();

        for (AttachedCellView cell
                : serialLinkRepo.findAttachedCellsByOrderIds(orderIds)) {
            if (cell.getOrderId() == null) {
                continue;
            }

            cellsByOrder
                    .computeIfAbsent(cell.getOrderId(), k -> new ArrayList<>())
                    .add(cell);
        }

        Map<Long, AttachedCellView> result = new HashMap<>();

        for (OrderScheduleRow row : rows) {
            ProductionOrder order = row.getProductionOrder();
            Integer index = row.getInstanceIndex();

            /*
             * Строки плана заказа покупателя здесь пропускаются: у них
             * свой серийник выпуска, и подпись ячейки их бы только
             * переписала.
             */
            if (order == null || order.getId() == null || index == null) {
                continue;
            }

            List<AttachedCellView> cells = cellsByOrder.get(order.getId());

            if (cells == null || index < 0 || index >= cells.size()) {
                continue;
            }

            result.put(row.getId(), cells.get(index));
        }

        return result;
    }

    /*
     * Наименования изделий для набора заказов — пакетный резолвинг.
     *
     * Нужен разделу распределения, чтобы подписать изделие на главной
     * строке таблицы: выпуск с серийным номером мог ещё не существовать,
     * и тогда наименование — единственная подсказка, над чем работа.
     *
     * Ключ результата — пара «заказ|позиция» (тот же {@link #key}, что и
     * при подвязке серийников), поэтому все экземпляры одной позиции
     * получают одно наименование без раздувания карты.
     */
    @Transactional(readOnly = true)
    public Map<String, String> resolveItemNames(
            List<OrderScheduleRow> rows
    ) {
        if (rows == null || rows.isEmpty()) {
            return Map.of();
        }

        Set<Long> orderIds = rows.stream()
                .map(OrderScheduleRow::getCustomerOrder)
                .filter(Objects::nonNull)
                .map(CustomerOrder::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        if (orderIds.isEmpty()) {
            return Map.of();
        }

        Map<String, String> result = new HashMap<>();

        for (OrderItemNameView item
                : customerItemRepo.findItemNamesByOrderIds(orderIds)) {
            String name = item.getDisplayName();

            if (name == null) {
                continue;
            }

            result.putIfAbsent(
                    key(item.getCustomerOrderId(),
                            item.getItemPosition()),
                    name
            );
        }

        return result;
    }

    /**
     * Наименование изделия одной строки графика.
     *
     * Пусто, если позиция заказа не определилась: строка таблицы тогда
     * показывает прочерк, а не пустую клетку.
     */
    public String itemNameOf(
            OrderScheduleRow row,
            Map<String, String> itemNames
    ) {
        if (row == null || itemNames == null || itemNames.isEmpty()) {
            return null;
        }

        CustomerOrder order = row.getCustomerOrder();

        if (order == null || order.getId() == null
                || row.getItemPosition() == null) {
            return null;
        }

        return itemNames.get(key(order.getId(), row.getItemPosition()));
    }
}