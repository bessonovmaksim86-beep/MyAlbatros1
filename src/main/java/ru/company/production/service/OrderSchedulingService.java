package ru.company.production.service;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.company.production.dto.ScheduleRowView;
import ru.company.production.entity.CustomerOrder;
import ru.company.production.entity.CustomerOrderItem;
import ru.company.production.entity.EmployeeAbsence;
import ru.company.production.entity.HolidayDay;
import ru.company.production.entity.Operation;
import ru.company.production.entity.OperationTypeCode;
import ru.company.production.entity.OrderPriority;
import ru.company.production.entity.OrderScheduleRow;
import ru.company.production.entity.OrganizationUnit;
import ru.company.production.entity.ProductClassifier;
import ru.company.production.entity.ProductionOrder;
import ru.company.production.entity.ScheduleRowStatus;
import ru.company.production.entity.TechProcess;
import ru.company.production.entity.TechProcessClassifier;
import ru.company.production.entity.TechProcessOperation;
import ru.company.production.entity.WorkPlace;
import ru.company.production.repository.CustomerOrderRepository;
import ru.company.production.repository.EmployeeAbsenceRepository;
import ru.company.production.repository.HolidayDayRepository;
import ru.company.production.repository.OrderScheduleRowRepository;
import ru.company.production.repository.ProductionOrderRepository;
import ru.company.production.repository.TechProcessClassifierRepository;
import ru.company.production.repository.TechProcessRepository;
import ru.company.production.repository.WorkshopStaffBindingRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Расчёт графика выпуска заказа покупателя по приоритету.
 *
 * Алгоритм (по ТЗ диспетчера):
 *
 *   1. Запуск — через {@code startDays} рабочих дней от сегодня.
 *   2. Для каждой позиции заказа берётся базовый техпроцесс изделия;
 *      позиция на N изделий даёт N экземпляров, каждый проходит весь
 *      маршрут (иначе загрузка этапа была бы занижена в N раз).
 *   3. Маршрут обходится в топологическом порядке DAG: операция не
 *      может начаться раньше всех своих предшественников этого же
 *      экземпляра плюс {@code transitionDays} рабочих дней перехода.
 *   4. Мощность этапа = число сотрудников, способных выполнить
 *      операцию, умноженное на 8-часовой рабочий день. Способность
 *      определяет руководитель подразделения привязкой к операции или
 *      к рабочему месту операции; отсутствующий в этот день сотрудник
 *      мощности не даёт.
 *   5. Загрузка дня = (занятые часы этапа + трудоёмкость операции) /
 *      мощность × 100. Если больше {@code maxLoadPercent} — дата
 *      сдвигается вперёд на следующий рабочий день.
 *   6. Крайний день изготовления = срок − {@code maxManufacturingDays}
 *      рабочих дней. Если план выходит за него, расчёт помечается
 *      неувязанным и диспетчеру предлагается сменить приоритет.
 *
 * Этап в смысле загрузки — конкретная операция, а не подразделение:
 * мощность считается по людям, способным к этой операции, поэтому и
 * занятость накапливается по паре «операция + дата». Иначе две разные
 * операции одного отдела искусственно блокировали бы друг друга, хотя
 * исполнителей у них может не пересекаться.
 *
 * Календарь (выходные, праздники, отсутствия) выгружается одним
 * снимком на расчёт: подбор даты идёт перебором дней, и поштучные
 * запросы в цикле дали бы тысячи обращений к базе.
 *
 * Расчёт ничего не фиксирует: предпросмотр существует только в памяти.
 * В план строки попадают одним действием {@link #confirm} и сразу в
 * статусе CONFIRMED — черновик, так и не внесённый в план, не должен
 * занимать людей и отклонять следующий заказ.
 */
@Service
@RequiredArgsConstructor
public class OrderSchedulingService {

    /** Принятый рабочий день в часах (мощность одного исполнителя). */
    private static final BigDecimal WORKDAY_HOURS =
            new BigDecimal("8");

    /**
     * Горизонт поиска свободной даты: если операция не размещается за
     * столько рабочих дней, этап перегружен и дальше смысл искать
     * теряется — берём последнюю проверенную дату и помечаем
     * предупреждением, а не уходим в бесконечный сдвиг.
     */
    private static final int MAX_SHIFT_DAYS = 400;

    private final CustomerOrderRepository customerOrderRepository;
    private final ProductionOrderRepository productionOrderRepository;
    private final TechProcessClassifierRepository techProcessClassifierRepository;
    private final TechProcessRepository techProcessRepository;
    private final OrderScheduleRowRepository scheduleRowRepository;
    private final WorkshopStaffBindingRepository staffBindingRepository;
    private final EmployeeAbsenceRepository employeeAbsenceRepository;
    private final HolidayDayRepository holidayDayRepository;
    private final OrderPriorityService orderPriorityService;

    /**
     * Предварительный график заказа без записи в базу.
     */
    @Transactional(readOnly = true)
    public ScheduleResult preview(Long orderId) {
        return calculate(orderId);
    }

    /**
     * Расчёт и запись графика в план: старые строки этого заказа
     * снимаются целиком, свежий расчёт вставляется в CONFIRMED.
     *
     * Пересборка обязательна: частичное обновление оставило бы вилку
     * дат от предыдущего плана, а две строки на одну пару
     * «заказ — позиция — экземпляр — операция» запрещает уникальный
     * индекс V43, так что INSERT упал бы на полпути.
     *
     * Если заказ не укладывается в крайний день изготовления, запись
     * всё равно выполняется (диспетчер видит предупреждение на шаге
     * предпросмотра и подтверждает осознанно): блокировать выпуск
     * произволом расчёта опаснее, чем дать возможность спланировать
     * заказ с явной отметкой о срыве срока.
     */
    @Transactional
    public ScheduleResult confirm(Long orderId) {
        ScheduleResult result = calculate(orderId);

        /*
         * План вносится только целиком: одна операция без закреплённого
         * исполнителя превратила бы заказ в запланированный с этапом,
         * который некому закрыть, — цех бы его не увидел в распределении,
         * а маршрут дальше не пошёл бы. Поэтому отказываем до записи,
         * хотя бы запрос пришёл мимо предпросмотра.
         */
        if (!result.confirmable()) {
            throw new IllegalStateException(result.unassignedHint());
        }

        scheduleRowRepository.deleteByOrderIdAndStatus(
                orderId,
                ScheduleRowStatus.DRAFT
        );
        scheduleRowRepository.deleteByOrderIdAndStatus(
                orderId,
                ScheduleRowStatus.CONFIRMED
        );

        CustomerOrder order =
                customerOrderRepository.getReferenceById(orderId);

        for (PlannedRow row : result.plannedRows()) {
            scheduleRowRepository.save(row.toEntity(order));
        }

        return result;
    }

    /**
     * Расчёт и запись графика работ заказа на производство (V56).
     *
     * Нужен групповым заказам-комплектующим: у них нет заказа покупателя,
     * поэтому план им не заводился вовсе, а цех читает именно план — без
     * строк «Монтаж подсборок» не появляется ни в распределении, ни в
     * «Мои операции», и следующая после комплектования операция остаётся
     * невидимой даже после того, как склад внёс все ячейки.
     *
     * Правила те же, что у плана заказа покупателя (запуск, переходы,
     * загрузка этапа), только основание другое: маршрут берётся из
     * техпроцесса самого заказа, а экземплярами служат заказанные изделия.
     */
    @Transactional
    public ScheduleResult confirmProductionOrder(Long productionOrderId) {
        ProductionOrder order = productionOrderRepository
                .findDetailedById(productionOrderId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Заказ на производство не найден: " + productionOrderId
                ));

        List<PlannedRow> planned = plannedRowsOf(order);

        scheduleRowRepository.deleteByProductionOrderIdAndStatus(
                productionOrderId,
                ScheduleRowStatus.DRAFT
        );
        scheduleRowRepository.deleteByProductionOrderIdAndStatus(
                productionOrderId,
                ScheduleRowStatus.CONFIRMED
        );

        for (PlannedRow row : planned) {
            scheduleRowRepository.save(row.toEntity(order));
        }

        return resultOf(order, planned);
    }

    /**
     * Сколько строк графика заведено бы было для заказа сейчас.
     *
     * Нужен вызывающему коду, чтобы не пересобирать существующий план
     * заказа, взятого в работу повторно.
     */
    @Transactional(readOnly = true)
    public int plannedRowCount(ProductionOrder order) {
        return plannedRowsOf(order).size();
    }

    /**
     * Построение строк расчёта по техпроцессу заказа на производство.
     *
     * Отличий от плана заказа покупателя ровно два, и оба следуют из
     * того, что у группового заказа нет позиций:
     *
     *   • маршрут берётся из техпроцесса самого заказа (V40), а не из
     *     базового техпроцесса изделия позиции;
     *   • экземплярами служат заказанные изделия: партия из пяти ячеек
     *     даёт пять экземпляров, и загрузка этапа не оказывается заниженной
     *     в пять раз — ровно та же причина, по которой экземпляр нужен
     *     в плане заказа покупателя.
     *
     * Позиция у таких строк остаётся нулевой: позиций у заказа нет, а
     * различает экземпляры instanceIndex, который для ветви ЗНП входит в
     * уникальный индекс uk_osr_prod_order_instance_operation.
     */
    private List<PlannedRow> plannedRowsOf(ProductionOrder order) {
        if (order.getTechProcess() == null) {
            return List.of();
        }

        List<TechProcessOperation> route =
                orderedRoute(order.getTechProcess().getId());

        if (route.isEmpty()) {
            return List.of();
        }

        OrderPriority priority = resolvePriority(order);
        LocalDate today = LocalDate.now();
        CalendarSnapshot calendar = loadCalendar(
                today.minusMonths(1),
                today.plusMonths(32)
        );

        LocalDate startDate = plusWorkingDays(
                today,
                nullToZero(priority.getStartDays()),
                calendar
        );
        int maxLoadPercent = nullToZero(priority.getMaxLoadPercent());

        Map<StageDay, BigDecimal> occupancy =
                loadExistingOccupancyOfProductionOrder(
                        order.getId(), startDate, calendar);

        int instances = ProductionOrderService.instanceCount(order.getQuantity());
        int transitions = nullToZero(priority.getTransitionDays());
        List<PlannedRow> planned = new ArrayList<>();

        for (int instance = 0; instance < instances; instance++) {
            for (TechProcessOperation node : route) {
                Operation base = node.getOperation();

                if (base == null || base.getId() == null) {
                    continue;
                }

                LocalDate earliest = startDate;

                for (TechProcessOperation predecessor : node.getPredecessors()) {
                    LocalDate predecessorEnd = finishOf(
                            planned, instance, predecessor.getId());

                    if (predecessorEnd == null) {
                        continue;
                    }

                    LocalDate afterTransition = plusWorkingDays(
                            predecessorEnd, transitions, calendar);

                    if (afterTransition.isAfter(earliest)) {
                        earliest = afterTransition;
                    }
                }

                Placement placement = placeOperation(
                        base.getId(),
                        node,
                        earliest,
                        occupancy,
                        maxLoadPercent,
                        calendar
                );

                PlannedRow row = new PlannedRow(
                        0,
                        instance,
                        node.getId(),
                        order.getTechProcess(),
                        base,
                        base.getOrganizationUnit(),
                        node.getPosition() == null ? 0 : node.getPosition(),
                        placement.date,
                        node.getLaborHours(),
                        placement.loadPercent
                );

                planned.add(row);
                occupancy.merge(
                        new StageDay(base.getId(), placement.date),
                        laborHours(node),
                        BigDecimal::add
                );
            }
        }

        return planned;
    }

    /*
     * Приоритет расчёта заказа на производство.
     *
     * Форма группового ЗНП приоритет не спрашивает, поэтому порядок
     * берётся общий для всех: заказ идёт в общую очередь на правилах
     * «обычного» приоритета, а не планируется «в никуда».
     */
    private OrderPriority resolvePriority(ProductionOrder order) {
        OrderPriority priority = order.getPriority();

        if (priority != null) {
            return priority;
        }

        OrderPriority fallback = orderPriorityService.findDefault();

        if (fallback == null) {
            throw new IllegalStateException(
                    "Заказу «" + order.getNumber() + "» не назначен приоритет, "
                            + "а в справочнике нет действующего правила по умолчанию"
            );
        }

        return fallback;
    }

    /* Дата окончания операции этого же экземпляра в текущем расчёте. */
    private static LocalDate finishOf(
            List<PlannedRow> planned,
            int instance,
            Long nodeId
    ) {
        for (PlannedRow row : planned) {
            if (row.instanceIndex == instance
                    && Objects.equals(row.nodeId, nodeId)) {
                return row.plannedDate;
            }
        }

        return null;
    }

    /*
     * Занятость этапов для расчёта плана заказа на производство: свои
     * строки исключены, чужие (и плана заказов покупателя, и прочих
     * групповых заказов) остались.
     */
    private Map<StageDay, BigDecimal> loadExistingOccupancyOfProductionOrder(
            Long productionOrderId,
            LocalDate from,
            CalendarSnapshot calendar
    ) {
        Map<StageDay, BigDecimal> occupancy = new HashMap<>();

        for (OrderScheduleRow row :
                scheduleRowRepository.findConfirmedExcludingProductionOrder(
                        ScheduleRowStatus.CONFIRMED,
                        from,
                        calendar.windowTo(),
                        productionOrderId)) {

            occupancy.merge(
                    new StageDay(row.getOperation().getId(),
                            row.getPlannedDate()),
                    row.getLaborHours(),
                    BigDecimal::add
            );
        }

        return occupancy;
    }

    /*
     * Результат расчёта по заказу на производство — для сообщений
     * вызывающему коду: табличного предпросмотра у этой ветви нет,
     * план заводится сразу при взятии заказа в работу.
     */
    private ScheduleResult resultOf(
            ProductionOrder order,
            List<PlannedRow> planned
    ) {
        OrderPriority priority = resolvePriority(order);
        LocalDate lastDate = null;

        for (PlannedRow row : planned) {
            if (lastDate == null || row.plannedDate.isAfter(lastDate)) {
                lastDate = row.plannedDate;
            }
        }

        List<ScheduleRowView> views = new ArrayList<>();
        String itemName = itemLabelOf(order);

        for (PlannedRow row : planned) {
            views.add(row.toView(
                    ProductionOrderService.instanceCount(order.getQuantity()),
                    itemName,
                    order.getTechProcess()
            ));
        }

        return new ScheduleResult(
                order.getNumber(),
                priority.getName(),
                planned.isEmpty() ? LocalDate.now() : planned.get(0).plannedDate,
                order.getDueDate(),
                null,
                lastDate,
                true,
                planned.isEmpty()
                        ? "Маршрут не построен: техпроцесс заказа пуст"
                        : null,
                planned,
                views,
                List.of(),
                /*
                 * Групповой ЗНП не имеет табличного предпросмотра: его
                 * план заводится автоматически при взятии заказа в
                 * работу, и блокировать этот шаг нельзя — заказ уже
                 * создан складом и ждёт работ. Нехватку исполнителей
                 * здесь закрывает руководитель подразделения в
                 * распределении работ.
                 */
                List.of()
        );
    }

    /* Изделие заказа — подпись экземпляра в таблице предпросмотра. */
    private static String itemLabelOf(ProductionOrder order) {
        ProductClassifier classifier = order.getProductClassifier();

        if (classifier == null) {
            return "Изделие заказа";
        }

        String name = trimToNull(classifier.getDisplayName());

        return name == null ? "Изделие заказа" : name;
    }

    /*
     * Основной расчёт. Ничего не пишет: confirm забирает plannedRows
     * и сохраняет их сам.
     */
    private ScheduleResult calculate(Long orderId) {
        CustomerOrder order = customerOrderRepository
                .findFullById(orderId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Заказ покупателя не найден"
                ));

        OrderPriority priority = order.getPriority();

        if (priority == null) {
            throw new IllegalStateException(
                    "Заказу не назначен приоритет: планирование невозможно"
            );
        }

        LocalDate today = LocalDate.now();

        /*
         * Календарь грузится до расчёта даты запуска: startDays
         * отсчитывается рабочими днями, а рабочий день определяется
         * справочником праздников. Окно берётся с запасом, чтобы
         * перепланирование отдалённого заказа не вышло за снимок.
         */
        CalendarSnapshot calendar = loadCalendar(
                today.minusMonths(1),
                today.plusMonths(32)
        );

        LocalDate startDate = plusWorkingDays(
                today,
                nullToZero(priority.getStartDays()),
                calendar
        );

        LocalDate deadline = order.getDueDate() == null
                ? null
                : minusWorkingDays(
                        order.getDueDate(),
                        nullToZero(priority.getMaxManufacturingDays())
                );

        int maxLoadPercent = nullToZero(priority.getMaxLoadPercent());

        /*
         * Накопленная занятость этапа (операция → дата → часы).
         * Сюда попадают уже внесённые в план чужие заказы и строки
         * текущего расчёта: второй экземпляр того же этапа обязан
         * видеть загрузку, созданную первым, иначе оба влезут в один
         * день. Строки перепланируемого заказа исключены — иначе его
         * же прошлый план удвоил бы загрузку и даты уехали бы в
         * бесконечный сдвиг.
         */
        Map<StageDay, BigDecimal> occupancy =
                loadExistingOccupancy(orderId, startDate, calendar);

        List<PlannedRow> planned = new ArrayList<>();
        List<ScheduleRowView> views = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        /*
         * Этапы, поставленные в график, но не закреплённые ни за одним
         * действующим сотрудником. Список подписывает причину блокировки:
         * диспетчеру нужно назвать операции, а не просто увидеть мёртвую
         * кнопку.
         */
        List<String> unassignedOperations = new ArrayList<>();

        LocalDate lastDate = startDate;
        boolean feasible = true;

        for (CustomerOrderItem item : order.getItems()) {
            int instances = ProductionOrderService.instanceCount(
                    item.getRemainingQuantity()
            );

            TechProcess process = resolveTechProcess(item);

            if (process == null) {
                warnings.add("Позиция «" + itemLabel(item)
                        + "»: у изделия нет базового техпроцесса — "
                        + "она не поставлена в график");
                continue;
            }

            List<TechProcessOperation> route = orderedRoute(process.getId());

            if (route.isEmpty()) {
                warnings.add("Позиция «" + itemLabel(item) + "»: техпроцесс "
                        + process.getCode() + " пуст — операций нет");
                continue;
            }

            for (int instance = 0; instance < instances; instance++) {
                for (TechProcessOperation node : route) {
                    Operation base = node.getOperation();

                    LocalDate earliest = earliestStart(
                            node,
                            startDate,
                            planned,
                            item.getPosition(),
                            instance,
                            priority,
                            calendar
                    );

                    Placement placement = placeOperation(
                            base.getId(),
                            node,
                            earliest,
                            occupancy,
                            maxLoadPercent,
                            calendar
                    );

                    /*
                     * Предупреждение объясняет методику расчёта: при
                     * нулевой мощности этапа загрузка нарисована условной,
                     * и это верно для любого вида работ. Блокирует же
                     * внесение плана только отсутствие закреплённого
                     * исполнителя там, где он нужен: цех получил бы
                     * строку, которую некому закрыть, а заказ уже
                     * числился бы запланированным.
                     */
                    if (!placement.executorBound) {
                        warnings.add("Операция «" + operationName(base)
                                + "» (позиция «" + itemLabel(item)
                                + "»): за ней не закреплено ни одного "
                                + "исполнителя — загрузка рассчитана "
                                + "условно по одному человеку");

                        if (requiresBoundExecutor(base)) {
                            unassignedOperations.add(
                                    operationName(base) + " — «"
                                            + itemLabel(item) + "»");
                        }
                    } else if (placement.capacityEmployees <= 0) {
                        warnings.add("Операция «" + operationName(base)
                                + "» (позиция «" + itemLabel(item)
                                + "»): в день " + ru(placement.date)
                                + " все закреплённые исполнители отсутствуют"
                                + " — загрузка рассчитана условно");
                    }

                    if (placement.shiftedPastHorizon) {
                        warnings.add("Операция «" + operationName(base)
                                + "» (позиция «" + itemLabel(item)
                                + "»): этап загружен выше допустимого "
                                + "предела, дата "
                                + ru(placement.date)
                                + " взята на границе горизонта — "
                                + "заказ может не уложиться в срок");
                    }

                    PlannedRow row = new PlannedRow(
                            item.getPosition(),
                            instance,
                            node.getId(),
                            process,
                            base,
                            base.getOrganizationUnit(),
                            node.getPosition() == null ? 0 : node.getPosition(),
                            placement.date,
                            node.getLaborHours(),
                            placement.loadPercent
                    );

                    planned.add(row);
                    views.add(row.toView(instances, item, process));

                    StageDay key = new StageDay(base.getId(), placement.date);
                    occupancy.merge(key, laborHours(node), BigDecimal::add);

                    if (deadline != null && placement.date.isAfter(deadline)) {
                        feasible = false;
                    }

                    if (placement.date.isAfter(lastDate)) {
                        lastDate = placement.date;
                    }
                }
            }
        }

        String message = buildMessage(
                order,
                priority,
                feasible,
                deadline,
                lastDate
        );

        return new ScheduleResult(
                order.getNumber(),
                priority.getName(),
                startDate,
                order.getDueDate(),
                deadline,
                lastDate,
                feasible,
                message,
                planned,
                views,
                warnings,
                unassignedOperations
        );
    }

    /*
     * Базовый техпроцесс изделия позиции. Позиция хранит только тип
     * (BASIC), поэтому маршрут ищется по изделию классификатора.
     */
    private TechProcess resolveTechProcess(CustomerOrderItem item) {
        if (item.getProductClassifier() == null) {
            return null;
        }

        return techProcessClassifierRepository
                .findBasicForClassifier(
                        item.getProductClassifier().getId()
                )
                .map(TechProcessClassifier::getTechProcess)
                .orElse(null);
    }

    /*
     * Операции маршрута в топологическом порядке (предшественники
     * раньше потомков). findFullById грузит операции, но не DAG-связи,
     * поэтому обход идёт внутри транзакции расчёта.
     */
    private List<TechProcessOperation> orderedRoute(Long processId) {
        TechProcess process = techProcessRepository
                .findFullById(processId)
                .orElse(null);

        if (process == null || process.getOperations().isEmpty()) {
            return List.of();
        }

        List<TechProcessOperation> nodes =
                new ArrayList<>(process.getOperations());

        Map<Long, Integer> indegree = new LinkedHashMap<>();
        Map<Long, TechProcessOperation> byId = new HashMap<>();
        Map<Long, List<TechProcessOperation>> successors = new HashMap<>();

        for (TechProcessOperation node : nodes) {
            byId.put(node.getId(), node);
            indegree.putIfAbsent(node.getId(), 0);

            for (TechProcessOperation predecessor : node.getPredecessors()) {
                if (predecessor.getId() == null) {
                    continue;
                }

                successors
                        .computeIfAbsent(
                                predecessor.getId(),
                                key -> new ArrayList<>()
                        )
                        .add(node);

                indegree.merge(node.getId(), 1, Integer::sum);
            }
        }

        Deque<TechProcessOperation> ready = new ArrayDeque<>();

        indegree.forEach((id, degree) -> {
            if (degree == 0) {
                ready.add(byId.get(id));
            }
        });

        List<TechProcessOperation> ordered = new ArrayList<>();

        while (!ready.isEmpty()) {
            TechProcessOperation node = ready.poll();
            ordered.add(node);

            for (TechProcessOperation next
                    : successors.getOrDefault(node.getId(), List.of())) {

                int left = indegree.merge(next.getId(), -1, Integer::sum);

                if (left == 0) {
                    ready.add(next);
                }
            }
        }

        /*
         * Цикл в DAG (данные повреждены) оставил бы часть операций
         * невозвращённой: дописываем их по позиции, чтобы план не
         * потерял работу молча.
         */
        if (ordered.size() < nodes.size()) {
            Set<Long> placed = new HashSet<>();

            ordered.forEach(node -> placed.add(node.getId()));

            nodes.stream()
                    .filter(node -> !placed.contains(node.getId()))
                    .forEach(ordered::add);
        }

        return ordered;
    }

    /*
     * Самая ранняя дата начала операции: запуск для корневых операций
     * и finish(предшественника) + переходы для остальных. Берётся
     * максимум по всем предшественникам этого же экземпляра.
     *
     * Предшественник сопоставляется со строкой расчёта по ID узла
     * маршрута (nodeId), а не по seq: seq — позиция для показа, и
     * путать её с идентификатором узла означало бы применять переходы
     * не к тем операциям.
     */
    private LocalDate earliestStart(
            TechProcessOperation node,
            LocalDate startDate,
            List<PlannedRow> planned,
            int itemPosition,
            int instance,
            OrderPriority priority,
            CalendarSnapshot calendar
    ) {
        Map<Long, LocalDate> finishByNode = new HashMap<>();

        for (PlannedRow row : planned) {
            if (row.itemPosition == itemPosition
                    && row.instanceIndex == instance) {
                finishByNode.put(row.nodeId, row.plannedDate);
            }
        }

        LocalDate earliest = startDate;
        int transitions = nullToZero(priority.getTransitionDays());

        for (TechProcessOperation predecessor : node.getPredecessors()) {
            LocalDate predecessorEnd =
                    finishByNode.get(predecessor.getId());

            if (predecessorEnd == null) {
                continue;
            }

            LocalDate afterTransition = plusWorkingDays(
                    predecessorEnd,
                    transitions,
                    calendar
            );

            if (afterTransition.isAfter(earliest)) {
                earliest = afterTransition;
            }
        }

        return earliest;
    }

    /*
     * Размещение операции в первой подходящей дате: дата рабочая, а
     * загрузка этапа не превышает допустимый процент.
     *
     * Пустое множество способных исполнителей мощность не обнуляет:
     * этап считается по одному условному человеку, и операция
     * размещается по датам, но расчёт выдаёт предупреждение — план с
     * нулевой мощностью был бы либо вечным сдвигом, либо фиктивной
     * загрузкой, и то и другое хуже явной подсказки диспетчеру.
     */
    private Placement placeOperation(
            Long operationId,
            TechProcessOperation node,
            LocalDate earliest,
            Map<StageDay, BigDecimal> occupancy,
            int maxLoadPercent,
            CalendarSnapshot calendar
    ) {
        List<Long> placeIds = node.getWorkPlaces().stream()
                .map(WorkPlace::getId)
                .filter(Objects::nonNull)
                .toList();

        List<Long> capable = staffBindingRepository.findCapableEmployeeIds(
                operationId,
                placeIds,
                !placeIds.isEmpty()
        );

        BigDecimal labor = laborHours(node);
        BigDecimal limit = new BigDecimal(maxLoadPercent);

        LocalDate date = nextWorkingDay(earliest, calendar);
        LocalDate lastChecked = date;
        int shift = 0;

        while (shift < MAX_SHIFT_DAYS) {
            int present = presentCount(capable, date, calendar);
            BigDecimal capacity = capacityHours(capable, present);

            BigDecimal busy = occupancy.getOrDefault(
                    new StageDay(operationId, date),
                    BigDecimal.ZERO
            );

            BigDecimal loadPercent = percent(busy.add(labor), capacity);
            lastChecked = date;

            if (loadPercent.compareTo(limit) <= 0) {
                return new Placement(
                        date,
                        loadPercent.setScale(0, RoundingMode.HALF_UP)
                                .intValue(),
                        present,
                        false,
                        !capable.isEmpty()
                );
            }

            date = plusWorkingDays(date, 1, calendar);
            shift++;
        }

        int present = presentCount(capable, lastChecked, calendar);

        return new Placement(
                lastChecked,
                percent(
                        occupancy.getOrDefault(
                                new StageDay(operationId, lastChecked),
                                BigDecimal.ZERO
                        ).add(labor),
                        capacityHours(capable, present)
                ).setScale(0, RoundingMode.HALF_UP).intValue(),
                present,
                true,
                !capable.isEmpty()
        );
    }

    /* Присутствующие в этот день сотрудники: способные минус отсутствующие. */
    private int presentCount(
            List<Long> capable,
            LocalDate date,
            CalendarSnapshot calendar
    ) {
        if (capable.isEmpty()) {
            return 0;
        }

        Set<Long> absent = calendar.absentOn(date);

        int count = 0;

        for (Long employeeId : capable) {
            if (!absent.contains(employeeId)) {
                count++;
            }
        }

        return count;
    }

    /*
     * Мощность этапа в часах: люди × рабочий день. Без исполнителей —
     * условные 8 часов (см. placeOperation).
     */
    private BigDecimal capacityHours(List<Long> capable, int presentEmployees) {
        if (capable.isEmpty()) {
            return WORKDAY_HOURS;
        }

        return WORKDAY_HOURS.multiply(new BigDecimal(
                Math.max(presentEmployees, 0)
        ));
    }

    /*
     * Снимок календаря: праздники и отсутствия одним запросом на окно
     * расчёта, а не по запросу на перебираемый день.
     */
    private CalendarSnapshot loadCalendar(LocalDate from, LocalDate to) {
        Set<LocalDate> holidays = new HashSet<>();

        for (HolidayDay holiday :
                holidayDayRepository.findByHolidayDateBetweenOrderByHolidayDateAsc(
                        from,
                        to)) {

            holidays.add(holiday.getHolidayDate());
        }

        Map<LocalDate, Set<Long>> absences = new HashMap<>();

        for (EmployeeAbsence absence :
                employeeAbsenceRepository.findByAbsenceDateBetween(
                        from,
                        to)) {

            absences
                    .computeIfAbsent(
                            absence.getAbsenceDate(),
                            key -> new HashSet<>()
                    )
                    .add(absence.getEmployee().getId());
        }

        return new CalendarSnapshot(from, to, holidays, absences);
    }

    /*
     * Занятость этапов уже внесёнными в план строками: только CONFIRMED
     * и только чужих заказов — строки перепланируемого заказа снимаются
     * и пересчитываются заново.
     */
    private Map<StageDay, BigDecimal> loadExistingOccupancy(
            Long orderId,
            LocalDate from,
            CalendarSnapshot calendar
    ) {
        Map<StageDay, BigDecimal> occupancy = new HashMap<>();

        for (OrderScheduleRow row :
                scheduleRowRepository.findConfirmedExcludingOrder(
                        ScheduleRowStatus.CONFIRMED,
                        from,
                        calendar.windowTo(),
                        orderId)) {

            occupancy.merge(
                    new StageDay(row.getOperation().getId(),
                            row.getPlannedDate()),
                    row.getLaborHours(),
                    BigDecimal::add
            );
        }

        return occupancy;
    }

    /* Рабочий ли день: не выходной и не праздник. */
    private static boolean isWorkingDay(
            LocalDate date,
            CalendarSnapshot calendar
    ) {
        if (date.getDayOfWeek() == java.time.DayOfWeek.SATURDAY
                || date.getDayOfWeek() == java.time.DayOfWeek.SUNDAY) {
            return false;
        }

        return !calendar.holidays().contains(date);
    }

    private static LocalDate nextWorkingDay(
            LocalDate date,
            CalendarSnapshot calendar
    ) {
        LocalDate candidate = date;
        int guard = 0;

        while (!isWorkingDay(candidate, calendar)
                && guard++ < MAX_SHIFT_DAYS) {
            candidate = candidate.plusDays(1);
        }

        return candidate;
    }

    private static LocalDate plusWorkingDays(
            LocalDate from,
            int workingDays,
            CalendarSnapshot calendar
    ) {
        LocalDate candidate = nextWorkingDay(from, calendar);

        for (int day = 0; day < workingDays; day++) {
            candidate = nextWorkingDay(candidate.plusDays(1), calendar);
        }

        return candidate;
    }

    /*
     * Шаг назад по календарю для отсечки изготовления. Границы окна
     * снимка здесь не мешают: праздник раньше окна — редкость, а
     * выходные отсекаются и без справочника.
     */
    private LocalDate minusWorkingDays(LocalDate from, int workingDays) {
        LocalDate candidate = previousWorkingDay(from);

        for (int day = 0; day < workingDays; day++) {
            candidate = previousWorkingDay(candidate.minusDays(1));
        }

        return candidate;
    }

    private static LocalDate previousWorkingDay(LocalDate date) {
        LocalDate candidate = date;

        while (candidate.getDayOfWeek() == java.time.DayOfWeek.SATURDAY
                || candidate.getDayOfWeek() == java.time.DayOfWeek.SUNDAY) {
            candidate = candidate.minusDays(1);
        }

        return candidate;
    }

    private static BigDecimal laborHours(TechProcessOperation node) {
        return node.getLaborHours() == null
                ? BigDecimal.ZERO
                : node.getLaborHours();
    }

    private static BigDecimal percent(BigDecimal busy, BigDecimal capacity) {
        if (capacity == null || capacity.signum() <= 0) {
            return new BigDecimal("1000");
        }

        return busy
                .multiply(new BigDecimal("100"))
                .divide(capacity, 2, RoundingMode.HALF_UP);
    }

    private static String buildMessage(
            CustomerOrder order,
            OrderPriority priority,
            boolean feasible,
            LocalDate deadline,
            LocalDate lastDate
    ) {
        if (order.getDueDate() == null) {
            return "У заказа не указан срок поставки: крайний день "
                    + "изготовления не контролируется.";
        }

        if (feasible) {
            return null;
        }

        return "План выходит за крайний день изготовления ("
                + ru(deadline) + "). Последняя операция — " + ru(lastDate)
                + ", срок отгрузки — " + ru(order.getDueDate())
                + ". Приоритет «" + priority.getName()
                + "» не обеспечивает выпуск к сроку: выберите более "
                + "высокий приоритет или согласуйте перенос срока.";
    }

    private static String operationName(Operation operation) {
        return operation.getOperationName() == null
                ? "Операция"
                : operation.getOperationName().getName();
    }

    /*
     * Виды работ, которые план закрепляет за подразделением, а не за
     * человеком: получение комплектации и распределение работ — личные
     * работы руководителя, контрольные и передаточные проводит тот, кто
     * принимает или передаёт изделие по маршруту (контролёр, кладовщик,
     * мастер участка), а не закреплённый исполнитель операции.
     *
     * Требовать от такой операции привязки сотрудника нельзя: её просто
     * не существует в справочнике закреплений, и заказ нельзя было бы
     * поставить в план ни при каких настройках подразделения.
     */
    private static final Set<String> UNIT_BOUND_TYPE_CODES = Set.of(
            OperationTypeCode.KIT_RECEIPT.name(),
            OperationTypeCode.WORK_DISTRIBUTION.name(),
            OperationTypeCode.CONTROL.name(),
            OperationTypeCode.TRANSFER.name()
    );

    /*
     * Нужен ли операции закреплённый исполнитель, чтобы её строку было
     * кому закрывать. Работы руководителя и его службы (роль-исполнитель
     * «Начальник цеха/отдела», «Руководитель службы») идут тем же путём,
     * что получение комплектации: их проводит сам руководитель, и
     * закрепления у них нет по замыслу.
     */
    private static boolean requiresBoundExecutor(Operation operation) {
        if (operation == null) {
            return false;
        }

        if (operation.getOperationType() != null
                && UNIT_BOUND_TYPE_CODES.contains(
                        operation.getOperationType().getCode())) {
            return false;
        }

        return operation.getExecutorRole() == null
                || !WorkshopDistributionService
                        .SUPERVISOR_EXECUTOR_ROLE_CODES
                        .contains(operation.getExecutorRole().getCode());
    }

    /*
     * Подпись позиции: полное обозначение «как в КД», введённое
     * диспетчером, а при его отсутствии — наименование классификатора
     * (для датчика — его род). Тот же приоритет, что в
     * OrderItemNameView и карточке заказа, чтобы таблица графика не
     * подписывала изделие короче, чем видит её диспетчер в заказе.
     */
    private static String itemLabel(CustomerOrderItem item) {
        String full = trimToNull(item.getFullName());

        if (full != null) {
            return full;
        }

        if (item.getProductClassifier() == null) {
            return "Позиция " + item.getPosition();
        }

        String name = trimToNull(item.getProductClassifier().getDisplayName());

        return name == null ? "Позиция " + item.getPosition() : name;
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static int nullToZero(Integer value) {
        return value == null ? 0 : value;
    }

    private static String ru(LocalDate date) {
        return date == null
                ? "—"
                : date.format(java.time.format.DateTimeFormatter
                        .ofPattern("dd.MM.yyyy"));
    }

    private static String ru(String text) {
        return text == null ? "—" : text;
    }

    /*
     * Пара «операция + дата» — ключ накопления загрузки этапа.
     *
     * Доступна пакету: карту занятости передают в приватный
     * placeOperation, и тесты того же пакета обязаны строить настоящие
     * ключи — подставной класс с теми же полями не совпал бы с этим
     * по equals, и занятость этапа перестала бы влиять на расчёт.
     */
    record StageDay(Long operationId, LocalDate date) {
    }

    private record Placement(
            LocalDate date,
            int loadPercent,
            int capacityEmployees,
            boolean shiftedPastHorizon,

            /*
             * Закреплён ли за операцией хоть один действующий сотрудник.
             * Не то же самое, что нулевой capacityEmployees: там люди
             * есть, но в этот день отсутствуют, и план от этого не
             * становится невозможным — здесь же работу не закроет никто.
             */
            boolean executorBound
    ) {
    }

    /*
     * Календарный снимок окна расчёта: праздничные даты и отсутствия
     * сотрудников, загруженные по одному запросу.
     *
     * Доступен пакету: расчёт даты размещения операции проверяют
     * тесты того же пакета, и им нужен тип параметра приватного
     * placeOperation.
     */
    record CalendarSnapshot(
            LocalDate windowFrom,
            LocalDate windowTo,
            Set<LocalDate> holidays,
            Map<LocalDate, Set<Long>> absencesByDate
    ) {

        Set<Long> absentOn(LocalDate date) {
            return absencesByDate.getOrDefault(date, Set.of());
        }
    }

    /*
     * Строка расчёта: и предпросмотр, и будущая запись в базу.
     * Держит всё, что нужно и для ScheduleRowView, и для
     * OrderScheduleRow, плюс ID узла маршрута для сопоставления DAG.
     */
    static final class PlannedRow {

        final int itemPosition;
        final int instanceIndex;
        final Long nodeId;
        final TechProcess techProcess;
        final Operation operation;
        final OrganizationUnit organizationUnit;
        final int seq;
        final LocalDate plannedDate;
        final BigDecimal laborHours;
        final int loadPercent;

        PlannedRow(
                int itemPosition,
                int instanceIndex,
                Long nodeId,
                TechProcess techProcess,
                Operation operation,
                OrganizationUnit organizationUnit,
                int seq,
                LocalDate plannedDate,
                BigDecimal laborHours,
                int loadPercent
        ) {
            this.itemPosition = itemPosition;
            this.instanceIndex = instanceIndex;
            this.nodeId = nodeId;
            this.techProcess = techProcess;
            this.operation = operation;
            this.organizationUnit = organizationUnit;
            this.seq = seq;
            this.plannedDate = plannedDate;
            this.laborHours = laborHours == null ? BigDecimal.ZERO : laborHours;
            this.loadPercent = loadPercent;
        }

        OrderScheduleRow toEntity(CustomerOrder order) {
            OrderScheduleRow row = new OrderScheduleRow();

            row.setCustomerOrder(order);
            row.setItemPosition(itemPosition);
            row.setInstanceIndex(instanceIndex);
            row.setTechProcess(techProcess);
            row.setOperation(operation);
            row.setOrganizationUnit(organizationUnit);
            row.setSeq(seq);
            row.setPlannedDate(plannedDate);
            row.setLaborHours(laborHours);
            row.setLoadPercent(loadPercent);
            row.setStatus(ScheduleRowStatus.CONFIRMED);

            return row;
        }

        /*
         * Строка плана заказа на производство (V56).
         *
         * Основание другое — остальное те же колонки: цех различает
         * ветви по связи, а не по набору полей, поэтому и распределение,
         * и проведение работают с обеими одинаково.
         */
        OrderScheduleRow toEntity(ProductionOrder order) {
            OrderScheduleRow row = new OrderScheduleRow();

            row.setProductionOrder(order);
            row.setItemPosition(itemPosition);
            row.setInstanceIndex(instanceIndex);
            row.setTechProcess(techProcess);
            row.setOperation(operation);
            row.setOrganizationUnit(organizationUnit);
            row.setSeq(seq);
            row.setPlannedDate(plannedDate);
            row.setLaborHours(laborHours);
            row.setLoadPercent(loadPercent);
            row.setStatus(ScheduleRowStatus.CONFIRMED);

            return row;
        }

        ScheduleRowView toView(
                int totalInstances,
                CustomerOrderItem item,
                TechProcess process
        ) {
            Integer classifierCode = item.getProductClassifier() == null
                    ? null
                    : item.getProductClassifier().getCode();

            String itemName = itemLabel(item);

            return new ScheduleRowView(
                    itemPosition,
                    instanceIndex,
                    (instanceIndex + 1) + "/" + totalInstances,
                    classifierCode,
                    itemName,
                    process.getCode(),
                    seq,
                    null,
                    operationName(operation),
                    organizationUnit == null ? null : organizationUnit.getId(),
                    organizationUnit == null
                            ? "Подразделение не указано"
                            : organizationUnit.getName(),
                    laborHours,
                    plannedDate,
                    loadPercent,
                    null
            );
        }

        /*
         * То же представление для строки плана заказа на производство:
         * изделие берётся из заказа, а не из позиции, позиций у такого
         * заказа нет.
         */
        ScheduleRowView toView(
                int totalInstances,
                String itemName,
                TechProcess process
        ) {
            Integer classifierCode = null;

            return new ScheduleRowView(
                    itemPosition,
                    instanceIndex,
                    (instanceIndex + 1) + "/" + totalInstances,
                    classifierCode,
                    itemName,
                    process.getCode(),
                    seq,
                    null,
                    operationName(operation),
                    organizationUnit == null ? null : organizationUnit.getId(),
                    organizationUnit == null
                            ? "Подразделение не указано"
                            : organizationUnit.getName(),
                    laborHours,
                    plannedDate,
                    loadPercent,
                    null
            );
        }
    }

    /*
     * Результат расчёта для контроллера и шаблона предпросмотра.
     */
    public record ScheduleResult(
            String orderNumber,
            String priorityName,
            LocalDate startDate,
            LocalDate dueDate,
            LocalDate manufacturingDeadline,
            LocalDate lastDate,
            boolean feasible,
            String message,
            List<PlannedRow> plannedRows,
            List<ScheduleRowView> rows,
            List<String> warnings,

            /*
             * Операции, для которых построен график, но за них не
             * закреплено ни одного действующего сотрудника. Пустой
             * список — единственное условие, при котором план можно
             * вносить: одна такая операция блокирует весь заказ,
             * потому что цех не смог бы закрыть её строку, а заказ
             * остался бы запланированным с мёртвым этапом.
             */
            List<String> unassignedOperations
    ) {

        /** План можно внести в заказ: все этапы имеют исполнителя. */
        public boolean confirmable() {
            return unassignedOperations.isEmpty();
        }

        /**
         * Подсказка для заблокированной кнопки «Внести план в заказ»:
         * перечисляет операции, за которые некому взяться.
         */
        public String unassignedHint() {
            if (confirmable()) {
                return null;
            }

            return "План нельзя внести в заказ: за "
                    + unassignedOperations.size()
                    + (unassignedOperations.size() == 1
                            ? " операцией" : " операциями")
                    + " график построен, но ни один исполнитель не"
                    + " закреплён: " + String.join("; ", unassignedOperations)
                    + ". Закрепите исполнителей в разделе «Сотрудники»"
                    + " подразделения и пересчитайте план.";
        }

        /** Дату начала — для подписи в шапке предпросмотра. */
        public String getFormattedStartDate() {
            return ruStatic(startDate);
        }

        public String getFormattedDueDate() {
            return ruStatic(dueDate);
        }

        public String getFormattedDeadline() {
            return ruStatic(manufacturingDeadline);
        }

        public String getFormattedLastDate() {
            return ruStatic(lastDate);
        }

        private static String ruStatic(LocalDate date) {
            return date == null
                    ? "—"
                    : date.format(java.time.format.DateTimeFormatter
                            .ofPattern("dd.MM.yyyy"));
        }
    }
}
