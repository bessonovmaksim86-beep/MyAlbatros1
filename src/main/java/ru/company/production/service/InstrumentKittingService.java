package ru.company.production.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.company.production.dto.InstrumentCellKindRow;
import ru.company.production.dto.InstrumentKittingView;
import ru.company.production.dto.InstrumentKittingZnpRow;
import ru.company.production.dto.QuarantineFilter;
import ru.company.production.dto.SubassemblyKittingCellRow;
import ru.company.production.entity.AppUser;
import ru.company.production.entity.ClassifierInclusion;
import ru.company.production.entity.Operation;
import ru.company.production.entity.OrderScheduleRow;
import ru.company.production.entity.ProductClassifier;
import ru.company.production.entity.ProductSerial;
import ru.company.production.entity.ProductSerialOperation;
import ru.company.production.entity.ProductSerialOperationStatus;
import ru.company.production.entity.ProductType;
import ru.company.production.entity.ProductionOrder;
import ru.company.production.entity.ProductionOrderStatus;
import ru.company.production.entity.ScheduleRowStatus;
import ru.company.production.entity.SerialProductionOrder;
import ru.company.production.entity.TechProcessOperation;
import ru.company.production.repository.CellReceivingBatchRepository;
import ru.company.production.repository.OrderScheduleRowRepository;
import ru.company.production.repository.ProductClassifierRepository;
import ru.company.production.repository.ProductSerialOperationRepository;
import ru.company.production.repository.ProductSerialRepository;
import ru.company.production.repository.ProductionOrderRepository;
import ru.company.production.repository.SerialProductionOrderRepository;
import ru.company.production.repository.TechProcessOperationRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Комплектация группового ЗНП на приборы.
 *
 * Раздел решает задачу, показанную на скриншотах:
 *
 * 1. Технолог в операции маршрута задаёт несколько контролируемых
 *    параметров «Ячейка №» — по одному на каждый вид ячейки, входящей
 *    в прибор (ЯДВ3, ЯПР39, ЯПР64 и т. п.). Если вид повторён в
 *    операции несколько раз, в один прибор входит несколько ячеек
 *    этого вида.
 *
 * 2. Диспетчер заводит групповой ЗНП на приборы изделием и количеством
 *    приборов.
 *
 * 3. Склад («Склад полуфабрикатов.Исполнитель») вносит с полки номера
 *    ячеек каждого вида. Разрешены только принятые складом ячейки,
 *    ещё не внесённые ни в один заказ (тот же перечень
 *    {@code findFreeOnWarehouse}, что и раздел подсборок: он отсекает
 *    подсборки по готовности {@code cellReadyAt}).
 *
 * 4. Требуемое количество вида = ячеек вида на один прибор × число
 *    приборов заказа.
 *
 * 5. Когда все виды укомплектованы, операция комплектования закрыта, а
 *    ЗНП переходит к следующему подразделению по техпроцессу — тем же
 *    механизмом, что и раздел подсборок: проведением строки плана
 *    заказа (зависимость маршрута разблокирует следующую операцию) и
 *    передачей подразделению-получателю (V81).
 *
 * Учёт состава ведётся через ту же матрицу {@link SerialProductionOrder}
 * (V20), что и раздел «Комплектование подсборок»: параллельный учёт
 * не заводим. Привязка ячейки к конкретному прибору — отдельная
 * цеховая операция (таблица {@code cell_instrument_bindings}, V91),
 * здесь она не выполняется: склад набирает состав на весь ЗНП разом.
 */
@Service
@RequiredArgsConstructor
public class InstrumentKittingService {

    /** Тип операции «Комплектование ячеек» (V83). */
    private static final String KITTING_CELL_CODE = "KITTING_CELL";

    /** Подразделение «Склад полуфабрикатов» (V2). */
    private static final String WAREHOUSE_UNIT_CODE =
            "SEMI_FINISHED_PRODUCT_WAREHOUSE";

    /** Длина полного номера: 4 цифры кода + 5 цифр серии. */
    private static final int FULL_NUMBER_LENGTH = 9;

    /** Номеров в сообщении об ошибке: длинный список не читаем. */
    private static final int MESSAGE_NUMBER_LIMIT = 12;

