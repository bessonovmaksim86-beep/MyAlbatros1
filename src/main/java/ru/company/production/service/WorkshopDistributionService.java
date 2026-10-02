package ru.company.production.service;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.company.production.dto.AssignableExecutor;
import ru.company.production.dto.AssignedOperationView;
import ru.company.production.dto.DistributionOperation;
import ru.company.production.dto.DistributionRow;
import ru.company.production.dto.OperationInstance;
import ru.company.production.dto.ProductOperationCard;
import ru.company.production.dto.ProductOperationsView;
import ru.company.production.entity.AppUser;
import ru.company.production.entity.CustomerOrder;
import ru.company.production.entity.OperationTypeCode;
import ru.company.production.entity.OrderScheduleRow;
import ru.company.production.entity.Role;
import ru.company.production.entity.ScheduleRowStatus;
import ru.company.production.entity.TechProcess;
import ru.company.production.entity.TechProcessOperation;
import ru.company.production.repository.EmployeeAbsenceRepository;
import ru.company.production.repository.ExecutorDayLoadView;
import ru.company.production.repository.OpenInstanceRowView;
import ru.company.production.repository.OrderScheduleRowRepository;
import ru.company.production.repository.ProductionOrderSerialProjection;
import ru.company.production.repository.TechProcessOperationRepository;
import ru.company.production.repository.TechProcessRepository;
import ru.company.production.repository.UserRepository;
import ru.company.production.repository.WorkshopStaffBindingRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Распределение работ силами подразделения.
 *
 * Руководитель цеха, склада или бюро входного контроля видит операции
 * заказа покупателя, запланированные на своё подразделение, но ещё не
 * распределённые по исполнителям. Мастер раскладывает работу по
 * конкретным изделиям: единица распределения — строка графика
 * (экземпляр), поэтому одинаковая операция в разных строках может
 * уйти разным сотрудникам.
 *
 * Операции получения комплектации (KIT_RECEIPT) в общий список не
 * попадают — это личные работы руководителя, они выводятся отдельно
 * в меню «Мои операции».
 *
 * Исполнители подбираются по привязкам: сотрудник способен выполнить
 * операцию, если он закреплён за самой операцией или за одним из
 * рабочих мест маршрута. Загрузка каждого считается по дням —
 * планировщик уже мог занять часть смены строками CONFIRMED.
 */
@Service
@RequiredArgsConstructor
public class WorkshopDistributionService {

    /** Срок работ по умолчанию: две недели. */
    public static final int DEFAULT_WEEKS = 2;

    /** Верхняя граница срока: дальше план нечитаем, а запрос тяжелее. */
    public static final int MAX_WEEKS = 12;

    /*
     * Горизонт по умолчанию в днях. Равен прежнему жёстко зашитому
     * значению, поэтому счётчики бокового меню и «Мои операции» не
     * меняются: фильтр по неделям относится только к экрану
     * распределения и задаётся отдельным параметром.
     */
    private static final int HORIZON_DAYS = 7 * DEFAULT_WEEKS;
    private static final BigDecimal SHIFT_HOURS = new BigDecimal("8");

    /** Подпись даты проведения в списках экземпляров. */
    private static final DateTimeFormatter RU_DATE_TIME =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    /*
     * Руководящие роли: те, кому планировщик отдаёт получение
     * комплектации своего подразделения как личную работу.
     *
     * Рядом с мастерами цехов, складов и бюро стоят начальники
     * технологического бюро и диспетчерского отдела, руководитель
     * службы качества — у них нет цехового модуля, но операции своего
     * подразделения (в том числе получение комплектации) закрепляются
     * именно за ними, и распределять их больше некому.
     */
    private static final Set<Role> SUPERVISOR_ROLES = Set.of(
            Role.SDP_SPP_MASTER,
            Role.SDP_SA_MASTER,
            Role.SDP_SC_MASTER,
            Role.SDP_ST_MASTER,
            Role.OMTSIK_MASTER,
            Role.OMTSIK_С_MASTER,
            Role.BVK_MASTER,
            Role.SDP_SERVICE_HEAD_TECHNOLOGY,
            Role.SDP_SERVICE_HEAD_DISPATCHER,
            Role.DISPATCHER,
            Role.SK_GENERAL
    );

    private final OrderScheduleRowRepository scheduleRowRepo;
    private final WorkshopStaffBindingRepository bindingRepo;
    private final TechProcessRepository techProcessRepo;
    private final EmployeeAbsenceRepository absenceRepo;
    private final UserRepository userRepository;
    private final WorkshopStaffService staffService;
    private final WorkshopDistributionSerialService serialService;
    private final TechProcessOperationRepository routeNodeRepo;

    /*
     * ============================================================
     * Экран распределения: строка таблицы = изделие, внутри — его
     * операции
     * ============================================================
     */

    /**
     * Очередь распределения, собранная по изделиям.
     *
     * Мастер выбирает работу над конкретным датчиком, поэтому единица
     * показа — позиция заказа («заказ + позиция»), а не пара
     * «операция + дата». Внутри изделия операции стоят своими
     * группами: у каждой своя дата плана, своя трудоёмкость и свой
     * набор способных сотрудников.
     *
     * Способные считаются один раз на пару «операция + дата» на весь
     * экран, а не на каждое изделие: десять изделий одной операции в
     * один день обязаны дать один запрос с загрузкой, иначе страница на
     * сотню изделий ушла бы в сотни обращений к базе.
     */
    @Transactional(readOnly = true)
    public List<ProductOperationsView> findProductOperations(
            Long unitId,
            AppUser supervisor,
            int weeks
    ) {
        if (unitId == null) {
            return List.of();
        }

        LocalDate from = LocalDate.now();
        LocalDate to = from.plusDays(7L * normalizeWeeks(weeks));

        return groupByProduct(
                scheduleRowRepo.findAssignableForUnit(
                        ScheduleRowStatus.CONFIRMED,
                        unitId,
                        OperationTypeCode.KIT_RECEIPT.name(),
                        from,
                        to
                ),
                from,
                to,
                true
        );
    }

    /**
     * Уже закреплённые работы подразделения, собранные по изделиям.
     *
     * Тот же разрез, что у очереди: изделие остаётся строкой, и мастер
     * видит над какими именно датчиками что уже разложено. Способные
     * сотрудники здесь не нужны — снимать назначение можно с работы,
     * привязку которой потом и поправили.
     */
    @Transactional(readOnly = true)
    public List<ProductOperationsView> findAssignedProductOperations(
            Long unitId,
            int weeks
    ) {
        if (unitId == null) {
            return List.of();
        }

        LocalDate from = LocalDate.now();
        LocalDate to = from.plusDays(7L * normalizeWeeks(weeks));

        return groupByProduct(
                scheduleRowRepo.findAssignedForUnit(
                        ScheduleRowStatus.CONFIRMED,
                        unitId,
                        from,
                        to
                ),
                from,
                to,
                false
        );
    }

