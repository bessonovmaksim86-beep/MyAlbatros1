package ru.company.production.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.company.production.entity.CustomerOrder;
import ru.company.production.entity.OrderScheduleRow;
import ru.company.production.repository.CustomerOrderItemRepository;
import ru.company.production.repository.OrderItemNameView;
import ru.company.production.repository.ProductionOrderItemRepository;
import ru.company.production.repository.ProductionOrderSerialProjection;

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