    private final ProductionOrderRepository productionOrderRepository;
    private final ProductSerialRepository serialRepository;
    private final SerialProductionOrderRepository linkRepository;
    private final TechProcessOperationRepository techProcessOpRepository;
    private final ProductClassifierRepository classifierRepository;
    private final ProductSerialOperationRepository serialOperationRepository;
    private final SerialMovementJournalService journal;
    private final UnitHandoverService handoverService;
    private final OrderScheduleRowRepository scheduleRowRepository;
    private final QuarantineTransferService quarantineTransferService;
    private final CellReceivingBatchRepository batchRepository;
    private final InputControlService inputControlService;

    /*
     * ============================================================
     * СПИСОК ЗАКАЗОВ
     * ============================================================
     */

    /**
     * Групповые ЗНП на приборы с операцией комплектования ячеек склада.
     *
     * Свободный склад читается один раз на страницу и вычитается из
     * изолятора брака; внесённый состав — пакетно по идентификаторам.
     */
    @Transactional(readOnly = true)
    public List<InstrumentKittingZnpRow> findOrders() {

        List<ProductionOrder> orders =
                productionOrderRepository.findInstrumentKittingOrders(
                        ProductionOrderStatus.IN_WORK,
                        KITTING_CELL_CODE,
                        WAREHOUSE_UNIT_CODE
                );

        if (orders.isEmpty()) {
            return List.of();
        }

        List<ProductSerial> stock = freeStock();

        Map<Long, List<SerialProductionOrder>> attachedByOrder =
                attachedLinksByOrder(orders);

        Map<Long, String> changeNumbers = latestChangeNumbers(orders);

        Map<Long, LocalDate> planEndDates = planEndDates(orders);

        List<InstrumentKittingZnpRow> result = new ArrayList<>();

        for (ProductionOrder order : orders) {

            List<SerialProductionOrder> attached =
                    attachedByOrder.getOrDefault(order.getId(), List.of());

            List<InstrumentCellKindRow> kinds =
                    resolveKinds(order, stock, attached);

            result.add(new InstrumentKittingZnpRow(
                    order.getId(),
                    order.getNumber(),
                    order.getZnpKindDisplayName(),
                    order.getClassifierLabelWithChange(
                            changeNumbers.get(classifierId(order))),
                    order.getTechProcessCode(),
                    operationName(order),
                    order.getFormattedOrderDate(),
                    planDueDate(order, planEndDates),
                    order.getStatusDisplayName(),
                    order.getStatusBadgeClass(),
                    order.getNote(),
                    order.getQuantity(),
                    instrumentCount(order),
                    kinds
            ));
        }

        return result;
    }

    /*
     * ============================================================
     * РАЗБОР ВИДОВ ЯЧЕЕК ЗАКАЗА
     * ============================================================
     */