    /**
     * Строки графика → изделия с их операциями.
     *
     * Флаг {@code withExecutors} различает очередь и распределённые:
     * подбор кандидатов с загрузкой и отсутствиями нужен только там,
     * где мастер ещё выбирает человека.
     */
    private List<ProductOperationsView> groupByProduct(
            List<OrderScheduleRow> rows,
            LocalDate from,
            LocalDate to,
            boolean withExecutors
    ) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }

        Map<Long, ProductionOrderSerialProjection> serialMap =
                serialService.resolve(rows);

        Map<String, String> itemNames =
                serialService.resolveItemNames(rows);

        Map<Long, InstanceBlocker> blockers = blockersOf(rows);

        /*
         * Кэш способных на весь экран: один расчёт на пару
         * «операция + дата» и набор техпроцессов группы.
         */
        Map<String, List<AssignableExecutor>> capableCache =
                new HashMap<>();

        Map<String, List<OrderScheduleRow>> byProduct =
                new LinkedHashMap<>();

        for (OrderScheduleRow row : rows) {
            byProduct
                    .computeIfAbsent(productKey(row),
                            k -> new ArrayList<>())
                    .add(row);
        }

        List<ProductOperationsView> result =
                new ArrayList<>(byProduct.size());

        for (List<OrderScheduleRow> product : byProduct.values()) {
            result.add(toProductView(
                    product,
                    serialMap,
                    itemNames,
                    blockers,
                    from,
                    to,
                    withExecutors,
                    capableCache
            ));
        }

        /*
         * Изделия идут в порядке заказа и позиции: мастер читает
         * таблицу сверху вниз как перечень датчиков, а не как случайный
         * набор дат плана.
         */
        result.sort((a, b) -> {
            int order = nullSafeCompare(a.orderNumber(), b.orderNumber());

            if (order != 0) {
                return order;
            }

            return Integer.compare(
                    a.itemPosition() == null ? 0 : a.itemPosition(),
                    b.itemPosition() == null ? 0 : b.itemPosition()
            );
        });

        return result;
    }

    /**
     * Строки одного изделия → карточка изделия со списком операций.
     *
     * Внутри изделия операции группируются по паре «операция + дата»:
     * именно так работа закрывается одним решением мастера. Порядок
     * LinkedHashMap сохраняет сортировку запроса — дата плана, затем
     * наименование операции.
     */
    private ProductOperationsView toProductView(
            List<OrderScheduleRow> product,
            Map<Long, ProductionOrderSerialProjection> serialMap,
            Map<String, String> itemNames,
            Map<Long, InstanceBlocker> blockers,
            LocalDate from,
            LocalDate to,
            boolean withExecutors,
            Map<String, List<AssignableExecutor>> capableCache
    ) {
        OrderScheduleRow first = product.get(0);
        CustomerOrder order = first.getCustomerOrder();

        Map<String, List<OrderScheduleRow>> byOperation =
                new LinkedHashMap<>();

        for (OrderScheduleRow row : product) {
            byOperation
                    .computeIfAbsent(
                            row.getOperation().getId() + "|"
                                    + row.getPlannedDate(),
                            k -> new ArrayList<>()
                    )
                    .add(row);
        }

        List<ProductOperationCard> cards =
                new ArrayList<>(byOperation.size());

        BigDecimal totalHours = BigDecimal.ZERO;
        long rowCount = 0L;
        long assignedCount = 0L;

        for (List<OrderScheduleRow> group : byOperation.values()) {
            OrderScheduleRow head = group.get(0);

            BigDecimal hours = BigDecimal.ZERO;
            Set<String> orderNumbers = new LinkedHashSet<>();
            boolean assigned = true;
            String executor = null;

            for (OrderScheduleRow row : group) {
                hours = hours.add(row.getLaborHours());

                CustomerOrder rowOrder = row.getCustomerOrder();

                if (rowOrder != null && rowOrder.getNumber() != null) {
                    orderNumbers.add(rowOrder.getNumber());
                }

                if (row.getExecutor() == null) {
                    assigned = false;
                } else {
                    executor = WorkshopStaffService.displayName(
                            row.getExecutor()
                    );
                }
            }

            List<AssignableExecutor> executors = withExecutors
                    ? capableCache.computeIfAbsent(
                            capableKey(group),
                            k -> capableExecutors(
                                    group, head.getPlannedDate(), from, to
                            )
                    )
                    : List.of();

            cards.add(new ProductOperationCard(
                    head.getOperation().getId(),
                    getOperationName(head),
                    head.getPlannedDate(),
                    hours,
                    new ArrayList<>(orderNumbers),
                    group.size(),
                    assigned,
                    executors,
                    buildInstances(group, serialMap, blockers),
                    serialsOf(group, serialMap),
                    executor
            ));

            totalHours = totalHours.add(hours);
            rowCount += group.size();

            if (assigned) {
                assignedCount++;
            }
        }

        return new ProductOperationsView(
                order == null ? null : order.getId(),
                first.getItemPosition(),
                serialsOf(product, serialMap),
                serialService.itemNameOf(first, itemNames),
                order == null ? null : order.getNumber(),
                cards,
                totalHours,
                rowCount,
                assignedCount
        );
    }

    /* Ключ изделия: заказ покупателя и позиция в нём. */
    private static String productKey(OrderScheduleRow row) {
        CustomerOrder order = row.getCustomerOrder();

        return (order == null ? "0" : order.getId())
                + "|" + row.getItemPosition();
    }

    /*
     * Кэш способа подбора: одинаковая операция на одну дату для разных
     * изделий даёт одинаковых кандидатов, если совпадает и набор
     * техпроцессов — именно по ним ищутся рабочие места маршрута.
     */
    private static String capableKey(List<OrderScheduleRow> group) {
        OrderScheduleRow head = group.get(0);

        String processes = group.stream()
                .map(OrderScheduleRow::getTechProcess)
                .filter(tp -> tp != null && tp.getId() != null)
                .map(tp -> tp.getId())
                .distinct()
                .sorted()
                .map(String::valueOf)
                .collect(java.util.stream.Collectors.joining(","));

        return head.getOperation().getId() + "|"
                + head.getPlannedDate() + "|" + processes;
    }

    /** Порядок сравнения подписей: пустая уходит в конец списка. */
    private static int nullSafeCompare(String left, String right) {
        if (java.util.Objects.equals(left, right)) {
            return 0;
        }

        if (left == null) {
            return 1;
        }

        return right == null ? -1 : left.compareTo(right);
    }

    /*
     * ============================================================
     * Плоское распределение: строка таблицы = экземпляр операции
     * ============================================================
     */

    /**
     * Плоская очередь распределения: по одной строке на экземпляр.
     *
     * Работа не сворачивается в группы «операция + дата»: мастер
     * раскладывает по людям конкретные изделия, и каждая строка
     * таблицы получает собственный выбор исполнителя.
     *
     * Способные сотрудники считаются один раз на группу «операция +
     * дата» и переиспользуются для всех экземпляров группы: запросов
     * получается столько же, сколько групп, а не сколько строк.
     *
     * Серийники и наименования подвязываются одним пакетным запросом
     * ко всем экземплярам страницы (см. {@link
     * WorkshopDistributionSerialService}): на каждую строку таблицы
     * база не дёргается.
     */
    @Transactional(readOnly = true)
    public List<DistributionRow> findAssignableRows(
            Long unitId,
            AppUser supervisor
    ) {
        if (unitId == null) {
            return List.of();
        }

        LocalDate from = LocalDate.now();
        LocalDate to = from.plusDays(HORIZON_DAYS);

        List<OrderScheduleRow> rows = scheduleRowRepo
                .findAssignableForUnit(
                        ScheduleRowStatus.CONFIRMED,
                        unitId,
                        OperationTypeCode.KIT_RECEIPT.name(),
                        from,
                        to
                );

        if (rows.isEmpty()) {
            return List.of();
        }

        Map<Long, ProductionOrderSerialProjection> serialMap =
                serialService.resolve(rows);

        /*
         * Группировка по паре «операция + дата» — только ради того,
         * чтобы рассчитать способных один раз на группу. Порядок
         * LinkedHashMap сохраняет сортировку запроса: дата, операция,
         * позиция, экземпляр.
         */
        Map<String, List<OrderScheduleRow>> grouped = new LinkedHashMap<>();

        for (OrderScheduleRow row : rows) {
            String key = row.getOperation().getId() + "|"
                    + row.getPlannedDate();

            grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
        }

        List<DistributionRow> result = new ArrayList<>(rows.size());

        for (List<OrderScheduleRow> group : grouped.values()) {
            List<AssignableExecutor> executors = capableExecutors(
                    group, group.get(0).getPlannedDate(), from, to
            );

            for (OrderScheduleRow row : group) {
                result.add(toRow(
                        row,
                        executors,
                        false,
                        serialMap.get(row.getId())
                ));
            }
        }

        return result;
    }

    /**
     * Плоский список уже распределённых работ: по строке на экземпляр.
     *
     * Тот же принцип, что у очереди: изделие остаётся отдельной
     * строкой, а не растворяется в группе, поэтому снятие назначения
     * снимает его с конкретного экземпляра.
     */
    @Transactional(readOnly = true)
    public List<DistributionRow> findAssignedRows(Long unitId) {
        if (unitId == null) {
            return List.of();
        }

        LocalDate from = LocalDate.now();
        LocalDate to = from.plusDays(HORIZON_DAYS);

        List<OrderScheduleRow> rows = scheduleRowRepo.findAssignedForUnit(
                ScheduleRowStatus.CONFIRMED,
                unitId,
                from,
                to
        );

        Map<Long, ProductionOrderSerialProjection> serialMap =
                serialService.resolve(rows);

        List<DistributionRow> result = new ArrayList<>(rows.size());

        for (OrderScheduleRow row : rows) {
            result.add(toRow(
                    row,
                    List.of(),
                    isKitReceipt(row),
                    serialMap.get(row.getId())
            ));
        }

        return result;
    }

    /**
     * Назначение одного экземпляра на исполнителя.
     *
     * Закрепляет ровно одну строку графика — тот экземпляр, чей
     * {@code select} сохранил мастер. Проверки: строка CONFIRMED, её
     * подразделение совпадает с подразделением руководителя, это не
     * получение комплектации, экземпляр ещё не назначен, исполнитель
     * относится к тому же подразделению.
     */
    @Transactional
    public void assignRow(
            Long rowId,
            Long executorId,
            AppUser supervisor
    ) {
        if (rowId == null) {
            throw new IllegalArgumentException("Экземпляр не указан");
        }

        if (executorId == null) {
            throw new IllegalArgumentException("Выберите исполнителя");
        }

        Long unitId = staffService.workshopUnitId(supervisor);

        OrderScheduleRow row = scheduleRowRepo.findById(rowId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Строка графика не найдена"
                ));

        requireAssignable(row, unitId);

        if (row.getExecutor() != null) {
            throw new IllegalArgumentException(
                    "Экземпляр уже назначен: «"
                            + getOperationName(row) + "»"
            );
        }

        AppUser executor = findById(executorId);

        staffService.requireSameWorkshop(executor, supervisor);

        row.setExecutor(executor);
        row.setAssignedBy(supervisor);
        row.setAssignedAt(java.time.LocalDateTime.now());
        scheduleRowRepo.save(row);
    }

    /**
     * Снятие назначения с одного экземпляра.
     *
     * Работа возвращается в очередь распределения: executor, assignedBy
     * и assignedAt обнуляются только у этой строки, остальные экземпляры
     * той же операции остаются за своими исполнителями.
     */
    @Transactional
    public void unassignRow(Long rowId, AppUser supervisor) {
        if (rowId == null) {
            throw new IllegalArgumentException("Экземпляр не указан");
        }

        Long unitId = staffService.workshopUnitId(supervisor);

        OrderScheduleRow row = scheduleRowRepo.findById(rowId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Строка графика не найдена"
                ));

        if (row.getOrganizationUnit() == null
                || !row.getOrganizationUnit().getId().equals(unitId)) {
            throw new IllegalArgumentException(
                    "Работа относится к другому подразделению"
            );
        }

        if (row.getExecutor() == null) {
            throw new IllegalArgumentException(
                    "Экземпляр ещё не назначен"
            );
        }

        row.setExecutor(null);
        row.setAssignedBy(null);
        row.setAssignedAt(null);
        scheduleRowRepo.save(row);
    }

    /*
     * ============================================================
     * Групповые режимы (сохранены для смежных разделов)
     * ============================================================
     */

    /**
     * Операции, ожидающие распределения, за срок по умолчанию.
     *
     * Перегрузка обязана оставить прежний горизонт: счётчики бокового
     * меню и «Мои операции» считаются по нему, и фильтр на одной
     * странице не должен менять цифру во всём меню.
     */
    public List<DistributionOperation> findAssignableOperations(
            Long unitId,
            AppUser supervisor
    ) {
        return findAssignableOperations(unitId, supervisor, DEFAULT_WEEKS);
    }

    /**
     * Операции, ожидающие распределения, — от сегодня на {@code weeks}
     * недель вперёд.
     *
     * Строки группируются по паре «операция + дата плана»: позиция на
     * десять изделий даёт десять строк одной операции в один день, и
     * раскладывать их по людям мастер должен одним решением.
     *
     * Для каждой группы сразу рассчитываются способные исполнители
     * вместе с их загрузкой и отсутствиями — отдельный запрос по
     * клику не нужен, загрузка нужна до выбора, а не после него.
     * Серийники и наименования подвязываются одним пакетным запросом
     * ко всем строкам срока (см. {@link WorkshopDistributionSerialService}).
     */
    @Transactional(readOnly = true)
    public List<DistributionOperation> findAssignableOperations(
            Long unitId,
            AppUser supervisor,
            int weeks
    ) {
        if (unitId == null) {
            return List.of();
        }

        LocalDate from = LocalDate.now();
        LocalDate to = from.plusDays(7L * normalizeWeeks(weeks));

        List<OrderScheduleRow> rows = scheduleRowRepo
                .findAssignableForUnit(
                        ScheduleRowStatus.CONFIRMED,
                        unitId,
                        OperationTypeCode.KIT_RECEIPT.name(),
                        from,
                        to
                );

        if (rows.isEmpty()) {
            return List.of();
        }

        Map<Long, ProductionOrderSerialProjection> serialMap =
                serialService.resolve(rows);

        Map<String, String> itemNames =
                serialService.resolveItemNames(rows);

        Map<Long, InstanceBlocker> blockers = blockersOf(rows);

        Map<String, List<OrderScheduleRow>> grouped = new LinkedHashMap<>();

        for (OrderScheduleRow row : rows) {
            String key = row.getOperation().getId() + "|"
                    + row.getPlannedDate();

            grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
        }

        List<DistributionOperation> result = new ArrayList<>();

        for (List<OrderScheduleRow> group : grouped.values()) {
            OrderScheduleRow first = group.get(0);

            List<AssignableExecutor> executors = capableExecutors(
                    group, first.getPlannedDate(), from, to
            );

            /* Суммируем трудоёмкость и собираем номера заказов. */
            BigDecimal totalHours = BigDecimal.ZERO;
            Set<String> orderNumbers = new HashSet<>();

            for (OrderScheduleRow row : group) {
                totalHours = totalHours.add(row.getLaborHours());
                CustomerOrder order = row.getCustomerOrder();
                if (order != null && order.getNumber() != null) {
                    orderNumbers.add(order.getNumber());
                }
            }

            result.add(new DistributionOperation(
                    first.getOperation().getId(),
                    getOperationCode(first),
                    getOperationName(first),
                    first.getPlannedDate(),
                    group.size(),
                    totalHours,
                    new ArrayList<>(orderNumbers),
                    executors,
                    buildInstances(group, serialMap, blockers),
                    serialsOf(group, serialMap),
                    itemNamesOf(group, itemNames)
            ));
        }

        return result;
    }

    /**
     * Операции получения комплектации подразделения — личные работы
     * руководителя.
     *
     * В общий список распределения не попадают, но выводятся в меню
     * «Мои операции» вместе с обычными работами, назначенными
     * сотруднику.
     */
    @Transactional(readOnly = true)
    public List<DistributionOperation> findKitReceiptRows(
            Long unitId,
            AppUser supervisor
    ) {
        if (unitId == null) {
            return List.of();
        }

        LocalDate from = LocalDate.now();
        LocalDate to = from.plusDays(HORIZON_DAYS);

        List<OrderScheduleRow> rows = scheduleRowRepo
                .findKitReceiptForUnit(
                        ScheduleRowStatus.CONFIRMED,
                        unitId,
                        OperationTypeCode.KIT_RECEIPT.name(),
                        from,
                        to
                );

        if (rows.isEmpty()) {
            return List.of();
        }

        Map<Long, ProductionOrderSerialProjection> serialMap =
                serialService.resolve(rows);

        Map<String, String> itemNames =
                serialService.resolveItemNames(rows);

        Map<Long, InstanceBlocker> blockers = blockersOf(rows);

        Map<String, List<OrderScheduleRow>> grouped = new LinkedHashMap<>();

        for (OrderScheduleRow row : rows) {
            String key = row.getOperation().getId() + "|"
                    + row.getPlannedDate();
            grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
        }

        List<DistributionOperation> result = new ArrayList<>();

        for (List<OrderScheduleRow> group : grouped.values()) {
            OrderScheduleRow first = group.get(0);
            BigDecimal totalHours = BigDecimal.ZERO;
            Set<String> orderNumbers = new HashSet<>();

            for (OrderScheduleRow row : group) {
                totalHours = totalHours.add(row.getLaborHours());
                CustomerOrder order = row.getCustomerOrder();
                if (order != null && order.getNumber() != null) {
                    orderNumbers.add(order.getNumber());
                }
            }

            result.add(new DistributionOperation(
                    first.getOperation().getId(),
                    getOperationCode(first),
                    getOperationName(first),
                    first.getPlannedDate(),
                    group.size(),
                    totalHours,
                    new ArrayList<>(orderNumbers),
                    List.of(),
                    buildInstances(group, serialMap, blockers),
                    serialsOf(group, serialMap),
                    itemNamesOf(group, itemNames)
            ));
        }

        return result;
    }

    /**
     * Назначение операции на исполнителя.
     *
     * Все строки группы («операция + дата») закрепляются за одним
     * сотрудником. Устанавливаются executor, assignedBy и assignedAt.
     *
     * Проверки: строки должны быть CONFIRMED, подразделение —
     * совпадать с подразделением руководителя, операция — не быть
     * KIT_RECEIPT (его нельзя переложить на исполнителя).
     */
    @Transactional
    public void assign(
            Long operationId,
            LocalDate plannedDate,
            Long executorId,
            AppUser supervisor
    ) {
        if (operationId == null) {
            throw new IllegalArgumentException("Операция не указана");
        }

        if (executorId == null) {
            throw new IllegalArgumentException("Выберите исполнителя");
        }

        Long unitId = staffService.workshopUnitId(supervisor);

        List<OrderScheduleRow> group = scheduleRowRepo
                .findGroupRows(
                        ScheduleRowStatus.CONFIRMED,
                        unitId,
                        operationId,
                        plannedDate
                );

        if (group.isEmpty()) {
            throw new IllegalArgumentException(
                    "Не найдено строк для назначения"
            );
        }

        /*
         * Проверяем, что ни одна строка ещё не распределена —
         * иначе мастер мог бы переназначить уже назначенное.
         */
        for (OrderScheduleRow row : group) {
            if (row.getExecutor() != null) {
                throw new IllegalArgumentException(
                        "Операция уже назначена: «"
                                + getOperationName(row) + "»"
                );
            }
        }

        /*
         * Проверяем, что ни одна строка не относится к KIT_RECEIPT —
         * такие операции назначаются напрямую, а не распределяются.
         */
        for (OrderScheduleRow row : group) {
            if (isKitReceipt(row)) {
                throw new IllegalArgumentException(
                        "Операция получения комплектации не распределяется"
                );
            }
        }

        AppUser executor = findById(executorId);

        staffService.requireSameWorkshop(executor, supervisor);

        for (OrderScheduleRow row : group) {
            row.setExecutor(executor);
            row.setAssignedBy(supervisor);
            row.setAssignedAt(java.time.LocalDateTime.now());
            scheduleRowRepo.save(row);
        }
    }

    /**
     * Снятие назначения с операции.
     *
     * Возвращает строки в нераспределённое состояние: executor,
     * assignedBy и assignedAt обнуляются.
     */
    @Transactional
    public void unassign(
            Long operationId,
            LocalDate plannedDate,
            AppUser supervisor
    ) {
        if (operationId == null) {
            throw new IllegalArgumentException("Операция не указана");
        }

        Long unitId = staffService.workshopUnitId(supervisor);

        List<OrderScheduleRow> group = scheduleRowRepo
                .findGroupRows(
                        ScheduleRowStatus.CONFIRMED,
                        unitId,
                        operationId,
                        plannedDate
                );

        if (group.isEmpty()) {
            throw new IllegalArgumentException(
                    "Не найдено строк для снятия"
            );
        }

        for (OrderScheduleRow row : group) {
            if (row.getExecutor() == null) {
                continue;
            }
            row.setExecutor(null);
            row.setAssignedBy(null);
            row.setAssignedAt(null);
            scheduleRowRepo.save(row);
        }
    }

    /**
     * Назначение операции конкретного изделия.
     *
     * Карточка операции в раскрытой строке-изделии закрепляет работу за
     * исполнителем в масштабе одного датчика: все экземпляры пары
     * «операция + дата» этого изделия уходят одному сотруднику, а те же
     * операции соседних изделий остаются чужими решениями мастера.
     *
     * Проверки собраны вокруг одной выборки строк: они обязаны
     * существовать, принадлежать подразделению руководителя, не быть
     * получением комплектации и ещё не иметь исполнителя.
     */
    @Transactional
    public void assignProductOperation(
            Long operationId,
            LocalDate plannedDate,
            Long orderId,
            Integer itemPosition,
            Long executorId,
            AppUser supervisor
    ) {
        if (operationId == null || orderId == null
                || itemPosition == null) {
            throw new IllegalArgumentException(
                    "Операция изделия не указана"
            );
        }

        if (executorId == null) {
            throw new IllegalArgumentException("Выберите исполнителя");
        }

        Long unitId = staffService.workshopUnitId(supervisor);

        List<OrderScheduleRow> group = scheduleRowRepo
                .findProductOperationRows(
                        ScheduleRowStatus.CONFIRMED,
                        unitId,
                        operationId,
                        plannedDate,
                        orderId,
                        itemPosition
                );

        if (group.isEmpty()) {
            throw new IllegalArgumentException(
                    "Не найдено операций этого изделия для назначения"
            );
        }

        for (OrderScheduleRow row : group) {
            requireAssignable(row, unitId);

            if (row.getExecutor() != null) {
                throw new IllegalArgumentException(
                        "Операция уже назначена: «"
                                + getOperationName(row) + "»"
                );
            }
        }

        AppUser executor = findById(executorId);

        staffService.requireSameWorkshop(executor, supervisor);

        java.time.LocalDateTime now = java.time.LocalDateTime.now();

        for (OrderScheduleRow row : group) {
            row.setExecutor(executor);
            row.setAssignedBy(supervisor);
            row.setAssignedAt(now);
            scheduleRowRepo.save(row);
        }
    }

    /**
     * Снятие назначения с операции конкретного изделия.
     *
     * Работа возвращается в очередь распределения только у этого
     * изделия: соседние датчики той же операции остаются за своими
     * исполнителями, иначе снятие одной кнопки переписывало бы весь
     * день плана.
     */
    @Transactional
    public void unassignProductOperation(
            Long operationId,
            LocalDate plannedDate,
            Long orderId,
            Integer itemPosition,
            AppUser supervisor
    ) {
        if (operationId == null || orderId == null
                || itemPosition == null) {
            throw new IllegalArgumentException(
                    "Операция изделия не указана"
            );
        }

        Long unitId = staffService.workshopUnitId(supervisor);

        List<OrderScheduleRow> group = scheduleRowRepo
                .findProductOperationRows(
                        ScheduleRowStatus.CONFIRMED,
                        unitId,
                        operationId,
                        plannedDate,
                        orderId,
                        itemPosition
                );

        if (group.isEmpty()) {
            throw new IllegalArgumentException(
                    "Не найдено операций этого изделия для снятия"
            );
        }

        boolean released = false;

        for (OrderScheduleRow row : group) {
            if (row.getExecutor() == null) {
                continue;
            }

            row.setExecutor(null);
            row.setAssignedBy(null);
            row.setAssignedAt(null);
            scheduleRowRepo.save(row);
            released = true;
        }

        if (!released) {
            throw new IllegalArgumentException(
                    "Операция изделия ещё не назначена"
            );
        }
    }

    /*
     * ============================================================
     * Уже распределённые работы и «Мои операции»
     * ============================================================
     */

    /**
     * Операции подразделения, уже закреплённые за исполнителями.
     *
     * Второй блок того же раздела: снятие назначения нужно там же,
     * где и назначение, иначе мастер искал бы кнопку в другом месте.
     */
    public List<AssignedOperationView> findAssignedOperations(Long unitId) {
        return findAssignedOperations(unitId, DEFAULT_WEEKS);
    }

    /**
     * Уже закреплённые работы подразделения за выбранный срок в
     * неделях. Второй блок того же раздела: снятие назначения нужно
     * там же, где и назначение.
     */
    @Transactional(readOnly = true)
    public List<AssignedOperationView> findAssignedOperations(
            Long unitId,
            int weeks
    ) {
        if (unitId == null) {
            return List.of();
        }

        LocalDate from = LocalDate.now();
        LocalDate to = from.plusDays(7L * normalizeWeeks(weeks));

        return groupAssigned(
                scheduleRowRepo.findAssignedForUnit(
                        ScheduleRowStatus.CONFIRMED,
                        unitId,
                        from,
                        to
                ),
                "Распределено"
        );
    }

    /**
     * Работы, закреплённые за конкретным пользователем.
     *
     * Исполнитель видит только строки, где он записан в
     * {@code executor_id}. Руководитель того же подразделения видит
     * сверх того получение комплектации: такие строки планировщик
     * закрепляет за подразделением, а не за человеком, и в очереди
     * распределения они не участвуют — их некому переложить.
     *
     * Для руководителя роль берётся из учётной записи, а не из
     * аргумента: администратор, зашедший в чужой интерфейс, получает
     * список того пользователя, под которого выполнен вход.
     */
    @Transactional(readOnly = true)
    public List<AssignedOperationView> findMyOperations(AppUser user) {
        if (user == null || user.getId() == null) {
            return List.of();
        }

        LocalDate from = LocalDate.now();
        LocalDate to = from.plusDays(HORIZON_DAYS);

        List<AssignedOperationView> result = new ArrayList<>(
                groupAssigned(
                        scheduleRowRepo.findForExecutor(
                                user.getId(),
                                from,
                                to
                        ),
                        null
                )
        );

        if (!isSupervisor(user)) {
            return result;
        }

        Long unitId = staffService.workshopUnitId(user);

        if (unitId == null) {
            return result;
        }

        result.addAll(groupAssigned(
                scheduleRowRepo.findOwnKitReceipt(
                        ScheduleRowStatus.CONFIRMED,
                        List.of(unitId),
                        OperationTypeCode.KIT_RECEIPT.name(),
                        from,
                        to
                ),
                "Получение комплектации"
        ));

        result.sort((a, b) -> {
            int date = a.plannedDate().compareTo(b.plannedDate());
            return date != 0 ? date : a.title().compareTo(b.title());
        });

        return result;
    }

    /**
     * Проведение экземпляра операции — отметка факта выполнения.
     *
     * Право проводить закреплено за исполнителем, на которого строка
     * закреплена (V44): руководитель работу назначает, но не закрывает.
     * Поэтому проверяется совпадение {@code executor} с тем, кто
     * проводит, а {@code completed_by} фиксирует автора отметки
     * отдельно от назначения.
     *
     * Проведение поштучное: строка — один экземпляр одной позиции, и
     * закрывается она своим фактом. Повторное проведение запрещено —
     * иначе дата факта перетёрлась бы, и «сколько сделано» стало бы
     * неотличимо от «когда пересохраняли».
     */
    @Transactional
    public void completeRow(Long rowId, AppUser performer) {
        if (rowId == null) {
            throw new IllegalArgumentException("Экземпляр не указан");
        }

        if (performer == null || performer.getId() == null) {
            throw new IllegalStateException("Пользователь не определён");
        }

        OrderScheduleRow row = scheduleRowRepo.findById(rowId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Строка графика не найдена"
                ));

        if (row.getExecutor() == null
                || !row.getExecutor().getId().equals(performer.getId())) {
            throw new IllegalStateException(
                    "Провести работу может только исполнитель, "
                            + "за которым она закреплена"
            );
        }

        if (row.getCompletedAt() != null) {
            throw new IllegalStateException(
                    "Экземпляр уже проведён "
                            + row.getCompletedAt().format(RU_DATE_TIME)
            );
        }

        /*
         * Зависимость маршрута проверяется здесь же, а не только
         * цветом в списке: страница «Мои операции» могла быть открыта
         * до того, как предшественника разблокировали или, наоборот,
         * перепланировали, и одна только кнопка в интерфейсе не
         * удержала бы от проведения работы раньше времени.
         */
        InstanceBlocker blocker = blockersOf(List.of(row))
                .getOrDefault(row.getId(), InstanceBlocker.FREE);

        if (blocker.blocked()) {
            throw new IllegalStateException(
                    blocker.blockerLabel() == null
                            || blocker.blockerLabel().isBlank()
                            ? "Нельзя провести: предшествующая операция ещё не закрыта"
                            : "Нельзя провести: сначала закройте операцию «"
                                    + blocker.blockerLabel().trim() + "»"
            );
        }

        row.setCompletedAt(java.time.LocalDateTime.now());
        row.setCompletedBy(performer);
        scheduleRowRepo.save(row);
    }

    /**
     * Руководящая ли роль: ей принадлежат личные операции получения
     * комплектации подразделения.
     */
    public boolean isSupervisor(AppUser user) {
        return user != null
                && user.getRole() != null
                && SUPERVISOR_ROLES.contains(user.getRole());
    }

    /**
     * Закреплённые работы пользователя — счётчик пункта «Мои
     * операции» в боковом меню.
     *
     * Считается запросом, а не размером списка {@link #findMyOperations}:
     * меню рисуется на каждой странице модуля, а подбор кандидатов с
     * их загрузкой и выводом экземпляров ради одной цифры в меню стоил
     * бы десятков запросов на каждую загрузку экрана.
     *
     * Граница та же, что у самого списка: строки, закреплённые за
     * человеком напрямую, плюс получение комплектации своего
     * подразделения — только для руководящей роли.
     */
    @Transactional(readOnly = true)
    public long countMyOperations(AppUser user) {
        if (user == null || user.getId() == null) {
            return 0L;
        }

        LocalDate from = LocalDate.now();
        LocalDate to = from.plusDays(HORIZON_DAYS);

        long total = scheduleRowRepo.countForExecutor(
                user.getId(), from, to
        );

        if (!isSupervisor(user)) {
            return total;
        }

        Long unitId = staffService.workshopUnitId(user);

        if (unitId == null) {
            return total;
        }

        return total + scheduleRowRepo.countOwnKitReceipt(
                ScheduleRowStatus.CONFIRMED,
                List.of(unitId),
                OperationTypeCode.KIT_RECEIPT.name(),
                from,
                to
        );
    }

    /**
     * Нераспределённые работы подразделения за горизонт — счётчик
     * бокового меню.
     */
    @Transactional(readOnly = true)
    public long countAssignable(Long unitId) {
        if (unitId == null) {
            return 0L;
        }

        LocalDate from = LocalDate.now();

        return scheduleRowRepo.countAssignableForUnit(
                ScheduleRowStatus.CONFIRMED,
                unitId,
                OperationTypeCode.KIT_RECEIPT.name(),
                from,
                from.plusDays(HORIZON_DAYS)
        );
    }

    /**
     * Строки → группы «операция + дата» с подписью исполнителя.
     *
     * Одна и та же сборка нужна и разделу распределения, и «Мои
     * операции»: различается только подпись природы группы — там
     * «Распределено», там «Получение комплектации», а для исполнителя
     * источник не подписывается вовсе.
     */
    private List<AssignedOperationView> groupAssigned(
            List<OrderScheduleRow> rows,
            String source
    ) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }

        Map<Long, ProductionOrderSerialProjection> serialMap =
                serialService.resolve(rows);

        Map<String, String> itemNames =
                serialService.resolveItemNames(rows);

        Map<Long, InstanceBlocker> blockers = blockersOf(rows);

        Map<String, List<OrderScheduleRow>> grouped = new LinkedHashMap<>();

        for (OrderScheduleRow row : rows) {
            String key = row.getOperation().getId() + "|"
                    + row.getPlannedDate();

            grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
        }

        List<AssignedOperationView> result = new ArrayList<>();

        for (List<OrderScheduleRow> group : grouped.values()) {
            OrderScheduleRow first = group.get(0);
            BigDecimal totalHours = BigDecimal.ZERO;
            Set<String> orderNumbers = new HashSet<>();

            for (OrderScheduleRow row : group) {
                totalHours = totalHours.add(row.getLaborHours());

                CustomerOrder order = row.getCustomerOrder();
                if (order != null && order.getNumber() != null) {
                    orderNumbers.add(order.getNumber());
                }
            }

            result.add(new AssignedOperationView(
                    first.getOperation().getId(),
                    getOperationCode(first),
                    getOperationName(first),
                    first.getPlannedDate(),
                    group.size(),
                    totalHours,
                    new ArrayList<>(orderNumbers),
                    first.getExecutor() == null
                            ? null
                            : WorkshopStaffService.displayName(
                                    first.getExecutor()
                            ),
                    source,
                    buildInstances(group, serialMap, blockers),
                    serialsOf(group, serialMap),
                    itemNamesOf(group, itemNames)
            ));
        }

        return result;
    }

    /*
     * ============================================================
     * Вспомогательные методы
     * ============================================================
     */

    /**
     * Способные исполнители для группы строк одной операции и даты.
     *
     * Метод поднят из тела группировщика, чтобы его переиспользовали и
     * групповой, и плоский режимы распределения: расчёт привязок,
     * загрузки и отсутствий в них идентичен.
     */
    private List<AssignableExecutor> capableExecutors(
            List<OrderScheduleRow> group,
            LocalDate plannedDate,
            LocalDate from,
            LocalDate to
    ) {
        OrderScheduleRow first = group.get(0);
        Long operationId = first.getOperation().getId();

        /*
         * Собираем все techProcess.id из строк группы —
         * findRouteWorkPlaceIds ищет рабочие места маршрута
         * по техпроцессам, к которым относится операция.
         * Пустой список processIds вернул бы пустое множество.
         */
        List<Long> processIds = group.stream()
                .map(OrderScheduleRow::getTechProcess)
                .filter(tp -> tp != null && tp.getId() != null)
                .map(tp -> tp.getId())
                .distinct()
                .toList();

        /*
         * Рабочие места маршрута для операции —
         * если пусто, findCapableEmployeeIds учтёт только
         * прямые привязки к операции.
         */
        List<Long> wps = techProcessRepo
                .findRouteWorkPlaceIds(operationId, processIds);
        boolean hasPlaces = !wps.isEmpty();

        List<Long> capableIds = bindingRepo
                .findCapableEmployeeIds(operationId, wps, hasPlaces);

        return buildExecutors(capableIds, plannedDate, from, to);
    }

    /**
     * Строка графика → плоская строка таблицы распределения.
     *
     * Для очереди распределения передаётся список способных, и поля
     * исполнителя остаются пустыми; для уже распределённых список
     * пуст, а исполнитель подписывается по учётной записи.
     *
     * Серийный номер и полное наименование приходят из {@code serial}
     * — проекции выпуска для этого экземпляра. Она null, пока выпуск
     * не создан: тогда поля остаются пустыми, и таблица показывает
     * плановую подпись {@code itemLabel}.
     */
    private DistributionRow toRow(
            OrderScheduleRow row,
            List<AssignableExecutor> executors,
            boolean kitReceipt,
            ProductionOrderSerialProjection serial
    ) {
        CustomerOrder order = row.getCustomerOrder();

        return new DistributionRow(
                row.getId(),
                instanceLabel(row),
                order == null ? null : order.getNumber(),
                getOperationCode(row),
                getOperationName(row),
                row.getPlannedDate(),
                row.getLaborHours(),
                executors,
                row.getExecutor() == null ? null : row.getExecutor().getId(),
                row.getExecutor() == null
                        ? null
                        : WorkshopStaffService.displayName(row.getExecutor()),
                kitReceipt,
                serial == null ? null : serial.getFullNumber(),
                serial == null ? null : serial.getDisplayName()
        );
    }

    /**
     * Идентификатор изделия: «поз. 3 · экз. 2».
     *
     * Серийного номера на плановой стадии нет (заказ планируется до
     * выпуска, V43), поэтому экземпляр подписывается позицией и
     * номером внутри позиции. Когда выпуск создан, рядом появляется
     * серийный номер (см. {@link DistributionRow#productNumberLabel()}).
     */
    private String instanceLabel(OrderScheduleRow row) {
        int position = row.getItemPosition() == null
                ? 0
                : row.getItemPosition();
        int index = row.getInstanceIndex() == null
                ? 0
                : row.getInstanceIndex();

        return "поз. " + position + " · экз. " + (index + 1);
    }

    /**
     * Экземпляр относится к получению комплектации — распределению не
     * подлежит, это личная работа руководителя.
     */
    private boolean isKitReceipt(OrderScheduleRow row) {
        return row.getOperation() != null
                && row.getOperation().getOperationType() != null
                && OperationTypeCode.KIT_RECEIPT.name().equals(
                        row.getOperation().getOperationType().getCode());
    }

    /**
     * Проверка, что строку вообще можно назначить: она в плане, её
     * подразделение совпадает с подразделением руководителя и это не
     * получение комплектации.
     */
    private void requireAssignable(OrderScheduleRow row, Long unitId) {
        if (row.getStatus() != ScheduleRowStatus.CONFIRMED) {
            throw new IllegalStateException(
                    "Назначать можно только внесённые в план работы"
            );
        }

        if (row.getOrganizationUnit() == null
                || !row.getOrganizationUnit().getId().equals(unitId)) {
            throw new IllegalArgumentException(
                    "Работа относится к другому подразделению"
            );
        }

        if (isKitReceipt(row)) {
            throw new IllegalArgumentException(
                    "Операция получения комплектации не распределяется"
            );
        }
    }

    /**
     * Экземпляры группы «операция + дата» — по одной записи на
     * каждую строку графика.
     *
     * Группа показывается одной строкой таблицы, но закрывается
     * поштучно: позиция на десять изделий — десять фактов. Поэтому
     * у группы появляется список экземпляров, и каждый несёт id
     * строки — ключ проведения.
     *
     * Провести экземпляр можно, когда он закреплён за исполнителем
     * и ещё не закрыт. Строки очереди распределения (DRAFT) и
     * получения комплектации исполнителя не имеют — для них
     * canComplete ложна.
     */
    private List<OperationInstance> buildInstances(
            List<OrderScheduleRow> group,
            Map<Long, ProductionOrderSerialProjection> serialMap,
            Map<Long, InstanceBlocker> blockers
    ) {
        List<OperationInstance> instances = new ArrayList<>();

        for (OrderScheduleRow row : group) {
            CustomerOrder order = row.getCustomerOrder();
            ProductionOrderSerialProjection serial = serialMap == null
                    ? null
                    : serialMap.get(row.getId());
            InstanceBlocker blocker = blockers == null
                    ? InstanceBlocker.FREE
                    : blockers.getOrDefault(row.getId(),
                            InstanceBlocker.FREE);

            instances.add(new OperationInstance(
                    row.getId(),
                    row.getItemPosition() == null
                            ? 0
                            : row.getItemPosition(),
                    row.getInstanceIndex() == null
                            ? 0
                            : row.getInstanceIndex(),
                    order == null ? null : order.getNumber(),
                    row.getLaborHours(),
                    row.getCompletedAt() != null,
                    row.getCompletedAt() == null
                            ? null
                            : row.getCompletedAt().format(RU_DATE_TIME),
                    row.getExecutor() != null
                            && row.getCompletedAt() == null
                            && !blocker.blocked(),
                    serial == null ? null : serial.getFullNumber(),
                    serial == null ? null : serial.getDisplayName(),
                    blocker.blocked(),
                    blocker.blockerLabel()
            ));
        }

        return instances;
    }

    /*
     * ============================================================
     * Зависимости маршрута: работу нельзя провести раньше времени
     * ============================================================
     */

    /**
     * Итог проверки зависимостей одного экземпляра.
     *
     * @param blocked      есть незакрытая предшествующая операция
     * @param blockerLabel её наименование — по нему исполнитель
     *                     понимает, чего именно ждёт работа
     */
    private record InstanceBlocker(boolean blocked, String blockerLabel) {

        /** Экземпляр никому не должен: самая частая ситуация. */
        static final InstanceBlocker FREE =
                new InstanceBlocker(false, null);
    }

    /**
     * Зависимости сразу для всех строк списка — по одному запросу
     * на экран вместо двух на каждую строку.
     *
     * Узлы маршрутов читаются по техпроцессам строк, состояние
     * строк — по заказам. Предшественник закрыт, если найдена
     * проведённая строка той же пары «техпроцесс + операция» того же
     * экземпляра того же заказа.
     *
     * Ориентир — зависимости маршрута (V7), а не порядок seq:
     * планировщик считает даты по зависимостям, и две работы одного
     * этапа могут стоять в любом порядке, поэтому блок по номеру
     * строки тормозил бы операцию, которая никого не ждёт.
     */
    private Map<Long, InstanceBlocker> blockersOf(
            List<OrderScheduleRow> rows
    ) {
        if (rows == null || rows.isEmpty()) {
            return Map.of();
        }

        Set<Long> processIds = new HashSet<>();
        Set<Long> orderIds = new HashSet<>();

        for (OrderScheduleRow row : rows) {
            TechProcess process = row.getTechProcess();

            if (process != null && process.getId() != null) {
                processIds.add(process.getId());
            }

            CustomerOrder order = row.getCustomerOrder();

            if (order != null && order.getId() != null) {
                orderIds.add(order.getId());
            }
        }

        if (processIds.isEmpty() || orderIds.isEmpty()) {
            return Map.of();
        }

        /*
         * Узлы: пара самой операции и перечень пар её
         * предшественников. Наименование узла запоминается сразу —
         * оно понадобится как подпись причины блокировки.
         */
        Map<String, Set<String>> pairsByNode = new HashMap<>();
        Map<String, String> labelsByPair = new HashMap<>();

        for (TechProcessOperation node
                : routeNodeRepo
                        .findAllWithPredecessorsForProcesses(
                                processIds)) {
            if (node.getTechProcess() == null
                    || node.getOperation() == null) {
                continue;
            }

            String pair = routePairKey(
                    node.getTechProcess().getId(),
                    node.getOperation().getId()
            );

            labelsByPair.putIfAbsent(pair, operationLabel(node));

            Set<String> predecessors = pairsByNode.computeIfAbsent(
                    pair, k -> new LinkedHashSet<>()
            );

            for (TechProcessOperation dep : node.getPredecessors()) {
                if (dep.getTechProcess() == null
                        || dep.getOperation() == null) {
                    continue;
                }

                String depPair = routePairKey(
                        dep.getTechProcess().getId(),
                        dep.getOperation().getId()
                );

                predecessors.add(depPair);
                labelsByPair.putIfAbsent(depPair, operationLabel(dep));
            }
        }

        /*
         * Состояние строк заказов: закрытая пара перекрывает
         * открытую. После перепланирования по одной работе висят
         * проведённая строка прежнего плана и непроведённая строка
         * черновика — работа сделана, и блокировать её по черновику
         * нельзя.
         */
        Set<String> closedKeys = new HashSet<>();
        Set<String> openKeys = new HashSet<>();

        for (OpenInstanceRowView view
                : scheduleRowRepo.findRouteRowsOfOrders(orderIds)) {
            String key = instanceKey(
                    view.getOrderNumber(),
                    view.getItemPosition(),
                    view.getInstanceIndex(),
                    routePairKey(
                            view.getTechProcessId(),
                            view.getOperationId()
                    )
            );

            if (view.getCompletedAt() != null) {
                closedKeys.add(key);
            } else {
                openKeys.add(key);
            }
        }

        if (openKeys.isEmpty() || pairsByNode.isEmpty()) {
            return Map.of();
        }

        Map<Long, InstanceBlocker> result = new HashMap<>();

        for (OrderScheduleRow row : rows) {
            TechProcess process = row.getTechProcess();
            CustomerOrder order = row.getCustomerOrder();

            if (process == null || order == null
                    || row.getOperation() == null
                    || row.getCompletedAt() != null) {
                continue;
            }

            Set<String> predecessors = pairsByNode.get(routePairKey(
                    process.getId(),
                    row.getOperation().getId()
            ));

            if (predecessors == null || predecessors.isEmpty()) {
                continue;
            }

            for (String predecessor : predecessors) {
                String key = instanceKey(
                        order.getNumber(),
                        row.getItemPosition(),
                        row.getInstanceIndex(),
                        predecessor
                );

                if (openKeys.contains(key)
                        && !closedKeys.contains(key)) {
                    result.put(row.getId(), new InstanceBlocker(
                            true,
                            labelsByPair.get(predecessor)
                    ));
                    break;
                }
            }
        }

        return result;
    }

    /** Ключ пары «техпроцесс + операция» внутри маршрута. */
    private static String routePairKey(
            Long processId,
            Long operationId
    ) {
        return processId + ":" + operationId;
    }

    /**
     * Ключ экземпляра работы: заказ, позиция, номер внутри позиции
     * и пара маршрута.
     *
     * Экземпляр в ключе обязателен: позиция на десять изделий
     * закрывается десятью фактами, и разблокировать нужно только то
     * изделие, по которому предшественник действительно проведён.
     */
    private static String instanceKey(
            String orderNumber,
            Integer itemPosition,
            Integer instanceIndex,
            String pairKey
    ) {
        return orderNumber + "|" + itemPosition + "|" + instanceIndex
                + "|" + pairKey;
    }

    /** Наименование операции узла — подпись причины блокировки. */
    private static String operationLabel(
            TechProcessOperation node
    ) {
        if (node.getOperation() == null
                || node.getOperation().getOperationName() == null) {
            return null;
        }

        return node.getOperation().getOperationName().getName();
    }

    /**
     * Способные исполнители для группы — с загрузкой и отсутствиями.
     *
     * busyHours относится к дате плана: планировщик уже занял время
     * человека строками CONFIRMED именно того дня, и остаток смены
     * мастер обязан видеть под ту дату, под которую кладёт работу.
     */
    private List<AssignableExecutor> buildExecutors(
            List<Long> executorIds,
            LocalDate plannedDate,
            LocalDate from,
            LocalDate to
    ) {
        if (executorIds == null || executorIds.isEmpty()) {
            return List.of();
        }

        List<ExecutorDayLoadView> loads = scheduleRowRepo
                .findExecutorLoad(
                        ScheduleRowStatus.CONFIRMED,
                        executorIds,
                        from,
                        to
                );

        /* Пакетная выборка отсутствий на целевую дату. */
        Set<Long> absentIds = new HashSet<>(
                absenceRepo.findAbsentEmployeeIds(
                        plannedDate, executorIds
                )
        );

        /* Занятость берётся по дате плана этой группы, а не за период. */
        Map<Long, BigDecimal> busyMap = new HashMap<>();
        for (ExecutorDayLoadView load : loads) {
            if (!plannedDate.equals(load.getPlannedDate())) {
                continue;
            }

            Long eid = load.getExecutorId();
            BigDecimal h = load.getHours() == null
                    ? BigDecimal.ZERO
                    : load.getHours();
            busyMap.merge(eid, h, BigDecimal::add);
        }

        /*
         * Загружаем полные карточки сотрудников одним запросом.
         */
        List<AppUser> employees = findByIds(executorIds);

        List<AssignableExecutor> result = new ArrayList<>();

        for (AppUser emp : employees) {
            BigDecimal busy = busyMap.getOrDefault(
                    emp.getId(), BigDecimal.ZERO
            );

            /*
             * FreeHours = смена − busy. Если busy > смены — free
             * становится отрицательным: это сигнал перегрузки.
             */
            BigDecimal free = SHIFT_HOURS.subtract(busy);

            result.add(new AssignableExecutor(
                    emp.getId(),
                    emp.getFullName(),
                    emp.getSpecialty(),
                    emp.getUsername(),
                    busy,
                    free,
                    SHIFT_HOURS,
                    absentIds.contains(emp.getId())
            ));
        }

        /* Сортировка: absent в конец, затем по фамилии. */
        result.sort((a, b) -> {
            if (a.absent() != b.absent()) {
                return a.absent() ? 1 : -1;
            }
            return a.label().compareTo(b.label());
        });

        return result;
    }

    /**
     * Срок в неделях: всё, что вне диапазона, — дефолт.
     *
     * Параметр приходит из адресной строки, и «weeks=99999» не должен
     * превращать страницу в выборку на десять лет.
     */
    public static int normalizeWeeks(Integer weeks) {
        return weeks == null || weeks < 1 || weeks > MAX_WEEKS
                ? DEFAULT_WEEKS
                : weeks;
    }

    /**
     * Серийные номера группы без повторов — для подписи строки таблицы,
     * чтобы номер изделия был виден до раскрытия панели.
     */
    private List<String> serialsOf(
            List<OrderScheduleRow> group,
            Map<Long, ProductionOrderSerialProjection> serialMap
    ) {
        if (serialMap == null || serialMap.isEmpty()) {
            return List.of();
        }

        List<String> serials = new ArrayList<>();

        for (OrderScheduleRow row : group) {
            ProductionOrderSerialProjection serial =
                    serialMap.get(row.getId());
            String number = serial == null ? null : serial.getFullNumber();

            if (number != null && !number.isBlank()
                    && !serials.contains(number)) {
                serials.add(number);
            }
        }

        return serials;
    }

    /**
     * Наименования изделий группы без повторов — подпись главной строки.
     *
     * Берутся из позиции заказа, а не из выпуска: наименование нужно
     * мастеру, даже когда серийного номера ещё нет. Позиции одной
     * операции обычно одно изделие, поэтому дубли отбрасываются.
     */
    private List<String> itemNamesOf(
            List<OrderScheduleRow> group,
            Map<String, String> itemNames
    ) {
        List<String> names = new ArrayList<>();

        for (OrderScheduleRow row : group) {
            String name = serialService.itemNameOf(row, itemNames);

            if (name != null && !name.isBlank() && !names.contains(name)) {
                names.add(name);
            }
        }

        return names;
    }

    private String getOperationCode(OrderScheduleRow row) {
        if (row.getOperation() == null
                || row.getOperation().getOperationName() == null) {
            return null;
        }
        return row.getOperation().getOperationName().getCode();
    }

    private String getOperationName(OrderScheduleRow row) {
        if (row.getOperation() == null
                || row.getOperation().getOperationName() == null) {
            return null;
        }
        return row.getOperation().getOperationName().getName();
    }

    private AppUser findById(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Сотрудник не найден"
                ));
    }

    private List<AppUser> findByIds(List<Long> ids) {
        return userRepository.findAllById(ids);
    }
}
