package ru.company.production.service;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.company.production.dto.AssignableExecutor;
import ru.company.production.dto.AssignedOperationView;
import ru.company.production.dto.DistributionItemCard;
import ru.company.production.dto.DistributionOperation;
import ru.company.production.dto.DistributionRow;
import ru.company.production.dto.OperationDistributionView;
import ru.company.production.dto.OperationInstance;
import ru.company.production.dto.ProductOperationCard;
import ru.company.production.dto.ProductOperationsView;
import ru.company.production.entity.AppUser;
import ru.company.production.entity.CustomerOrder;
import ru.company.production.entity.Operation;
import ru.company.production.entity.OperationTypeCode;
import ru.company.production.entity.OrderScheduleRow;
import ru.company.production.entity.ProductionOrder;
import ru.company.production.entity.Role;
import ru.company.production.entity.ScheduleRowStatus;
import ru.company.production.entity.TechProcess;
import ru.company.production.entity.TechProcessOperation;
import ru.company.production.entity.ZnpKind;
import ru.company.production.repository.AttachedCellView;
import ru.company.production.repository.EmployeeAbsenceRepository;
import ru.company.production.repository.ExecutorDayLoadView;
import ru.company.production.repository.OpenInstanceRowView;
import ru.company.production.repository.OrderScheduleRowRepository;
import ru.company.production.repository.ProductionOrderSerialProjection;
import ru.company.production.repository.TechProcessOperationControlParamRepository;
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
import java.util.Objects;
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
    static final int HORIZON_DAYS = 7 * DEFAULT_WEEKS;
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
            Role.BTK_MASTER,
            Role.SGP_MASTER,
            Role.SGM_MASTER,
            Role.SDP_SERVICE_HEAD_TECHNOLOGY,
            Role.SDP_SERVICE_HEAD_DISPATCHER,
            Role.DISPATCHER,
            Role.SK_GENERAL
    );

    /*
     * Роли-исполнители справочника операций, которые носит сам
     * руководитель: «Начальник цеха/отдела» и «Руководитель службы».
     *
     * Операция с такой ролью исполнителя по смыслу не работа участка,
     * а действие руководителя своего подразделения — распределить её
     * некому, поэтому она идёт тем же путём, что получение комплектации:
     * мимо очереди сразу в «Мои операции» руководителя того
     * подразделения, к которому отнесена операция.
     */
    public static final Set<String> SUPERVISOR_EXECUTOR_ROLE_CODES =
            Set.of("HEAD_OF_WORKSHOP_OR_DEPARTMENT", "SERVICE_HEAD");

    /*
     * Виды заказов, попадающие в групповую половину «Моих операций».
     *
     * Список берётся у {@link ZnpKind}, а не переписывается константами:
     * новый групповой вид должен оказаться в списке сам, иначе его
     * работы исчезли бы из обеих половин — из обычной их отсекает
     * признак вида, а в групповую они бы не попали.
     */
    private static final List<ZnpKind> GROUP_KINDS = ZnpKind.groupKinds();

    /*
     * Типы операций, которые не распределяют: получение комплектации
     * закрепляется за подразделением, а работы по распределению работ
     * — это и есть действие руководителя.
     */
    public static final Set<String> NON_DISTRIBUTION_TYPE_CODES = Set.of(
            OperationTypeCode.KIT_RECEIPT.name(),
            OperationTypeCode.WORK_DISTRIBUTION.name()
    );

    /*
     * Подразделения, где работу не распределяют: бюро входного контроля.
     *
     * Контролёр бюро берёт любую работу из перечня бюро, а планировщик
     * закрепляет строку графика за подразделением, а не за человеком,
     * поэтому и меню, и право проведения здесь считаются по
     * подразделению, а не по executor_id. Руководитель бюро и
     * руководитель службы качества видят тот же перечень целиком.
     */
    private static final Set<Role> UNIT_POOL_ROLES = Set.of(
            Role.BVK_EXECUTOR,
            Role.BVK_MASTER,
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
     * Признак «у операции есть контролируемые параметры» нужен панели
     * экземпляров: массовое проведение закрывает только те экземпляры,
     * где отметка значений не требуется, — обязательный параметр нельзя
     * закрыть без значения, а внести его может только форма.
     */
    private final TechProcessOperationControlParamRepository controlParamLinkRepo;

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
            Collection<Long> unitIds,
            AppUser supervisor,
            int weeks
    ) {
        List<Long> scope = cleanIds(unitIds);

        if (scope.isEmpty()) {
            return List.of();
        }

        LocalDate from = LocalDate.now();
        LocalDate to = from.plusDays(7L * normalizeWeeks(weeks));

        return groupByProduct(
                scheduleRowRepo.findAssignableForUnitIds(
                        ScheduleRowStatus.CONFIRMED,
                        scope,
                        NON_DISTRIBUTION_TYPE_CODES,
                        SUPERVISOR_EXECUTOR_ROLE_CODES,
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
            Collection<Long> unitIds,
            int weeks
    ) {
        List<Long> scope = cleanIds(unitIds);

        if (scope.isEmpty()) {
            return List.of();
        }

        LocalDate from = LocalDate.now();
        LocalDate to = from.plusDays(7L * normalizeWeeks(weeks));

        return groupByProduct(
                scheduleRowRepo.findAssignedForUnitIds(
                        ScheduleRowStatus.CONFIRMED,
                        scope,
                        from,
                        to
                ),
                from,
                to,
                false
        );
    }

    /*
     * Строки графика → изделия с их операциями. Прежний разрез «строка =
     * изделие» уступил месту разрезу «строка = операция»: см.
     * {@link #groupByOperation}.
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

                String baseNumber = baseNumberOrNull(row);

                if (baseNumber != null) {
                    orderNumbers.add(baseNumber);
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
                    buildInstances(group, serialMap, blockers, List.of(),
                            false),
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
                first.getProductionOrder() == null
                        ? null
                        : first.getProductionOrder().getId(),
                first.getItemPosition(),
                serialsOf(product, serialMap),
                serialService.itemNameOf(first, itemNames),
                baseNumberOrNull(first),
                cards,
                totalHours,
                rowCount,
                assignedCount
        );
    }

    /*
     * ============================================================
     * Распределение: строка таблицы = операция одного дня
     * ============================================================
     */

    /**
     * Очередь распределения, собранная по операциям.
     *
     * Строка — пара «операция + дата плана», раскрытие перечисляет
     * изделия этой операции. Мастер видит работу списком, как в «Мои
     * операции», а решение о человеке по-прежнему принимается над
     * конкретным изделием: форма назначения живёт внутри раскрытия.
     *
     * Выборка та же, что у прежнегоproduct-разреза
     * ({@link #findProductOperations}), поэтому набор работ, границы
     * подразделения и счётчик меню не меняются — меняется только то,
     * как строки сгруппированы на экране.
     */
    @Transactional(readOnly = true)
    public List<OperationDistributionView> findDistributionOperations(
            Collection<Long> unitIds,
            AppUser supervisor,
            int weeks
    ) {
        LocalDate from = LocalDate.now();
        LocalDate to = from.plusDays(7L * normalizeWeeks(weeks));

        return findDistributionOperations(unitIds, supervisor, from, to);
    }

    /**
     * То же, но по заданному интервалу дат вместо «сегодня + недели».
     */
    @Transactional(readOnly = true)
    public List<OperationDistributionView> findDistributionOperations(
            Collection<Long> unitIds,
            AppUser supervisor,
            LocalDate from,
            LocalDate to
    ) {
        List<Long> scope = cleanIds(unitIds);

        if (scope.isEmpty()) {
            return List.of();
        }

        return groupByOperation(
                scheduleRowRepo.findAssignableForUnitIds(
                        ScheduleRowStatus.CONFIRMED,
                        scope,
                        NON_DISTRIBUTION_TYPE_CODES,
                        SUPERVISOR_EXECUTOR_ROLE_CODES,
                        from,
                        to
                ),
                from,
                to,
                true
        );
    }

    /**
     * Уже закреплённые работы тем же разрезом «операция → изделия».
     *
     * Снятие назначения идёт по тому же изделию, что и назначение, —
     * поэтому оба блока обязаны показывать одну и ту же структуру, иначе
     * мастер искал бы кнопку в другом разрезе.
     */
    @Transactional(readOnly = true)
    public List<OperationDistributionView> findAssignedDistributionOperations(
            Collection<Long> unitIds,
            int weeks
    ) {
        LocalDate from = LocalDate.now();
        LocalDate to = from.plusDays(7L * normalizeWeeks(weeks));

        return findAssignedDistributionOperations(unitIds, from, to);
    }

    /**
     * То же, но по заданному интервалу дат.
     */
    @Transactional(readOnly = true)
    public List<OperationDistributionView> findAssignedDistributionOperations(
            Collection<Long> unitIds,
            LocalDate from,
            LocalDate to
    ) {
        List<Long> scope = cleanIds(unitIds);

        if (scope.isEmpty()) {
            return List.of();
        }

        return groupByOperation(
                scheduleRowRepo.findAssignedForUnitIds(
                        ScheduleRowStatus.CONFIRMED,
                        scope,
                        from,
                        to
                ),
                from,
                to,
                false
        );
    }

    /**
     * Уже закреплённые работы обычного основания — для переключателя
     * режимов распределения.
     *
     * Обычное основание — заказ покупателя или выпуск (KITTING): именно
     * эти строки подписываются серийным номером выпуска и группируются
     * по изделию. У группового ЗНП подписью служит номер ячейки, и
     * показывать их в той же таблице нельзя — колонка датчика врёт
     * либо там, либо там.
     */
    @Transactional(readOnly = true)
    public List<OperationDistributionView> findAssignedRegularDistributionOperations(
            Collection<Long> unitIds,
            int weeks
    ) {
        LocalDate from = LocalDate.now();
        LocalDate to = from.plusDays(7L * normalizeWeeks(weeks));

        return findAssignedRegularDistributionOperations(unitIds, from, to);
    }

    /**
     * То же, но по заданному интервалу дат — панель срока раздела
     * работает интервалом, и цифра кнопки обязана считаться тем же
     * интервалом, что показан под переключателем.
     */
    @Transactional(readOnly = true)
    public List<OperationDistributionView> findAssignedRegularDistributionOperations(
            Collection<Long> unitIds,
            LocalDate from,
            LocalDate to
    ) {
        List<Long> scope = cleanIds(unitIds);

        if (scope.isEmpty()) {
            return List.of();
        }

        return groupByOperation(
                scheduleRowRepo.findAssignedRegularForUnitIds(
                        ScheduleRowStatus.CONFIRMED,
                        scope,
                        from,
                        to
                ),
                from,
                to,
                false
        );
    }

    /**
     * Уже закреплённые работы группового основания — вторая половина
     * переключателя «Распределённые работы».
     *
     * Групповое основание — производственный заказ группового вида: у
     * него нет заказа покупателя, и изделие подписывается номером
     * ячейки, внесённой складом. Показывать их в одной таблице с
     * обычными изделиями нельзя — разная структура подписи.
     */
    @Transactional(readOnly = true)
    public List<OperationDistributionView> findAssignedGroupDistributionOperations(
            Collection<Long> unitIds,
            int weeks
    ) {
        LocalDate from = LocalDate.now();
        LocalDate to = from.plusDays(7L * normalizeWeeks(weeks));

        return findAssignedGroupDistributionOperations(unitIds, from, to);
    }

    /**
     * То же, но по заданному интервалу дат.
     */
    @Transactional(readOnly = true)
    public List<OperationDistributionView> findAssignedGroupDistributionOperations(
            Collection<Long> unitIds,
            LocalDate from,
            LocalDate to
    ) {
        List<Long> scope = cleanIds(unitIds);

        if (scope.isEmpty()) {
            return List.of();
        }

        List<OrderScheduleRow> rows =
                scheduleRowRepo.findAssignedGroupForUnitIds(
                        ScheduleRowStatus.CONFIRMED,
                        scope,
                        GROUP_KINDS,
                        from,
                        to
                );

        /*
         * Карта внесённых ячеек обязательна: у группового заказа состава
         * выпуска нет, и без неё блок датчика остался бы без подписи —
         * мастер не понял бы, над какой ячейкой он работает.
         */
        return groupByOperation(
                rows,
                from,
                to,
                false,
                serialService.resolveAttachedCells(rows)
        );
    }

    /**
     * Цифры половин переключателя «Распределённые работы».
     *
     * Считаются запросом, а не размером списка: кнопка видна и в том
     * режиме, чей список сейчас не открыт, — мастер обязан видеть объём
     * второй половины, чтобы понимать, есть ли смысл в неё переходить.
     */
    @Transactional(readOnly = true)
    public long countAssignedRegular(Collection<Long> unitIds,
                                     LocalDate from,
                                     LocalDate to) {
        List<Long> scope = cleanIds(unitIds);

        return scope.isEmpty()
                ? 0L
                : scheduleRowRepo.countAssignedRegularForUnitIds(
                        ScheduleRowStatus.CONFIRMED, scope, from, to
                );
    }

    @Transactional(readOnly = true)
    public long countAssignedGroup(Collection<Long> unitIds,
                                   LocalDate from,
                                   LocalDate to) {
        List<Long> scope = cleanIds(unitIds);

        return scope.isEmpty()
                ? 0L
                : scheduleRowRepo.countAssignedGroupForUnitIds(
                        ScheduleRowStatus.CONFIRMED, scope, GROUP_KINDS,
                        from, to
                );
    }

    /**
     * Строки графика → операции, внутри которых перечислены изделия.
     *
     * Флаг {@code withExecutors} различает очередь и распределённые:
     * подбор кандидатов с загрузкой нужен лишь там, где мастер ещё
     * выбирает человека.
     *
     * Порядок строк задаёт запрос (дата плана, затем наименование
     * операции), поэтому LinkedHashMap достаточно для стабильного
     * экрана: мастер читает список сверху вниз по датам.
     */
    private List<OperationDistributionView> groupByOperation(
            List<OrderScheduleRow> rows,
            LocalDate from,
            LocalDate to,
            boolean withExecutors
    ) {
        return groupByOperation(rows, from, to, withExecutors, Map.of());
    }

    /*
     * Тот же группировщик с картой внесённых ячеек.
     *
     * {@code cellMap} передаёт номер ячейки, внесённой складом в
     * групповой ЗНП (V20): у таких заказов состава выпуска нет, и
     * серийный номер их экземпляров не подписывает ничего. Карта
     * приходит из
     * {@link WorkshopDistributionSerialService#resolveAttachedCells} и
     * пуста для всех прочих разделов — там работает {@code serialMap}.
     */
    private List<OperationDistributionView> groupByOperation(
            List<OrderScheduleRow> rows,
            LocalDate from,
            LocalDate to,
            boolean withExecutors,
            Map<Long, AttachedCellView> cellMap
    ) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }

        Map<Long, ProductionOrderSerialProjection> serialMap =
                serialService.resolve(rows);

        Map<String, String> itemNames =
                serialService.resolveItemNames(rows);

        /*
         * Кандидаты считаются один раз на пару «операция + дата» на весь
         * экран: десять изделий одной операции в один день обязаны дать
         * один запрос с загрузкой, а не десять одинаковых.
         */
        Map<String, List<AssignableExecutor>> capableCache =
                new HashMap<>();

        Map<String, List<OrderScheduleRow>> byOperation =
                new LinkedHashMap<>();

        for (OrderScheduleRow row : rows) {
            byOperation
                    .computeIfAbsent(
                            row.getOperation().getId() + "|"
                                    + row.getPlannedDate(),
                            k -> new ArrayList<>()
                    )
                    .add(row);
        }

        List<OperationDistributionView> result =
                new ArrayList<>(byOperation.size());

        for (List<OrderScheduleRow> group : byOperation.values()) {
            result.add(toOperationView(
                    group,
                    serialMap,
                    itemNames,
                    from,
                    to,
                    withExecutors,
                    capableCache,
                    cellMap
            ));
        }

        return result;
    }

    /**
     * Строки одной операции → карточка строки таблицы распределения.
     *
     * Внутри операции блокируются поштучно: блок = одна строка графика =
     * один датчик. Назначение идёт по идентификатору строки
     * ({@link #assignRow}), поэтому подпись блока и граница назначения
     * совпадают по построению — мастер закрепляет ровно тот датчик,
     * который видит.
     */
    private OperationDistributionView toOperationView(
            List<OrderScheduleRow> group,
            Map<Long, ProductionOrderSerialProjection> serialMap,
            Map<String, String> itemNames,
            LocalDate from,
            LocalDate to,
            boolean withExecutors,
            Map<String, List<AssignableExecutor>> capableCache
    ) {
        return toOperationView(
                group, serialMap, itemNames, from, to,
                withExecutors, capableCache, Map.of()
        );
    }

    /*
     * Тот же сборщик карточек с картой внесённых ячеек.
     *
     * {@code cellMap} передаёт номер ячейки, внесённой складом в
     * групповой ЗНП (V20): у таких заказов состава выпуска нет, и
     * серийный номер их экземпляров не подписывает ничего. Карта
     * приходит из
     * {@link WorkshopDistributionSerialService#resolveAttachedCells} и
     * пуста для всех прочих разделов — там работает {@code serialMap}.
     */
    private OperationDistributionView toOperationView(
            List<OrderScheduleRow> group,
            Map<Long, ProductionOrderSerialProjection> serialMap,
            Map<String, String> itemNames,
            LocalDate from,
            LocalDate to,
            boolean withExecutors,
            Map<String, List<AssignableExecutor>> capableCache,
            Map<Long, AttachedCellView> cellMap
    ) {
        OrderScheduleRow head = group.get(0);

        List<DistributionItemCard> items =
                new ArrayList<>(group.size());

        BigDecimal totalHours = BigDecimal.ZERO;
        long assignedCount = 0L;

        for (OrderScheduleRow row : group) {
            ProductionOrderSerialProjection serial = serialMap == null
                    ? null
                    : serialMap.get(row.getId());
            AttachedCellView cell = cellMap == null
                    ? null
                    : cellMap.get(row.getId());

            totalHours = totalHours.add(row.getLaborHours());

            if (row.getExecutor() != null) {
                assignedCount++;
            }

            long overdue = computeOverdueDays(row);

            items.add(new DistributionItemCard(
                    row.getId(),
                    row.getItemPosition(),
                    row.getInstanceIndex(),
                    row.getPlannedDate(),
                    serial == null ? null : serial.getFullNumber(),
                    serialService.itemNameOf(row, itemNames),
                    baseNumberOrNull(row),
                    row.getLaborHours(),
                    row.getExecutor() != null,
                    row.getExecutor() == null
                            ? null
                            : WorkshopStaffService.displayName(
                                    row.getExecutor()
                            ),
                    cell == null ? null : cell.getFullNumber(),
                    overdue
            ));
        }

        List<AssignableExecutor> executors = withExecutors
                ? capableCache.computeIfAbsent(
                        capableKey(group),
                        k -> capableExecutors(
                                group, head.getPlannedDate(), from, to
                        )
                )
                : List.of();

        return new OperationDistributionView(
                head.getOperation().getId(),
                getOperationName(head),
                head.getPlannedDate(),
                group.size(),
                totalHours,
                executors,
                items,
                assignedCount
        );
    }

    /*
     * Основание строки для подписи группы.
     *
     * Ветка основания может быть любой (V56), а подпись печатает
     * просто номер: перечень заказов в строке таблицы не различает
     * реестры, и различать их мастеру незачем. Пустое основание
     * возвращает null — прочерк рядом с назначенными номерами читался
     * бы как сломанная строка.
     */
    private static String baseNumberOrNull(OrderScheduleRow row) {
        if (row.getProductionOrder() != null) {
            return row.getProductionOrder().getNumber();
        }

        return row.getCustomerOrder() == null
                ? null
                : row.getCustomerOrder().getNumber();
    }

    /*
     * Дни просрочки работы.
     *
     * Отсчёт идёт от первоначальной даты плана, а не от текущей:
     * перенос сдвигает planned_date на ближайший свободный день, и
     * без original_planned_date перенесённая работа выглядела бы
     * свежей, хотя с момента решения планировщика прошла уже
     * неделя. Проведённая работа просроченной не считается — у неё
     * срок закрыт фактом выполнения.
     */
    private static long computeOverdueDays(OrderScheduleRow row) {
        if (row.getCompletedAt() != null) {
            return 0L;
        }

        LocalDate planned = row.getOriginalPlannedDate() != null
                ? row.getOriginalPlannedDate()
                : row.getPlannedDate();

        if (planned == null) {
            return 0L;
        }

        long days = java.time.temporal.ChronoUnit.DAYS
                .between(planned, LocalDate.now());

        return days > 0 ? days : 0L;
    }

    /*
     * Ключ изделия: основание строки и позиция в нём.
     *
     * Ветвь основания входит в ключ (V56): у строк группового ЗНП
     * заказа покупателя нет, и ключ по одному только заказу покупателя
     * схлопнул бы изделия всех заказов-комплектующих в одно — мастер
     * увидел бы одну карточку вместо работы по каждому заказу.
     */
    private static String productKey(OrderScheduleRow row) {
        CustomerOrder order = row.getCustomerOrder();

        if (order != null) {
            return "CO" + order.getId() + "|" + row.getItemPosition();
        }

        ProductionOrder productionOrder = row.getProductionOrder();

        return "PO" + (productionOrder == null ? "0"
                : productionOrder.getId())
                + "|" + row.getInstanceIndex();
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
                        NON_DISTRIBUTION_TYPE_CODES,
                        SUPERVISOR_EXECUTOR_ROLE_CODES,
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

        List<Long> unitIds = staffService.unitScope(supervisor);

        OrderScheduleRow row = scheduleRowRepo.findById(rowId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Строка графика не найдена"
                ));

        requireAssignable(row, unitIds);

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

        List<Long> unitIds = staffService.unitScope(supervisor);

        OrderScheduleRow row = scheduleRowRepo.findById(rowId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Строка графика не найдена"
                ));

        if (row.getOrganizationUnit() == null
                || !cleanIds(unitIds).contains(row.getOrganizationUnit().getId())) {
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
                        NON_DISTRIBUTION_TYPE_CODES,
                        SUPERVISOR_EXECUTOR_ROLE_CODES,
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
                String baseNumber = baseNumberOrNull(row);
                if (baseNumber != null) {
                    orderNumbers.add(baseNumber);
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
                    buildInstances(group, serialMap, blockers, List.of(),
                            false),
                    serialsOf(group, serialMap),
                    itemNamesOf(group, itemNames)
            ));
        }

        return result;
    }

    /**
     * Личные работы руководителя подразделения — получение комплектации
     * и операции с руководящей ролью исполнителя.
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
                        NON_DISTRIBUTION_TYPE_CODES,
                        SUPERVISOR_EXECUTOR_ROLE_CODES,
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
                String baseNumber = baseNumberOrNull(row);
                if (baseNumber != null) {
                    orderNumbers.add(baseNumber);
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
                    buildInstances(group, serialMap, blockers, List.of(),
                            false),
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

        List<Long> unitIds = staffService.unitScope(supervisor);

        List<OrderScheduleRow> group = scheduleRowRepo
                .findGroupRowsForUnitIds(
                        ScheduleRowStatus.CONFIRMED,
                        unitIds,
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

        List<Long> unitIds = staffService.unitScope(supervisor);

        List<OrderScheduleRow> group = scheduleRowRepo
                .findGroupRowsForUnitIds(
                        ScheduleRowStatus.CONFIRMED,
                        unitIds,
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
    /**
     * Строки одной операции одного изделия — та же граница, что у
     * карточки в распределении.
     *
     * Основание строки выбирает ветвь (V56): у плана заказа покупателя
     * изделие задано парой «заказ + позиция», у заказа на производство
     * позиций нет и изделием служит сам заказ. Передана обычно только
     * одна из ветвей; если не передана ни одна, группа пуста — запрос с
     * пустым идентификатором вернул бы работы чужого изделия.
     */
    private List<OrderScheduleRow> productOperationRows(
            Long operationId,
            LocalDate plannedDate,
            Long orderId,
            Integer itemPosition,
            Long productionOrderId,
            List<Long> unitIds
    ) {
        if (productionOrderId != null) {
            return scheduleRowRepo.findProductOperationRowsOfProductionOrders(
                    ScheduleRowStatus.CONFIRMED,
                    unitIds,
                    operationId,
                    plannedDate,
                    productionOrderId
            );
        }

        if (orderId == null || itemPosition == null) {
            return List.of();
        }

        return scheduleRowRepo.findProductOperationRowsForUnitIds(
                ScheduleRowStatus.CONFIRMED,
                unitIds,
                operationId,
                plannedDate,
                orderId,
                itemPosition
        );
    }

    @Transactional
    public void assignProductOperation(
            Long operationId,
            LocalDate plannedDate,
            Long orderId,
            Integer itemPosition,
            Long productionOrderId,
            Long executorId,
            AppUser supervisor
    ) {
        if (operationId == null || plannedDate == null
                || (productionOrderId == null
                    && (orderId == null || itemPosition == null))) {
            throw new IllegalArgumentException(
                    "Операция изделия не указана"
            );
        }

        if (executorId == null) {
            throw new IllegalArgumentException("Выберите исполнителя");
        }

        List<Long> unitIds = staffService.unitScope(supervisor);

        List<OrderScheduleRow> group = productOperationRows(
                operationId, plannedDate, orderId, itemPosition,
                productionOrderId, unitIds);

        if (group.isEmpty()) {
            throw new IllegalArgumentException(
                    "Не найдено операций этого изделия для назначения"
            );
        }

        for (OrderScheduleRow row : group) {
            requireAssignable(row, unitIds);

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
            Long productionOrderId,
            AppUser supervisor
    ) {
        if (operationId == null || plannedDate == null
                || (productionOrderId == null
                    && (orderId == null || itemPosition == null))) {
            throw new IllegalArgumentException(
                    "Операция изделия не указана"
            );
        }

        List<Long> unitIds = staffService.unitScope(supervisor);

        List<OrderScheduleRow> group = productOperationRows(
                operationId, plannedDate, orderId, itemPosition,
                productionOrderId, unitIds);

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
                "Распределено",
                false,
                List.of()
        );
    }

    /**
     * Операции комплектования склада за горизонт планирования.
     *
     * Разделам склада («Комплектование ЗНП», «Комплектование
     * полуфабрикатов») нужен весь объём работ склада, а не только
     * закреплённый за человеком: планировщик создаёт их при
     * планировании заказа покупателя и закрепляет за подразделением,
     * поэтому кладовщик обязан видеть и назначенные, и неназначенные
     * строки — иначе неотобранная работа исчезла бы из вида.
     *
     * Набор типов задаёт вызывающий: комплектованием считаются и
     * получение комплектации, и комплектовочная операция, а цеховые
     * этапы маршрута в раздел не попадают.
     *
     * Подписи групп собирает тот же {@link #groupAssigned}, что и
     * «Мои операции»: кладовщик и исполнитель видят одну и ту же
     * работу одинаково, различается только отбор. Источник здесь не
     * подписывается — в разделе одна природа работ.
     */
    @Transactional(readOnly = true)
    public List<AssignedOperationView> findKittingOperations(
            Collection<Long> unitIds,
            Collection<String> typeCodes
    ) {
        if (unitIds == null || unitIds.isEmpty()
                || typeCodes == null || typeCodes.isEmpty()) {
            return List.of();
        }

        LocalDate from = LocalDate.now();
        LocalDate to = from.plusDays(HORIZON_DAYS);

        return groupAssigned(
                scheduleRowRepo.findKittingForUnits(
                        ScheduleRowStatus.CONFIRMED,
                        unitIds,
                        typeCodes,
                        from,
                        to
                ),
                null,
                false,
                List.of()
        );
    }

    /**
     * Сколько операций комплектования у склада — счётчик бокового
     * меню.
     *
     * Считается запросом, а не размером списка: меню рисуется на
     * каждой странице модуля, а подбор серийников и экземпляров
     * ради одной цифры стоил бы десятков запросов.
     */
    @Transactional(readOnly = true)
    public long countKittingOperations(
            Collection<Long> unitIds,
            Collection<String> typeCodes
    ) {
        if (unitIds == null || unitIds.isEmpty()
                || typeCodes == null || typeCodes.isEmpty()) {
            return 0L;
        }

        LocalDate from = LocalDate.now();
        LocalDate to = from.plusDays(HORIZON_DAYS);

        return scheduleRowRepo.countKittingForUnits(
                ScheduleRowStatus.CONFIRMED,
                unitIds,
                typeCodes,
                from,
                to
        );
    }

    /**
     * Сырые строки графика комплектования склада.
     *
     * Раздел «Комплектование ЗНП» перечисляет изделия, а не группы
     * операций: серийный номер принадлежит экземпляру, поэтому разрезу
     * «изделие → операции» нужны исходные строки, а не собранные
     * группы. Те же горизонт и отбор, что у
     * {@link #findKittingOperations}: два разреза одного раздела не
     * должны показывать разный объём работы.
     */
    @Transactional(readOnly = true)
    public List<OrderScheduleRow> findKittingRows(
            Collection<Long> unitIds,
            Collection<String> typeCodes
    ) {
        if (unitIds == null || unitIds.isEmpty()
                || typeCodes == null || typeCodes.isEmpty()) {
            return List.of();
        }

        LocalDate from = LocalDate.now();
        LocalDate to = from.plusDays(HORIZON_DAYS);

        return scheduleRowRepo.findKittingForUnits(
                ScheduleRowStatus.CONFIRMED,
                unitIds,
                typeCodes,
                from,
                to
        );
    }

    /**
     * Работы групповых ЗНП, закреплённые за пользователем, — вторая
     * половина «Моих операций».
     *
     * Разрез тот же, что у {@link #findMyOperations}, но изделие
     * подписывается номером ячейки, внесённой складом в заказ (V20), а
     * не серийным номером выпуска: у группового заказа состава выпуска
     * нет вовсе, и в обычном списке его работы читались бы как «номер не
     * выпущен». Разделение на два списка держит каждая половина свою
     * колонку честной — ровно как в распределении, где для этого
     * поставлен переключатель режимов.
     *
     * Личные работы руководителя (получение комплектации, распределение
     * работ) здесь не дублируются: они закреплены планом, а не
     * исполнителем, подписываются своей природой и не нуждаются в номере
     * ячейки — поэтому остаются в обычной половине.
     */
    @Transactional(readOnly = true)
    public List<AssignedOperationView> findMyGroupOperations(AppUser user) {
        if (user == null || user.getId() == null) {
            return List.of();
        }

        LocalDate from = LocalDate.now();
        LocalDate to = from.plusDays(HORIZON_DAYS);

        List<OrderScheduleRow> rows;
        List<Long> poolUnitIds;

        if (isUnitPool(user)) {
            List<Long> poolScope = staffService.unitScope(user);

            if (poolScope.isEmpty()) {
                return List.of();
            }

            rows = scheduleRowRepo.findGroupAllForUnits(
                    ScheduleRowStatus.CONFIRMED, poolScope,
                    GROUP_KINDS, from, to
            );
            poolUnitIds = poolScope;
        } else {
            rows = scheduleRowRepo.findGroupForExecutor(
                    user.getId(), GROUP_KINDS, from, to
            );
            poolUnitIds = List.of();
        }

        List<AssignedOperationView> result = new ArrayList<>(
                groupAssigned(
                        rows,
                        null,
                        false,
                        poolUnitIds,
                        serialService.resolveAttachedCells(rows)
                )
        );

        /*
         * Личные работы руководителя по групповым ЗНП («Получение
         * подсборок для группового ЗНП», передача ячеек в БТК) — те же
         * строки, что в обычной половине у руководителя, но с групповым
         * основанием. Здесь они не дубликат: обычная половина отсекает
         * их по виду основания, поэтому без этого шага работа пропала
         * бы из обоих списков.
         */
        if (!isUnitPool(user) && isSupervisor(user)) {
            List<Long> scope = staffService.unitScope(user);

            if (!scope.isEmpty()) {
                List<OrderScheduleRow> ownRows =
                        scheduleRowRepo.findOwnGroupKitReceipt(
                                ScheduleRowStatus.CONFIRMED,
                                scope,
                                NON_DISTRIBUTION_TYPE_CODES,
                                SUPERVISOR_EXECUTOR_ROLE_CODES,
                                GROUP_KINDS,
                                from,
                                to
                        );

                result.addAll(groupAssigned(
                        ownRows,
                        null,
                        true,
                        scope,
                        serialService.resolveAttachedCells(ownRows)
                ));
            }
        }

        result.sort((a, b) -> {
            int date = a.plannedDate().compareTo(b.plannedDate());
            return date != 0 ? date : a.title().compareTo(b.title());
        });

        return result;
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

        /*
         * Бюро входного контроля: меню строится по подразделению, а не
         * по закреплённому исполнителю. План закрепляет строку за
         * бюро, а контролёр берёт любую работу из его перечня, поэтому
         * список по executor_id показался бы пустым и работу пришлось
         * бы искать в чужом разделе. Если подразделение в карточке
         * учётной записи не заполнено, граница не выдумывается —
         * остаётся личный список, как у прочих ролей.
         */
        if (isUnitPool(user)) {
            List<Long> poolScope = staffService.unitScope(user);

            if (!poolScope.isEmpty()) {
                return groupAssigned(
                        scheduleRowRepo.findAllForUnits(
                                ScheduleRowStatus.CONFIRMED,
                                poolScope,
                                from,
                                to
                        ),
                        null,
                        false,
                        poolScope
                );
            }
        }

        List<AssignedOperationView> result = new ArrayList<>(
                groupAssigned(
                        scheduleRowRepo.findForExecutor(
                                user.getId(),
                                from,
                                to
                        ),
                        null,
                        false,
                        List.of()
                )
        );

        if (!isSupervisor(user)) {
            return result;
        }

        /*
         * Граница — список подразделений службы, а не одно: у
         * руководителя службы качества карточка подразделения пуста, и
         * проверка одиночного идентификатора отсекла бы ему получение
         * комплектации целиком.
         */
        List<Long> scope = staffService.unitScope(user);

        if (scope.isEmpty()) {
            return result;
        }

        result.addAll(groupAssigned(
                scheduleRowRepo.findOwnKitReceipt(
                        ScheduleRowStatus.CONFIRMED,
                        scope,
                        NON_DISTRIBUTION_TYPE_CODES,
                        SUPERVISOR_EXECUTOR_ROLE_CODES,
                        from,
                        to
                ),
                null,
                true,
                scope
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
     * Исключение — складские операции комплектования (KIT_RECEIPT,
     * KITTING): они закреплены за подразделением, а не за человеком,
     * поэтому любой авторизованный пользователь может их закрыть.
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

        /*
         * Складские операции комплектования план закрепляет за
         * подразделением, а не за человеком, — исполнителя у них нет.
         * Проводить их вправе сотрудник склада: доступ к разделу
         * ограничен правилом модуля склада, поэтому для таких строк
         * проверяется только сам факт авторизованного пользователя.
         *
         * То же правило работает для бюро входного контроля: его строки
         * принадлежат бюро, а не человеку, и контролёр закрывает любую
         * работу из перечня бюро. Граница — подразделение учётной
         * записи, поэтому чужую работу так не провести.
         */
        if (!isWarehouseKitting(row)
                && !isPoolPerformable(row, performer)
                && !isOwnSupervisorPerformable(row, performer)) {
            if (row.getExecutor() == null
                    || !row.getExecutor().getId().equals(performer.getId())) {
                throw new IllegalStateException(
                        "Провести работу может только исполнитель, "
                                + "за которым она закреплена"
                );
            }
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
     * Невыполненные закреплённые работы пользователя — счётчик пункта
     * «Мои операции» в боковом меню.
     *
     * Считается запросом, а не размером списка {@link #findMyOperations}:
     * меню рисуется на каждой странице модуля, а подбор кандидатов с
     * их загрузкой и выводом экземпляров ради одной цифры в меню стоил
     * бы десятков запросов на каждую загрузку экрана.
     *
     * Граница та же, что у самого списка: строки, закреплённые за
     * человеком напрямую, плюс получение комплектации своего
     * подразделения — только для руководящей роли. Сверх списка
     * отсекаются проведённые строки: цифра в меню означает объём
     * оставшейся работы, а не число строк плана на неделю, поэтому
     * закрытая сегодня операция в неё уже не входит, хотя и остаётся
     * в списке до конца дня.
     */
    /**
     * Работы групповых ЗНП пользователя — цифра его половины списка.
     *
     * Граница повторяет {@link #findMyGroupOperations} буквально: для
     * бюро входного контроля по подразделению, для прочих ролей по
     * закреплённому исполнителю. Личные работы руководителя здесь не
     * считаются — они остаются в обычной половине, и их цифра живёт в
     * {@link #countMyOperations}.
     */
    @Transactional(readOnly = true)
    public long countMyGroupOperations(AppUser user) {
        if (user == null || user.getId() == null) {
            return 0L;
        }

        LocalDate from = LocalDate.now();
        LocalDate to = from.plusDays(HORIZON_DAYS);

        if (isUnitPool(user)) {
            List<Long> poolScope = staffService.unitScope(user);

            if (poolScope.isEmpty()) {
                return 0L;
            }

            return scheduleRowRepo.countGroupAllForUnits(
                    ScheduleRowStatus.CONFIRMED,
                    poolScope,
                    GROUP_KINDS,
                    from,
                    to
            );
        }

        return scheduleRowRepo.countGroupForExecutor(
                user.getId(), GROUP_KINDS, from, to
        ) + ownGroupCount(user, from, to);
    }

    /*
     * Цифра личных групповых работ руководителя — добавка к счётчику
     * групповой половины.
     *
     * Повторяет условие списка буквально: руководящая роль и непустой
     * scope подразделений. Для бюро входного контроля личные работы не
     * считаются — там весь объём уже взят счётчиком по подразделению, и
     * сложение дало бы двойную цифру.
     */
    private long ownGroupCount(AppUser user, LocalDate from, LocalDate to) {
        if (isUnitPool(user) || !isSupervisor(user)) {
            return 0L;
        }

        List<Long> scope = staffService.unitScope(user);

        if (scope.isEmpty()) {
            return 0L;
        }

        return scheduleRowRepo.countOwnGroupKitReceipt(
                ScheduleRowStatus.CONFIRMED,
                scope,
                NON_DISTRIBUTION_TYPE_CODES,
                SUPERVISOR_EXECUTOR_ROLE_CODES,
                GROUP_KINDS,
                from,
                to
        );
    }

    @Transactional(readOnly = true)
    public long countMyOperations(AppUser user) {
        if (user == null || user.getId() == null) {
            return 0L;
        }

        LocalDate from = LocalDate.now();
        LocalDate to = from.plusDays(HORIZON_DAYS);

        /*
         * Бюро входного контроля: цифра должна совпадать со списком,
         * а список строится по подразделению, поэтому и счётчик
         * считается по нему же.
         */
        if (isUnitPool(user)) {
            List<Long> poolScope = staffService.unitScope(user);

            if (!poolScope.isEmpty()) {
                return scheduleRowRepo.countAllForUnits(
                        ScheduleRowStatus.CONFIRMED,
                        poolScope,
                        from,
                        to
                );
            }
        }

        long total = scheduleRowRepo.countForExecutor(
                user.getId(), from, to
        );

        if (!isSupervisor(user)) {
            return total;
        }

        /*
         * Граница — список подразделений службы, а не одно: у
         * руководителя службы качества карточка подразделения пуста, и
         * проверка одиночного идентификатора отняла бы у него личные
         * работы целиком. Тот же scope, что у самого списка.
         */
        List<Long> scope = staffService.unitScope(user);

        if (scope.isEmpty()) {
            return total;
        }

        return total + scheduleRowRepo.countOwnKitReceipt(
                ScheduleRowStatus.CONFIRMED,
                scope,
                NON_DISTRIBUTION_TYPE_CODES,
                SUPERVISOR_EXECUTOR_ROLE_CODES,
                from,
                to
        );
    }

    /**
     * Нераспределённые работы подразделения за горизонт — счётчик
     * бокового меню.
     *
     * Принимает список: руководитель службы качества видит очередь
     * сразу двух своих подразделений, и цифра по одному из них не
     * сошлась бы с тем, что он увидит, войдя в раздел.
     */
    @Transactional(readOnly = true)
    public long countAssignable(Collection<Long> unitIds) {
        List<Long> scope = cleanIds(unitIds);

        if (scope.isEmpty()) {
            return 0L;
        }

        LocalDate from = LocalDate.now();

        return scheduleRowRepo.countAssignableForUnitIds(
                ScheduleRowStatus.CONFIRMED,
                scope,
                NON_DISTRIBUTION_TYPE_CODES,
                SUPERVISOR_EXECUTOR_ROLE_CODES,
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
     *
     * {@code poolUnitIds} — подразделения, где работу не распределяют
     * (бюро входного контроля): для них кнопка проведения полагается на
     * любую строку своего подразделения, а не только на строку, где
     * человек вписан исполнителем. Для прочих разделов передаётся пустой
     * — там работа всегда закреплена за конкретным человеком.
     *
     * {@code ownWork} помечает группы, закреплённые планом, а не
     * решением мастера: получение комплектации и операции с руководящей
     * ролью исполнителя. Таким группам полагается своя подпись и своё
     * право проведения — по подразделению, потому что исполнителя у них
     * нет.
     */
    private List<AssignedOperationView> groupAssigned(
            List<OrderScheduleRow> rows,
            String source,
            boolean ownWork,
            List<Long> poolUnitIds
    ) {
        return groupAssigned(rows, source, ownWork, poolUnitIds, Map.of());
    }

    /*
     * Тот же группировщик с картой внесённых ячеек.
     *
     * {@code cellMap} передаётся только групповому списку: у серийного
     * ЗНП матрица комплектования хранит датчики выпуска, и подставить её
     * в обычную половину было бы подписать работу чужим номером.
     */
    private List<AssignedOperationView> groupAssigned(
            List<OrderScheduleRow> rows,
            String source,
            boolean ownWork,
            List<Long> poolUnitIds,
            Map<Long, AttachedCellView> cellMap
    ) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }

        Map<Long, ProductionOrderSerialProjection> serialMap =
                serialService.resolve(rows);

        Map<String, String> itemNames =
                serialService.resolveItemNames(rows);

        Map<Long, InstanceBlocker> blockers = blockersOf(rows);

        Set<String> parametrisedNodes = controlParamNodes(rows);

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

                String baseNumber = baseNumberOrNull(row);
                if (baseNumber != null) {
                    orderNumbers.add(baseNumber);
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
                    ownWork ? ownOperationSource(first) : source,
                    ownWork,
                    buildInstances(
                            group, serialMap, blockers, poolUnitIds, ownWork,
                            cellMap, parametrisedNodes
                    ),
                    /*
                     * Главная подпись группы. Пока карта ячеек не
                     * задана — прежний серийный номер выпуска; для
                     * группового списка, наоборот, номер ячейки: у
                     * такого заказа состава выпуска нет вовсе, и
                     * серийник подписывал бы пустую клетку там, где у
                     * изделия уже есть внесённый складом номер.
                     */
                    cellMap == null || cellMap.isEmpty()
                            ? serialsOf(group, serialMap)
                            : cellNumbersOf(group, cellMap),
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
     * Способные исполнители для группы — с загрузкой и отсутствиями.
     *
     * Метод поднят из тела группировщика, чтобы его переиспользовали и
     * групповой, и плоский режимы распределения: расчёт привязок,
     * загрузки и отсутствий в них идентичен.
     *
     * Видимость — package-private: раздел распределения групповых ЗНП
     * обязан подбирать людей ровно тем же правилом, что и общее
     * распределение, иначе два экрана одного руководителя показали бы
     * разные списки кандидатов и разную загрузку.
     */
    List<AssignableExecutor> capableExecutors(
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
        return new DistributionRow(
                row.getId(),
                instanceLabel(row),
                baseNumberOrNull(row),
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
     * Экземпляр относится к личной работе руководителя — получение
     * комплектации своего подразделения либо операция с руководящей
     * ролью исполнителя. Распределению такая работа не подлежит.
     */
    private boolean isKitReceipt(OrderScheduleRow row) {
        return isNonDistributableOperation(row);
    }

    /**
     * Строка не распределяется: тип операции из перечня нераспределяемых
     * либо роль её исполнителя — руководящая.
     *
     * Признак держится рядом с константами, а не переписывается в каждом
     * запросе: экран очереди и личный список руководителя обязаны
     * расходиться ровно на этот набор, и расхождение допустили бы в двух
     * местах сразу.
     */
    public static boolean isNonDistributableOperation(
            OrderScheduleRow row
    ) {
        if (row == null || row.getOperation() == null) {
            return false;
        }

        Operation operation = row.getOperation();

        if (operation.getOperationType() != null
                && NON_DISTRIBUTION_TYPE_CODES.contains(
                        operation.getOperationType().getCode())) {
            return true;
        }

        return operation.getExecutorRole() != null
                && SUPERVISOR_EXECUTOR_ROLE_CODES.contains(
                        operation.getExecutorRole().getCode());
    }

    /**
     * Строка — личная работа руководителя по руководящей роли
     * исполнителя: провести её вправе сам руководитель, а не
     * подчинённый, потому что исполнителя план у неё не заполняет.
     */
    private static boolean isSupervisorOwnRow(OrderScheduleRow row) {
        return row != null
                && row.getOperation() != null
                && row.getOperation().getExecutorRole() != null
                && SUPERVISOR_EXECUTOR_ROLE_CODES.contains(
                        row.getOperation().getExecutorRole().getCode());
    }

    /**
     * Подпись природы личной работы: получение комплектации подписано
     * по-своему, потому что так её называет план; остальным работам
     * руководителя даётся общая подпись.
     */
    private static String ownOperationSource(OrderScheduleRow row) {
        if (row.getOperation() != null
                && row.getOperation().getOperationType() != null
                && OperationTypeCode.KIT_RECEIPT.name().equals(
                        row.getOperation().getOperationType().getCode())) {
            return "Получение комплектации";
        }

        return "Личная работа руководителя";
    }

    private static boolean isKitReceiptType(String code) {
        return OperationTypeCode.KIT_RECEIPT.name().equals(code)
                || OperationTypeCode.KITTING.name().equals(code);
    }

    /*
     * Складские работы комплектации: план закрепляет их за
     * подразделением, а не за человеком, — исполнителя у такой строки
     * нет. Проводить их вправе любой сотрудник склада, доступ к
     * разделу которого ограничен правилом модуля склада.
     */
    private static boolean isWarehouseKitting(OrderScheduleRow row) {
        if (row.getOperation() == null
                || row.getOperation().getOperationType() == null) {
            return false;
        }

        return isKitReceiptType(
                row.getOperation().getOperationType().getCode()
        );
    }

    /**
     * Строка принадлежит подразделению без распределения — работа
     * берётся любым его сотрудником.
     */
    private static boolean isPoolRow(OrderScheduleRow row,
                                     List<Long> poolUnitIds) {
        return poolUnitIds != null
                && !poolUnitIds.isEmpty()
                && row.getOrganizationUnit() != null
                && poolUnitIds.contains(row.getOrganizationUnit().getId());
    }

    /**
     * Проводит ли сотрудник чужую строку по праву подразделения.
     *
     * Граница берётся из учётной записи, а не из запроса: список
     * подразделений того же состава, что у меню, поэтому контролёр бюро
     * закрывает работу своего бюро, но не чужого.
     */
    private boolean isPoolPerformable(OrderScheduleRow row,
                                      AppUser performer) {
        if (!isUnitPool(performer) || row.getOrganizationUnit() == null) {
            return false;
        }

        return staffService.unitScope(performer)
                .contains(row.getOrganizationUnit().getId());
    }

    /**
     * Проводит ли сотрудник свою руководящую работу.
     *
     * Операцию с руководящей ролью исполнителя план закрепляет за
     * подразделением, а не за человеком, поэтому право считается так же,
     * как у комплектования: по границе подразделения авторизованного
     * руководителя, и чужую работу так не закрыть.
     */
    private boolean isOwnSupervisorPerformable(
            OrderScheduleRow row,
            AppUser performer
    ) {
        if (!isSupervisor(performer) || row.getOrganizationUnit() == null) {
            return false;
        }

        return isSupervisorOwnRow(row)
                && staffService.unitScope(performer)
                        .contains(row.getOrganizationUnit().getId());
    }

    /**
     * Ведёт ли пользователь работу по подразделению, а не по личной
     * закреплённости: в бюро входного контроля работы не распределяются.
     */
    public boolean isUnitPool(AppUser user) {
        return user != null
                && user.getRole() != null
                && UNIT_POOL_ROLES.contains(user.getRole());
    }

    /*
     * Идентификаторы без null и повторов: пустой список — безопаснее,
     * чем пустой «in ()» в JPQL, а LinkedHashSet убирает дубли.
     */
    private static List<Long> cleanIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }

        return new ArrayList<>(
                new LinkedHashSet<>(ids).stream().filter(Objects::nonNull)
                        .toList()
        );
    }

    /**
     * Проверка, что строку вообще можно назначить: она в плане, её
     * подразделение входит в границу руководителя и это не получение
     * комплектации.
     *
     * Граница — список, а не одно подразделение: руководителю службы
     * качества принадлежат и БТК, и БВК, и сравнение с единственным
     * подразделением его карточки отклонило бы любую его работу.
     */
    private void requireAssignable(
            OrderScheduleRow row,
            Collection<Long> unitIds
    ) {
        if (row.getStatus() != ScheduleRowStatus.CONFIRMED) {
            throw new IllegalStateException(
                    "Назначать можно только внесённые в план работы"
            );
        }

        List<Long> scope = cleanIds(unitIds);

        if (row.getOrganizationUnit() == null
                || !scope.contains(row.getOrganizationUnit().getId())) {
            throw new IllegalArgumentException(
                    "Работа относится к другому подразделению"
            );
        }

        if (isNonDistributableOperation(row)) {
            throw new IllegalArgumentException(
                    "Личная работа руководителя не распределяется"
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
     *
     * {@code poolUnitIds} — подразделения без распределения (бюро
     * входного контроля): там строка принадлежит подразделению, а не
     * человеку, и провести её может любой контролёр этого бюро, поэтому
     * признак строится по подразделению строки.
     *
     * {@code ownWork} — те же личные работы руководителя, что и в
     * подписи группы: у них нет исполнителя, и право проведения тоже
     * берётся по подразделению, иначе кнопка не появилась бы ни у кого.
     */
    private List<OperationInstance> buildInstances(
            List<OrderScheduleRow> group,
            Map<Long, ProductionOrderSerialProjection> serialMap,
            Map<Long, InstanceBlocker> blockers,
            List<Long> poolUnitIds,
            boolean ownWork
    ) {
        return buildInstances(
                group, serialMap, blockers, poolUnitIds, ownWork,
                Map.of(), Set.of()
        );
    }

    /*
     * Тот же сборщик экземпляров с картой внесённых ячеек.
     *
     * {@code cellMap} передаёт номер ячейки, внесённой складом в
     * групповой ЗНП (V20): у таких заказов состава выпуска нет, и
     * серийный номер их экземпляров не подписывает ничего. Карта
     * приходит из
     * {@link WorkshopDistributionSerialService#resolveAttachedCells} и
     * пуста для всех прочих разделов — там работает
     * {@code serialMap}.
     *
     * {@code parametrisedNodes} — узлы маршрута с контролируемыми
     * параметрами (см. {@link #controlParamNodes}): экземпляр такого
     * узла массовое проведение обходит, потому что значение параметра
     * вносит только форма отметки.
     */
    private List<OperationInstance> buildInstances(
            List<OrderScheduleRow> group,
            Map<Long, ProductionOrderSerialProjection> serialMap,
            Map<Long, InstanceBlocker> blockers,
            List<Long> poolUnitIds,
            boolean ownWork,
            Map<Long, AttachedCellView> cellMap,
            Set<String> parametrisedNodes
    ) {
        List<OperationInstance> instances = new ArrayList<>();

        for (OrderScheduleRow row : group) {
            ProductionOrderSerialProjection serial = serialMap == null
                    ? null
                    : serialMap.get(row.getId());
            InstanceBlocker blocker = blockers == null
                    ? InstanceBlocker.FREE
                    : blockers.getOrDefault(row.getId(),
                            InstanceBlocker.FREE);
            AttachedCellView cell = cellMap == null
                    ? null
                    : cellMap.get(row.getId());

            instances.add(new OperationInstance(
                    row.getId(),
                    row.getItemPosition() == null
                            ? 0
                            : row.getItemPosition(),
                    row.getInstanceIndex() == null
                            ? 0
                            : row.getInstanceIndex(),
                    baseNumberOrNull(row),
                    row.getLaborHours(),
                    row.getCompletedAt() != null,
                    row.getCompletedAt() == null
                            ? null
                            : row.getCompletedAt().format(RU_DATE_TIME),
                    /*
                     * Провести можно свой экземпляр, а также любую
                     * работу комплектования склада: её план закрепляет
                     * за подразделением, исполнителя у неё нет, и без
                     * этой оговорки кнопка закрытия у кладовщика не
                     * появилась бы никогда. Та же оговорка работает для
                     * бюро входного контроля (poolUnitIds) и для личных
                     * работ руководителя (ownWork): их план закрепляет за
                     * подразделением, поэтому право считается по границе
                     * подразделения, а не по фамилии исполнителя.
                     */
                    (row.getExecutor() != null
                            || isWarehouseKitting(row)
                            || isPoolRow(row, poolUnitIds)
                            || (ownWork && isSupervisorOwnRow(row)))
                            && row.getCompletedAt() == null
                            && !blocker.blocked(),
                    serial == null ? null : serial.getFullNumber(),
                    /*
                     * Наименование изделия. Для строки выпуска — из
                     * позиции выпуска; для строки группового ЗНП выпуска
                     * нет, и наименованием служит классификатор внесённой
                     * ячейки: иначе панель экземпляра показывала бы
                     * прочерк там, где изделие названо.
                     */
                    serial != null
                            ? serial.getDisplayName()
                            : (cell == null ? null : cell.getDisplayName()),
                    blocker.blocked(),
                    blocker.blockerLabel(),
                    cell == null ? null : cell.getFullNumber(),
                    /*
                     * Отметка контролируемых параметров обязательна, если
                     * технолог задал их в этом узле маршрута: такое
                     * проведение принимает только форма с значениями.
                     */
                    parametrisedNodes != null
                            && parametrisedNodes.contains(routePairKey(
                                    row.getTechProcess() == null
                                            ? null
                                            : row.getTechProcess().getId(),
                                    row.getOperation() == null
                                            ? null
                                            : row.getOperation().getId()
                            ))
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
     * Блокированные строки и их подписи — read-модель, общая для
     * других разделов.
     *
     * Раздел комплектования строит свой разрез («изделие → операции»),
     * но зависимость маршрута обязана считаться ровно тем же правилом,
     * что и в распределении: иначе два экрана разошлись бы во мнении,
     * можно ли проводить работу. Возвращаются только заблокированные
     * строки — свободная строка в карте просто отсутствует.
     */
    @Transactional(readOnly = true)
    public Map<Long, String> blockerLabelsOf(List<OrderScheduleRow> rows) {
        Map<Long, String> result = new HashMap<>();

        blockersOf(rows).forEach((rowId, blocker) -> {
            if (blocker.blocked()) {
                result.put(rowId, blocker.blockerLabel());
            }
        });

        return result;
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
        Set<Long> productionOrderIds = new HashSet<>();

        for (OrderScheduleRow row : rows) {
            TechProcess process = row.getTechProcess();

            if (process != null && process.getId() != null) {
                processIds.add(process.getId());
            }

            CustomerOrder order = row.getCustomerOrder();

            if (order != null && order.getId() != null) {
                orderIds.add(order.getId());
            }

            ProductionOrder productionOrder = row.getProductionOrder();

            if (productionOrder != null
                    && productionOrder.getId() != null) {
                productionOrderIds.add(productionOrder.getId());
            }
        }

        /*
         * Оснований у строки два (V56), и пустая одна ветвь — норма:
         * в списке работ цеха план заказа покупателя и план группового
         * ЗНП редко пересекаются. Выход только когда оснований нет
         * вовсе — сравнивать состояние строк было бы не с чем.
         */
        if (processIds.isEmpty()
                || (orderIds.isEmpty() && productionOrderIds.isEmpty())) {
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
         *
         * Обе ветви основания (V56) читаются раздельно: `in` с пустой
         * коллекцией Hibernate отвергает, а пустая ветвь в списке
         * работ — обычное дело.
         */
        Set<String> closedKeys = new HashSet<>();
        Set<String> openKeys = new HashSet<>();

        collectInstanceKeys(closedKeys, openKeys,
                orderIds.isEmpty()
                        ? List.of()
                        : scheduleRowRepo
                                .findRouteRowsOfOrders(orderIds));

        collectInstanceKeys(closedKeys, openKeys,
                productionOrderIds.isEmpty()
                        ? List.of()
                        : scheduleRowRepo.findRouteRowsOfProductionOrders(
                                productionOrderIds));

        if (openKeys.isEmpty() || pairsByNode.isEmpty()) {
            return Map.of();
        }

        Map<Long, InstanceBlocker> result = new HashMap<>();

        for (OrderScheduleRow row : rows) {
            TechProcess process = row.getTechProcess();
            String baseKey = baseKeyOf(row);

            if (process == null || baseKey == null
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
                        baseKey,
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

    /*
     * Узлы маршрута строк списка, у которых заданы контролируемые
     * параметры.
     *
     * Один запрос на весь список, а не по одному на строку: панель
     * экземпляров раскрывается на десятки строк графика, а ответ
     * нужен только как множество ключей «техпроцесс + операция».
     */
    private Set<String> controlParamNodes(List<OrderScheduleRow> rows) {

        Set<Long> processIds = new HashSet<>();
        Set<Long> operationIds = new HashSet<>();

        for (OrderScheduleRow row : rows) {
            TechProcess process = row.getTechProcess();
            Operation operation = row.getOperation();

            if (process == null || process.getId() == null
                    || operation == null || operation.getId() == null) {
                continue;
            }

            processIds.add(process.getId());
            operationIds.add(operation.getId());
        }

        if (processIds.isEmpty() || operationIds.isEmpty()) {
            return Set.of();
        }

        Set<String> nodes = new HashSet<>();

        for (Object[] pair : controlParamLinkRepo.findParametrisedNodes(
                processIds, operationIds
        )) {
            nodes.add(routePairKey((Long) pair[0], (Long) pair[1]));
        }

        return nodes;
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
     * Ключ экземпляра работы: номер основания, позиция, номер внутри
     * позиции и пара маршрута.
     *
     * Экземпляр в ключе обязателен: позиция на десять изделий
     * закрывается десятью фактами, и разблокировать нужно только то
     * изделие, по которому предшественник действительно проведён.
     *
     * Номер основания строится из признака ветви и номера заказа, чтобы
     * номера обоих реестров (заказ покупателя и групповой ЗНП, V56) не
     * схлопнулись в одну работу, если случайно совпадут.
     */
    private static String instanceKey(
            String baseKey,
            Integer itemPosition,
            Integer instanceIndex,
            String pairKey
    ) {
        return baseKey + "|" + itemPosition + "|" + instanceIndex
                + "|" + pairKey;
    }

    /*
     * Признак и номер основания строки графика.
     *
     * У строки ровно одно основание (chk_osr_base_exactly_one). Для
     * плана заказа покупателя это его номер, для группового ЗНП —
     * номер заказа на производство.
     */
    private String baseKeyOf(OrderScheduleRow row) {
        if (row.getProductionOrder() != null) {
            return "PO:" + row.getProductionOrder().getNumber();
        }
        if (row.getCustomerOrder() != null) {
            return "CO:" + row.getCustomerOrder().getNumber();
        }
        return null;
    }

    /**
     * Собирает ключи закрытых и открытых строк из проекции.
     *
     * Ключ экземпляра строится по признаку ветви, номеру основания и
     * позиции в маршруте — так же, как при проверке блокировки.
     */
    private void collectInstanceKeys(
            Set<String> closedKeys,
            Set<String> openKeys,
            List<OpenInstanceRowView> views
    ) {
        for (OpenInstanceRowView view : views) {
            String key = instanceKey(
                    view.getBaseKind() + ":" + view.getOrderNumber(),
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
     * Номера внесённых ячеек группы одной строкой.
     *
     * Двойник {@link #serialsOf} для групповых ЗНП: подписывает группу
     * теми же номерами, что видит мастер в разделе распределения
     * групповых заказов, — исполнитель и руководитель обязаны называть
     * работу одинаково.
     */
    private List<String> cellNumbersOf(
            List<OrderScheduleRow> group,
            Map<Long, AttachedCellView> cellMap
    ) {
        if (cellMap == null || cellMap.isEmpty()) {
            return List.of();
        }

        List<String> numbers = new ArrayList<>();

        for (OrderScheduleRow row : group) {
            AttachedCellView cell = cellMap.get(row.getId());
            String number = cell == null ? null : cell.getFullNumber();

            if (number != null && !number.isBlank()
                    && !numbers.contains(number)) {
                numbers.add(number);
            }
        }

        return numbers;
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
        return null;
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