    /**
     * Виды ячеек заказа — из состава его изделия, а не из техпроцесса.
     *
     * Технолог описывает прибор в его карточке: в {@code
     * classifier_inclusion} owner = изделие заказа перечислены ячейки,
     * которые в него входят, и сколько штук каждого вида на один
     * прибор. Склад обязан набирать ровно этот состав.
     *
     * Техпроцесс как источник видов непригоден: контролируемые
     * параметры «Ячейка №» висят на производственной операции цеха
     * (настройка ячеек), а не на складской операции комплектования, и
     * читать их с операции комплектования — значит для корректного
     * маршрута получить пустой перечень. Пустой перечень раньше и
     * давал ложный «укомплектован»: требуемое количество равно нулю,
     * и сравнение «внесено &lt; требуемого» ложно.
     *
     * Берутся только позиции состава типа «Ячейка» ({@link
     * ProductType#CELL}): датчики и подсборки прибор набирает не со
     * склада полуфабрикатов этим разделом.
     *
     * @param stock    свободный склад (без изолятора), читается один раз
     * @param attached внесённые в заказ связи матрицы V20
     */
    private List<InstrumentCellKindRow> resolveKinds(
            ProductionOrder order,
            List<ProductSerial> stock,
            List<SerialProductionOrder> attached) {

        Long instrumentClassifierId = classifierId(order);

        if (instrumentClassifierId == null) {
            return List.of();
        }

        List<ClassifierInclusion> composition = classifierRepository
                .findDetailedById(instrumentClassifierId)
                .map(ProductClassifier::getInclusions)
                .orElse(List.of())
                .stream()
                .filter(inclusion -> inclusion.getTarget() != null
                        && inclusion.getTarget().getProductType()
                            == ProductType.CELL)
                .sorted(Comparator.comparing(
                        ClassifierInclusion::getPosition,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();

        if (composition.isEmpty()) {
            return List.of();
        }

        long instrumentQty = instrumentCount(order);

        List<InstrumentCellKindRow> kinds = new ArrayList<>();

        for (ClassifierInclusion inclusion : composition) {

            ProductClassifier cellClass = inclusion.getTarget();
            Long kindClassifierId = cellClass.getId();

            /*
             * Ячеек вида на один прибор — количество позиции состава
             * (округлено вверх: штуки дробными не бывают).
             */
            int perInstrument = inclusion.getQuantity() == null
                    ? 1
                    : inclusion.getQuantity()
                            .setScale(0, RoundingMode.CEILING).intValue();

            long requiredTotal = (long) perInstrument * instrumentQty;

            long attachedCount = attached.stream()
                    .filter(link -> link.getSerial() != null
                            && link.getSerial().getProductClassifier() != null
                            && kindClassifierId.equals(
                                    link.getSerial().getProductClassifier().getId()))
                    .count();

            List<String> attachedNumbers = attached.stream()
                    .filter(link -> link.getSerial() != null
                            && link.getSerial().getProductClassifier() != null
                            && kindClassifierId.equals(
                                    link.getSerial().getProductClassifier().getId()))
                    .map(link -> link.getSerial().getFullNumber())
                    .limit(MESSAGE_NUMBER_LIMIT)
                    .toList();

            List<String> freeNumbers = stock.stream()
                    .filter(s -> s.getProductClassifier() != null
                            && kindClassifierId.equals(
                                    s.getProductClassifier().getId()))
                    .map(ProductSerial::getFullNumber)
                    .toList();

            kinds.add(new InstrumentCellKindRow(
                    kindClassifierId,
                    cellClass.getCode(),
                    cellClass.getDisplayName(),
                    perInstrument,
                    requiredTotal,
                    attachedCount,
                    attachedNumbers,
                    freeNumbers.size(),
                    freeNumbers
            ));
        }

        return kinds;
    }

    /*
     * ============================================================
     * КАРТОЧКА ЗАКАЗА
     * ============================================================
     */

    /**
     * Экран внесения ячеек: заказ, его виды ячеек, свободный склад и
     * уже внесённый состав.
     */
    @Transactional(readOnly = true)
    public InstrumentKittingView loadView(Long orderId) {

        ProductionOrder order = requireKittingOrder(orderId);

        List<SerialProductionOrder> attached =
                linkRepository.findByProductionOrderId(orderId);

        return new InstrumentKittingView(
                order,
                resolveKinds(order, freeStock(), attached),
                toCellRows(attached),
                quarantineCells(order),
                operationName(order),
                latestChangeNumber(order),
                planDueDate(order, planEndDates(List.of(order)))
        );
    }

    /*
     * ============================================================
     * ВНЕСЕНИЕ ЯЧЕЕК
     * ============================================================
     */

    /**
     * Вносит ячейки в групповой ЗНП на приборы по видам.
     *
     * Номер принимается только если он принадлежит классификатору
     * соответствующего вида и не превышает требуемое количество этого
     * вида. Каждая ячейка записывается в матрицу {@link
     * SerialProductionOrder}, проводит операцию изделия и строку плана
     * заказа, после чего передаётся подразделению-получателю.
     */
    @Transactional
    public KittingResult attachCells(
            Long orderId,
            Map<Long, String> cellNumbersByKind,
            AppUser executor) {

        ProductionOrder order = requireKittingOrder(orderId);

        Long instrumentClassifierId = classifierId(order);

        /*
         * Загружаю состав изделия одной сессией: виды, лимиты каждого
         * вида, свободный склад — всё строится на основе inclusions.
         */
        List<ClassifierInclusion> composition = classifierRepository
                .findDetailedById(instrumentClassifierId)
                .map(ProductClassifier::getInclusions)
                .orElse(List.of())
                .stream()
                .filter(inclusion -> inclusion.getTarget() != null
                        && inclusion.getTarget().getProductType()
                            == ProductType.CELL)
                .sorted(Comparator.comparing(
                        ClassifierInclusion::getPosition,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();

        if (composition.isEmpty()) {
            return KittingResult.rejected(
                    "В заказе «" + order.getNumber()
                            + "» не задан состав изделия — вносить нечего. "
                            + "Обратитесь к технологу.");
        }

        /*
         * Собираю справочник вида: classifierId → requiredTotal и
         * свободный склад. Свободный склад читаю один раз и группирую
         * по классификатору вида.
         */
        Map<Long, Long> requiredByKind = new LinkedHashMap<>();
        Map<Long, String> nameByKind = new LinkedHashMap<>();
        long instrumentQty = instrumentCount(order);

        for (ClassifierInclusion inclusion : composition) {
            Long kindId = inclusion.getTarget().getId();
            int perInstrument = inclusion.getQuantity() == null
                    ? 1
                    : inclusion.getQuantity()
                            .setScale(0, RoundingMode.CEILING).intValue();
            requiredByKind.put(kindId, (long) perInstrument * instrumentQty);
            nameByKind.put(kindId, inclusion.getTarget().getDisplayName());
        }

        List<ProductSerial> stock = freeStock();
        Map<Long, List<ProductSerial>> stockByKind = new LinkedHashMap<>();

        for (ProductSerial s : stock) {
            if (s.getProductClassifier() == null) continue;
            Long cid = s.getProductClassifier().getId();
            if (requiredByKind.containsKey(cid)) {
                stockByKind.computeIfAbsent(cid, k -> new ArrayList<>())
                        .add(s);
            }
        }

        Set<String> quarantined = quarantineTransferService
                .findActiveQuarantineNumbers();

        /*
         * Уже внесённый состав заказа читается один раз и группируется
         * по видам: запрос на каждый вид дал бы N обращений к матрице
         * там, где достаточно одного.
         */
        Map<Long, Long> alreadyByKind = new LinkedHashMap<>();

        for (SerialProductionOrder link
                : linkRepository.findByProductionOrderId(orderId)) {

            if (link.getSerial() == null
                    || link.getSerial().getProductClassifier() == null) {
                continue;
            }

            Long cid = link.getSerial().getProductClassifier().getId();
            if (cid != null) {
                alreadyByKind.merge(cid, 1L, Long::sum);
            }
        }

        /*
         * Прохожу по видам: для каждого вида валидирую ввод, проверяю
         * вид ячейки (номер должен принадлежать классификатору вида)
         * и лимит (не превышать requiredTotal вида).
         */
        List<String> errors = new ArrayList<>();
        Map<Long, List<ProductSerial>> attachedByKind = new LinkedHashMap<>();

        for (Map.Entry<Long, Long> entry : requiredByKind.entrySet()) {
            Long kindId = entry.getKey();
            Long requiredTotal = entry.getValue();

            /*
             * Текущее число ячеек вида, уже внесённых в заказ.
             */
            long alreadyAttached = alreadyByKind.getOrDefault(kindId, 0L);

            String input = cellNumbersByKind == null
                    ? null
                    : cellNumbersByKind.get(kindId);

            List<String> chosen = parseNumbers(input);
            List<ProductSerial> attached = new ArrayList<>();

            /*
             * Свободный склад этого вида поднят на уровень цикла по
             * видам: он нужен и для проверки каждого номера, и для
             * подписи при превышении лимита.
             */
            List<ProductSerial> kindStock = stockByKind.get(kindId);

            for (String number : chosen) {

                /*
                 * Изолятор брака.
                 */
                if (quarantined.contains(number)) {
                    errors.add(number + " — в изоляторе брака");
                    continue;
                }

                /*
                 * Складской перечень.
                 */
                if (kindStock == null) {
                    errors.add(number + " — нет на складе");
                    continue;
                }

                ProductSerial serial = kindStock.stream()
                        .filter(s -> number.equals(s.getFullNumber()))
                        .findFirst()
                        .orElse(null);

                if (serial == null) {
                    errors.add(number + " — нет на складе полуфабрикатов");
                    continue;
                }

                /*
                 * Уже внесена в заказ.
                 */
                if (linkRepository
                        .existsBySerial_IdAndProductionOrder_Id(
                                serial.getId(), orderId)) {
                    continue;
                }

                attached.add(serial);
            }

            /*
             * Проверка лимита вида: уже внесено + новое ≤ requiredTotal.
             */
            if (alreadyAttached + attached.size() > requiredTotal) {
                errors.add(
                        "Вид «" + nameByKind.getOrDefault(kindId,
                                String.valueOf(kindId)) + "»"
                                + ": уже вошло " + alreadyAttached
                                + ", вносится " + attached.size()
                                + ", заказано " + requiredTotal);
            }

            attachedByKind.put(kindId, attached);
        }

        if (!errors.isEmpty()) {
            return KittingResult.rejected(
                    "Некоторые номера не приняты: " + join(errors));
        }

        /*
         * Ничего нового не собрано — все номера либо дубликаты, либо
         * введены пустыми.
         */
        boolean anyNew = attachedByKind.values().stream()
                .anyMatch(list -> !list.isEmpty());

        if (!anyNew) {
            return KittingResult.rejected(
                    "Все внесённые ячейки уже входят в заказ «"
                            + order.getNumber() + "»");
        }

        /*
         * Собираю плоский список для цикла записи.
         */
        List<ProductSerial> allAttached = new ArrayList<>();
        for (List<ProductSerial> list : attachedByKind.values()) {
            allAttached.addAll(list);
        }

        Operation operation = findKittingOperation(order);

        if (operation == null) {
            throw new IllegalStateException(
                    "В заказе «" + order.getNumber()
                            + "» не задана операция комплектования ячеек"
                            + " склада полуфабрикатов");
        }

        for (ProductSerial serial : allAttached) {

            SerialProductionOrder link = new SerialProductionOrder();
            link.setSerial(serial);
            link.setProductionOrder(order);
            link.setOperation(operation);
            link.setCreatedBy(executor);
            link.setNote("Комплектация группового ЗНП на приборы");
            linkRepository.save(link);

            performSerialOperation(serial, order, operation, executor);
            completeScheduleRow(order, operation, executor);
        }

        journal.recordInProduction(allAttached, order, executor);

        handoverService.openForOrder(allAttached, order, operation, executor);

        long taken = linkRepository.countByProductionOrder_Id(orderId);
        long required = requiredTotal(order);

        boolean completed = required > 0 && taken >= required;

        return KittingResult.accepted(
                "Укомплектовано в заказ «" + order.getNumber() + "»: "
                        + allAttached.size() + " яч.; всего вошло " + taken
                        + " из " + required + " яч."
                        + (completed
                                ? ". Все виды ячеек внесены — операция"
                                  + " комплектования закрыта, ЗНП передан"
                                  + " следующему подразделению по техпроцессу."
                                : ""));
    }

    /*
     * ============================================================
     * ПОМОГАЮЩИЕ МЕТОДЫ
     * ============================================================
     */

    /** Количество приборов в заказе (quantity). */
    private static long instrumentCount(ProductionOrder order) {
        BigDecimal qty = order.getQuantity();
        return qty == null
                ? 0L
                : qty.setScale(0, RoundingMode.CEILING).longValue();
    }

    /**
     * Требуемое число ячеек всех видов на заказ.
     *
     * Сумма {@code requiredTotal} по видам; заказ без видов даёт ноль,
     * и тогда укомплектованность по количеству не вычисляется.
     */
    private long requiredTotal(ProductionOrder order) {
        return resolveKinds(order, List.of(), List.of()).stream()
                .mapToLong(InstrumentCellKindRow::requiredTotal)
                .sum();
    }

    /** Свободный склад (принятые готовые ячейки) без изолятора брака. */
    private List<ProductSerial> freeStock() {
        return withoutQuarantine(
                serialRepository.findFreeOnWarehouse(),
                quarantineTransferService.findActiveQuarantineNumbers());
    }

    private static List<ProductSerial> withoutQuarantine(
            List<ProductSerial> stock,
            Set<String> quarantined) {

        if (quarantined.isEmpty()) {
            return stock;
        }

        return stock.stream()
                .filter(serial -> serial.getFullNumber() == null
                        || !quarantined.contains(serial.getFullNumber()))
                .toList();
    }

    /** Требование: заказ — групповой ЗНП на приборы. */
    private ProductionOrder requireKittingOrder(Long orderId) {

        ProductionOrder order = productionOrderRepository
                .findDetailedById(orderId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Заказ на производство не найден"));

        if (order.getZnpKind()
                != ru.company.production.entity.ZnpKind.GROUP_DEVICE) {

            throw new IllegalStateException(
                    "Комплектация приборов проходит по групповому ЗНП на"
                            + " приборы, а заказ «" + order.getNumber()
                            + "» имеет вид «" + order.getZnpKindDisplayName()
                            + "»");
        }

        return order;
    }

    /** Внесённый состав заказа — строками перечня. */
    private List<SubassemblyKittingCellRow> toCellRows(
            List<SerialProductionOrder> links) {

        return links.stream()
                .filter(link -> link.getSerial() != null)
                .map(link -> toCellRow(link.getSerial()))
                .toList();
    }

    /** Внесённые связи набора заказов — пакетно, ключ — id заказа. */
    private Map<Long, List<SerialProductionOrder>> attachedLinksByOrder(
            List<ProductionOrder> orders) {

        List<Long> ids = orders.stream()
                .map(ProductionOrder::getId)
                .filter(Objects::nonNull)
                .toList();

        if (ids.isEmpty()) {
            return Map.of();
        }

        Map<Long, List<SerialProductionOrder>> result = new LinkedHashMap<>();

        for (SerialProductionOrder link :
                linkRepository.findAttachedByOrderIds(ids)) {

            if (link.getSerial() == null
                    || link.getProductionOrder() == null
                    || link.getProductionOrder().getId() == null) {
                continue;
            }

            result.computeIfAbsent(
                            link.getProductionOrder().getId(),
                            k -> new ArrayList<>())
                    .add(link);
        }

        return result;
    }

    private static SubassemblyKittingCellRow toCellRow(ProductSerial serial) {
        return new SubassemblyKittingCellRow(
                serial.getId(),
                serial.getFullNumber(),
                serial.getDisplayName(),
                serial.getProductClassifier() == null
                        ? null
                        : String.valueOf(serial.getProductClassifier().getCode()),
                serial.getProductClassifier() == null
                        ? null
                        : serial.getProductClassifier().getId());
    }

    /** Ячейки изолятора брака по изделию заказа. */
    private List<QuarantineCellView> quarantineCells(ProductionOrder order) {

        Long classifierId = classifierId(order);

        if (classifierId == null) {
            return List.of();
        }

        return inputControlService.findQuarantineCells(
                new QuarantineFilter(null, classifierId, null));
    }

    private static Long classifierId(ProductionOrder order) {
        return order.getProductClassifier() == null
                ? null
                : order.getProductClassifier().getId();
    }

    /** Операция комплектования ячеек из техпроцесса заказа. */
    private Operation findKittingOperation(ProductionOrder order) {

        if (order.getTechProcess() == null) {
            return null;
        }

        List<TechProcessOperation> nodes =
                techProcessOpRepository.findUnitKittingNodesForProcesses(
                        List.of(order.getTechProcess().getId()),
                        KITTING_CELL_CODE,
                        WAREHOUSE_UNIT_CODE
                );

        return nodes.isEmpty() ? null : nodes.get(0).getOperation();
    }

    /** Наименование операции комплектования. */
    private String operationName(ProductionOrder order) {

        Operation operation = findKittingOperation(order);

        if (operation != null && operation.getOperationName() != null) {
            return operation.getOperationName().getName();
        }

        return "—";
    }

    /** Номер изменения изделия заказа — по последней партии прихода. */
    private String latestChangeNumber(ProductionOrder order) {

        Long cid = classifierId(order);

        if (cid == null) {
            return null;
        }

        List<String> numbers =
                batchRepository.findChangeNumbersOfClassifier(cid);

        return numbers.isEmpty() ? null : numbers.get(0);
    }

    /** Номера изменений набора заказов — одним запросом на страницу. */
    private Map<Long, String> latestChangeNumbers(List<ProductionOrder> orders) {

        List<Long> ids = orders.stream()
                .map(InstrumentKittingService::classifierId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        if (ids.isEmpty()) {
            return Map.of();
        }

        Map<Long, String> result = new LinkedHashMap<>();

        for (CellReceivingBatchRepository.ChangeNumberView row
                : batchRepository.findChangeNumbersByClassifiers(ids)) {

            if (row.getClassifierId() != null) {
                result.putIfAbsent(
                        row.getClassifierId(), row.getChangeNumber());
            }
        }

        return result;
    }

    /*
     * ============================================================
     * ПЛАН ЗАКАЗА
     * ============================================================
     */

    private static final DateTimeFormatter PLAN_DATE_FORMATTER =
            DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private Map<Long, LocalDate> planEndDates(List<ProductionOrder> orders) {

        Map<Long, LocalDate> result = new LinkedHashMap<>();

        Set<Long> ownRouteIds = new LinkedHashSet<>();

        for (ProductionOrder order : orders) {
            if (order.getId() != null && order.getZnpKind() != null
                    && order.getZnpKind().requiresTechProcess()) {
                ownRouteIds.add(order.getId());
            }
        }

        if (ownRouteIds.isEmpty()) {
            return result;
        }

        for (OrderScheduleRowRepository.PlanEndDateView row
                : scheduleRowRepository.findPlanEndDatesOfProductionOrders(
                        ScheduleRowStatus.CONFIRMED, ownRouteIds)) {
            result.put(row.getOrderId(), row.getEndDate());
        }

        return result;
    }

    private String planDueDate(
            ProductionOrder order,
            Map<Long, LocalDate> planEndDates) {

        LocalDate endDate = order.getId() == null
                ? null
                : planEndDates.get(order.getId());

        LocalDate date = endDate == null ? order.getDueDate() : endDate;

        return date == null ? "—" : date.format(PLAN_DATE_FORMATTER);
    }

    /*
     * ============================================================
     * ПРОВЕДЕНИЕ ОПЕРАЦИИ ИЗДЕЛИЯ И СТРОКИ ПЛАНА
     * ============================================================
     */

    private void performSerialOperation(
            ProductSerial serial,
            ProductionOrder order,
            Operation operation,
            AppUser user) {

        Optional<ProductSerialOperation> last =
                serialOperationRepository
                        .findFirstBySerialIdAndOperationIdOrderByAttemptDesc(
                                serial.getId(), operation.getId());

        Optional<ProductSerialOperation> open = last
                .filter(existing -> existing.getActualDate() == null
                        && !existing.isReworked());

        if (open.isPresent()) {

            ProductSerialOperation row = open.get();
            row.setProductionOrder(order);
            row.setStatus(ProductSerialOperationStatus.DONE);
            row.setActualDate(LocalDate.now());
            row.setExecutor(user);
            row.setPerformedBy(user);
            serialOperationRepository.save(row);
            return;
        }

        ProductSerialOperation row = new ProductSerialOperation();
        row.setSerial(serial);
        row.setOperation(operation);
        row.setProductionOrder(order);
        row.setAttempt(last.map(e -> e.getAttempt() + 1).orElse(1));
        row.setStatus(ProductSerialOperationStatus.DONE);
        row.setActualDate(LocalDate.now());
        row.setExecutor(user);
        row.setPerformedBy(user);
        row.setSeq(1);
        row.setNote("Комплектация группового ЗНП на приборы");
        serialOperationRepository.save(row);
    }

    private void completeScheduleRow(
            ProductionOrder order,
            Operation operation,
            AppUser executor) {

        if (order.getId() == null || operation == null
                || operation.getId() == null) {
            return;
        }

        List<OrderScheduleRow> open = scheduleRowRepository
                .findOpenRowsOfProductionOrder(order.getId(), operation.getId());

        if (open.isEmpty()) {
            return;
        }

        OrderScheduleRow row = open.get(0);
        row.setCompletedAt(LocalDateTime.now());
        row.setCompletedBy(executor);
        scheduleRowRepository.save(row);
    }

    /*
     * ============================================================
     * РАЗБОР ВВОДА
     * ============================================================
     */

    private static List<String> parseNumbers(String text) {

        if (text == null || text.isBlank()) {
            return List.of();
        }

        Set<String> numbers = new LinkedHashSet<>();

        for (String line : text.split("[,;\\r\\n]+")) {

            String digits = line.replaceAll("\\D", "");

            if (digits.length() == FULL_NUMBER_LENGTH) {
                numbers.add(digits);
            }
        }

        return List.copyOf(numbers);
    }

    private static String join(List<String> numbers) {
        return numbers.size() <= MESSAGE_NUMBER_LIMIT
                ? String.join(", ", numbers)
                : String.join(", ", numbers.subList(0, MESSAGE_NUMBER_LIMIT))
                + " …";
    }

    /*
     * ============================================================
     * СЧЁТЧИК МЕНЮ
     * ============================================================
     */

    /** Заказов, укомплектованных не полностью: счётчик пункта меню. */
    @Transactional(readOnly = true)
    public long countPending() {
        return findOrders().stream()
                .filter(InstrumentKittingZnpRow::incomplete)
                .count();
    }

    /** Итог проведения: что проведено, а что отвергнуто и почему. */
    public record KittingResult(boolean completed, String message) {

        public static KittingResult accepted(String message) {
            return new KittingResult(true, message);
        }

        public static KittingResult rejected(String message) {
            return new KittingResult(false, message);
        }
    }
}
