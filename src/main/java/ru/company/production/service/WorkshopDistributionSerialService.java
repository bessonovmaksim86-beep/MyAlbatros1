package ru.company.production.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.company.production.entity.CustomerOrder;
import ru.company.production.entity.OrderScheduleRow;
import ru.company.production.entity.ProductionOrder;
import ru.company.production.entity.ScrappedScheduleInstance;
import ru.company.production.entity.ZnpKind;
import ru.company.production.repository.AttachedCellView;
import ru.company.production.repository.CustomerOrderItemRepository;
import ru.company.production.repository.OrderItemNameView;
import ru.company.production.repository.ProductSerialRepository;
import ru.company.production.repository.ProductionOrderItemRepository;
import ru.company.production.repository.ProductionOrderSerialProjection;
import ru.company.production.repository.ScrappedScheduleInstanceRepository;
import ru.company.production.repository.SerialProductionOrderRepository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Подвязка серийных номеров и полных наименований к экземплярам
 * графика в разделе распределения.
 *
 * Класс намеренно выведен из {@link WorkshopDistributionService}: тот
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
    private final ScrappedScheduleInstanceRepository scrappedRepo;
    private final ProductSerialRepository productSerialRepo;

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
     * Забракованная позиция выпадает из ряда, но не из плана. Матрица
     * хранит только оставшиеся ячейки, и наивное «cells.get(index)»
     * подписало бы экземпляр 5 ячейкой, которая принадлежит экземпляру
     * 6, а последний экземпляр вышел бы вовсе без номера (пустая строка
     * перечня с рабочей кнопкой). Поэтому порядковый номер ячейки
     * сдвигается на число забракованных позиций, шедших перед ней:
     * ряд плана остаётся семизначным, а ряд матрицы — шестизначным, и
     * их надо свести, а не накладывать как есть.
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

        Set<Long> orderIds = orderIdsOf(rows);

        if (orderIds.isEmpty()) {
            return Map.of();
        }

        /*
         * Заказ на приборы (GROUP_DEVICE) подписывает экземпляр
         * номером ПРИБОРА, а не ячейкой: единица его плана — прибор,
         * серийник которого рождается в момент взятия ЗНП в работу.
         * Матрица комплектования (V20) у такого заказа не заполняется,
         * и журнал брака (V90) к нему не применяется — брак там
         * относится к изделию-прибору, а не к строке матрицы ячеек.
         * Поэтому перечень берётся из реестра DEVICE-серийников, а
         * сопоставление идёт напрямую по индексу экземпляра.
         */
        Set<Long> deviceOrderIds = deviceOrderIdsOf(rows);

        Set<Long> cellOrderIds = new java.util.LinkedHashSet<>(orderIds);
        cellOrderIds.removeAll(deviceOrderIds);

        Map<Long, List<AttachedCellView>> cellsByOrder = new LinkedHashMap<>();

        if (!cellOrderIds.isEmpty()) {
            for (AttachedCellView cell
                    : serialLinkRepo.findAttachedCellsByOrderIds(
                            cellOrderIds)) {
                if (cell.getOrderId() == null) {
                    continue;
                }

                cellsByOrder
                        .computeIfAbsent(
                                cell.getOrderId(), k -> new ArrayList<>())
                        .add(cell);
            }
        }

        if (!deviceOrderIds.isEmpty()) {
            for (AttachedCellView device
                    : productSerialRepo.findDevicesByOrderIds(
                            deviceOrderIds)) {
                if (device.getOrderId() == null) {
                    continue;
                }

                cellsByOrder
                        .computeIfAbsent(
                                device.getOrderId(), k -> new ArrayList<>())
                        .add(device);
            }
        }

        Map<Long, TreeSet<Integer>> scrappedByOrder =
                cellOrderIds.isEmpty()
                        ? Map.of()
                        : scrappedIndexesByOrder(cellOrderIds);

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

            if (cells == null || index < 0) {
                continue;
            }

            /*
             * Прибор подписывается напрямую по индексу: журнал брака
             * (V90) заводит ячейки матрицы, а у заказа на приборов
             * матрицы нет, и сдвиг на забракованные позиции применил бы
             * чужой номер.
             */
            if (deviceOrderIds.contains(order.getId())) {
                if (index < cells.size()) {
                    result.put(row.getId(), cells.get(index));
                }
                continue;
            }

            TreeSet<Integer> scrapped = scrappedByOrder.get(order.getId());

            /*
             * Собственный индекс экземпляра в журнале брака — значит,
             * изделие списано и ячейки у него нет вовсе. Выдать ему
             * следующую по ряду нельзя: она принадлежит соседу, а
             * цеху этот экземпляр покажется обычным изделием, которое
             * он проведёт до конца.
             */
            if (scrapped != null && scrapped.contains(index)) {
                continue;
            }

            int position = index - scrappedBefore(scrapped, index);

            if (position < 0 || position >= cells.size()) {
                continue;
            }

            result.put(row.getId(), cells.get(position));
        }

        return result;
    }

    /*
     * Идентификаторы заказов-оснований вида «Групповой ЗНП на приборы».
     *
     * Отделяют приборные заказы от всех прочих: у первых экземпляр
     * подписывается номером прибора из реестра, у вторых — ячейкой из
     * матрицы комплектования.
     */
    private Set<Long> deviceOrderIdsOf(List<OrderScheduleRow> rows) {
        return rows.stream()
                .map(OrderScheduleRow::getProductionOrder)
                .filter(Objects::nonNull)
                .filter(order -> order.getZnpKind()
                        == ZnpKind.GROUP_DEVICE)
                .map(ProductionOrder::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(
                        java.util.LinkedHashSet::new));
    }

    /**
     * Забракованные экземпляры строк графика — подпись «забраковано».
     *
     * Снятие ячейки с комплектования удаляет строку матрицы, и перечень
     * работ теряет из неё и номер, и признак: экземпляр выходит пустой
     * строкой «— / — / В работе» с рабочей кнопкой, которую цех и жмёт,
     * проводя несуществующее изделие. Журнал забракованных позиций
     * (V90) отвечает на вопрос «почему здесь пусто» и снимает право
     * проведения, поэтому карта нужна тем же разделам, что и карта
     * внесённых ячеек.
     *
     * Ключ результата — {@code rowId} строки графика, как у
     * {@link #resolveAttachedCells}: сборщик перечня читает обе карты
     * одинаково.
     */
    @Transactional(readOnly = true)
    public Map<Long, ScrappedScheduleInstance> resolveScrappedCells(
            List<OrderScheduleRow> rows
    ) {
        if (rows == null || rows.isEmpty()) {
            return Map.of();
        }

        Set<Long> orderIds = orderIdsOf(rows);

        if (orderIds.isEmpty()) {
            return Map.of();
        }

        Map<Long, List<ScrappedScheduleInstance>> byOrder =
                new LinkedHashMap<>();

        for (ScrappedScheduleInstance scrapped
                : scrappedRepo.findByProductionOrderIdIn(orderIds)) {
            if (scrapped.getProductionOrder() == null) {
                continue;
            }

            byOrder
                    .computeIfAbsent(
                            scrapped.getProductionOrder().getId(),
                            k -> new ArrayList<>()
                    )
                    .add(scrapped);
        }

        if (byOrder.isEmpty()) {
            return Map.of();
        }

        Map<Long, ScrappedScheduleInstance> result = new HashMap<>();

        for (OrderScheduleRow row : rows) {
            ProductionOrder order = row.getProductionOrder();
            Integer index = row.getInstanceIndex();

            if (order == null || order.getId() == null || index == null) {
                continue;
            }

            for (ScrappedScheduleInstance scrapped
                    : byOrder.getOrDefault(order.getId(), List.of())) {
                if (scrapped.getInstanceIndex() == index) {
                    result.put(row.getId(), scrapped);
                    break;
                }
            }
        }

        return result;
    }

    /**
     * Проведение запрещено, потому что позиция списана в брак.
     *
     * Нужен проведению строки графика: перечни снимают кнопку, но
     * страница могла быть открыта до решения бюро, а массовое проведение
     * и вовсе идёт списком идентификаторов. Проверка на сервере обязана
     * отвечать тем же журналом (V90), что и подпись в перечне, иначе
     * цех закрыл бы работу изделия, которого в цеху больше нет.
     */
    @Transactional(readOnly = true)
    public boolean isScrapped(OrderScheduleRow row) {
        return row != null
                && !resolveScrappedCells(List.of(row)).isEmpty();
    }

    /* Идентификаторы заказов-оснований строк — пакетная загрузка. */
    private Set<Long> orderIdsOf(List<OrderScheduleRow> rows) {
        return rows.stream()
                .map(OrderScheduleRow::getProductionOrder)
                .filter(Objects::nonNull)
                .map(ProductionOrder::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    /*
     * Забракованные индексы заказов, отсортированные внутри заказа.
     *
     * Дерево держит порядок: сдвиг считается числом позиций, шедших
     * перед данной, а оно требует именно упорядоченного множества.
     */
    private Map<Long, TreeSet<Integer>> scrappedIndexesByOrder(
            Set<Long> orderIds
    ) {
        Map<Long, TreeSet<Integer>> result = new LinkedHashMap<>();

        for (ScrappedScheduleInstance scrapped
                : scrappedRepo.findByProductionOrderIdIn(orderIds)) {
            if (scrapped.getProductionOrder() == null) {
                continue;
            }

            result
                    .computeIfAbsent(
                            scrapped.getProductionOrder().getId(),
                            k -> new TreeSet<>()
                    )
                    .add(scrapped.getInstanceIndex());
        }

        return result;
    }

    /* Сколько забракованных позиций шло перед экземпляром с этим индексом. */
    private static int scrappedBefore(TreeSet<Integer> scrapped, int index) {
        if (scrapped == null || scrapped.isEmpty()) {
            return 0;
        }

        int shift = 0;

        for (Integer scrappedIndex : scrapped) {
            if (scrappedIndex >= index) {
                break;
            }
            shift++;
        }

        return shift;
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