package ru.company.production.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.company.production.dto.AssignableExecutor;
import ru.company.production.dto.GroupZnpAssignRow;
import ru.company.production.dto.GroupZnpBatchRow;
import ru.company.production.dto.GroupZnpOperationRow;
import ru.company.production.dto.GroupZnpSerialRow;
import ru.company.production.dto.GroupZnpView;
import ru.company.production.entity.AppUser;
import ru.company.production.entity.OrderScheduleRow;
import ru.company.production.entity.ProductSerial;
import ru.company.production.entity.ProductionOrder;
import ru.company.production.entity.ScheduleRowStatus;
import ru.company.production.entity.SerialProductionOrder;
import ru.company.production.entity.ZnpKind;
import ru.company.production.repository.GroupZnpDistributionRepository;
import ru.company.production.repository.OrderScheduleRowRepository;
import ru.company.production.repository.ProductSerialRepository;
import ru.company.production.repository.UserRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
 * Распределение работ по групповым ЗНП.
 *
 * Руководитель подразделения видит групповые заказы-комплектующие
 * (V38), чей план принадлежит его цеху. Разрез экрана обратен общему
 * распределению: там мастер выбирает изделие и его операции, а здесь
 * работа заявлена группой ячеек одного наименования, поэтому думать
 * начинают с того, какие ячейки уже на участке.
 *
 * Экран трёхуровневый:
 *
 *   * заказ — номер, изделие, количество и маршрут (V40);
 *   * ячейки заказа — состав, внесённый складом (матрица
 *       комплектования, V20);
 *   * операции ячейки — строки плана (V56) с датой, трудоёмкостью и
 *       исполнителем; под операцией — сотрудники, способные её
 *       выполнить, с загрузкой на дату плана.
 *
 * Назначение ставится на строку плана: мастер закрепляет работу над
 * конкретной ячейкой, и десять ячеек одного заказа вправе вести десять
 * человек. Чекбокс «выделить все» собирает отмеченные ячейки операции
 * в одно назначение, когда работу ведёт один человек, — форма отдаёт
 * список {@code rowId}, и сервер не угадывает, что именно имел в виду
 * мастер.
 *
 * Способные исполнители и их загрузка считаются тем же
 * {@link WorkshopDistributionService}, что и в общем распределении:
 * два экрана одного руководителя обязаны показывать одинаковый список
 * кандидатов и одинаковые цифры занятости.
 */
@Service
@RequiredArgsConstructor
public class GroupZnpDistributionService {

    /** Срок работ по умолчанию: две недели — как в общем распределении. */
    public static final int DEFAULT_WEEKS = WorkshopDistributionService.DEFAULT_WEEKS;

    /** Верхняя граница срока. */
    public static final int MAX_WEEKS = WorkshopDistributionService.MAX_WEEKS;

    private static final DateTimeFormatter RU_DATE_TIME =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    /**
     * Виды, попадающие в раздел. Список берётся у {@link ZnpKind}, а не
     * переписывается константами: новый групповой вид окажется в разделе
     * сам.
     */
    private static final List<ZnpKind> GROUP_KINDS = ZnpKind.groupKinds();

    private final GroupZnpDistributionRepository groupZnpRepo;
    private final OrderScheduleRowRepository scheduleRowRepo;
    private final ProductSerialRepository serialRepo;
    private final UserRepository userRepository;
    private final WorkshopStaffService staffService;
    private final WorkshopDistributionService distributionService;

    /**
     * Групповые ЗНП подразделения с ячейками и их операциями.
     *
     * Три пакетных запроса на всю страницу — заказы, состав, строки
     * плана: страница перечисляет десятки заказов, и выборка на каждую
     * строку дала бы N+1 на экране, который мастер открывает за смену
     * много раз.
     *
     * Граница — подразделения учётной записи, а не параметр запроса:
     * мастер одного цеха не увидит работу чужого.
     */
    @Transactional(readOnly = true)
    public List<GroupZnpView> findGroupZnp(
            Collection<Long> unitIds,
            int weeks
    ) {
        List<Long> scope = cleanIds(unitIds);

        if (scope.isEmpty()) {
            return List.of();
        }

        LocalDate from = LocalDate.now();
        LocalDate to = from.plusDays(7L * normalizeWeeks(weeks));

        List<ProductionOrder> orders = groupZnpRepo.findGroupZnpOfUnits(
                GROUP_KINDS, ScheduleRowStatus.CONFIRMED, scope, from, to
        );

        if (orders.isEmpty()) {
            return List.of();
        }

        List<Long> orderIds = orders.stream()
                .map(ProductionOrder::getId)
                .filter(Objects::nonNull)
                .toList();

        if (orderIds.isEmpty()) {
            return List.of();
        }

        /*
         * Состав заказов: ячейки, внесённые складом.
         */
        Map<Long, List<SerialProductionOrder>> cellsByOrder =
                groupCellsByOrder(
                        groupZnpRepo.findCellsOfGroupZnp(orderIds)
                );

        /*
         * План цеха по этим заказам: строки операций подразделения.
         */
        List<OrderScheduleRow> planRows =
                groupZnpRepo.findPlanRowsOfGroupZnp(
                        orderIds, ScheduleRowStatus.CONFIRMED, scope
                );

        /*
         * Серийники ячеек читаются одним запросом: open-in-view
         * выключен, и полное наименование cell-сущности вне сессии
         * недоступно.
         */
        Map<Long, ProductSerial> serialsById =
                loadSerials(cellsByOrder.values());

        /*
         * Кэш способных сотрудников на весь экран: одна операция на
         * одну дату даёт одинаковых кандидатов для всех ячеек.
         */
        Map<String, List<AssignableExecutor>> capableCache = new HashMap<>();

        List<GroupZnpView> result = new ArrayList<>(orders.size());

        for (ProductionOrder order : orders) {
            List<SerialProductionOrder> cells =
                    cellsByOrder.getOrDefault(order.getId(), List.of());

            List<OrderScheduleRow> rows = rowsOfOrder(planRows, order.getId());

            result.add(toView(
                    order, cells, rows, serialsById, capableCache, from, to
            ));
        }

        return result;
    }

    /**
     * Назначение выбранных работ ячейки на исполнителя.
     *
     * Форма отдаёт список {@code rowId}: так один и тот же путь
     * обслуживает и назначение одной операции одной ячейки, и
     * пакетное назначение по чекбоксу «выделить все» — сервер
     * закрепляет ровно то, что отметил мастер, а не всю операцию
     * заказа целиком.
     *
     * Проверки собраны вокруг одной выборки: строка обязана
     * существовать, быть внесена в план, принадлежать подразделению
     * руководителя, относиться к групповому заказу и ещё не иметь
     * исполнителя.
     */
    @Transactional
    public void assignRows(
            List<Long> rowIds,
            Long executorId,
            AppUser supervisor
    ) {
        List<Long> ids = cleanIds(rowIds);

        if (ids.isEmpty()) {
            throw new IllegalArgumentException(
                    "Не отмечено ни одной работы для назначения"
            );
        }

        if (executorId == null) {
            throw new IllegalArgumentException("Выберите исполнителя");
        }

        List<Long> unitIds = staffService.unitScope(supervisor);

        List<OrderScheduleRow> rows = loadAssignableRows(ids, unitIds);

        AppUser executor = findById(executorId);

        staffService.requireSameWorkshop(executor, supervisor);

        LocalDateTime now = LocalDateTime.now();

        for (OrderScheduleRow row : rows) {
            row.setExecutor(executor);
            row.setAssignedBy(supervisor);
            row.setAssignedAt(now);
        }

        scheduleRowRepo.saveAll(rows);
    }

    /**
     * Снятие назначения с выбранных работ.
     *
     * Снимается ровно то, что отметил мастер: соседние ячейки той же
     * операции остаются за своими исполнителями, иначе снятие одной
     * кнопки переписывало бы распределение всего заказа.
     */
    @Transactional
    public void unassignRows(List<Long> rowIds, AppUser supervisor) {
        List<Long> ids = cleanIds(rowIds);

        if (ids.isEmpty()) {
            throw new IllegalArgumentException(
                    "Не отмечено ни одной работы для снятия"
            );
        }

        List<Long> unitIds = staffService.unitScope(supervisor);

        List<OrderScheduleRow> rows = new ArrayList<>();

        for (OrderScheduleRow row : scheduleRowRepo.findAllById(ids)) {
            requireOwnUnit(row, unitIds);

            if (row.getExecutor() == null) {
                continue;
            }

            row.setExecutor(null);
            row.setAssignedBy(null);
            row.setAssignedAt(null);
            rows.add(row);
        }

        if (rows.isEmpty()) {
            throw new IllegalArgumentException(
                    "Отмеченные работы ещё не назначены"
            );
        }

        scheduleRowRepo.saveAll(rows);
    }

    /**
     * Нераспределённые работы групповых заказов — счётчик пункта меню.
     *
     * Считается запросом, а не размером списка: меню рисуется на
     * каждой странице модуля, а подбор кандидатов и их загрузки ради
     * одной цифры стоил бы десятков обращений к базе.
     */
    @Transactional(readOnly = true)
    public long countUnassigned(Collection<Long> unitIds) {
        List<Long> scope = cleanIds(unitIds);

        if (scope.isEmpty()) {
            return 0L;
        }

        LocalDate from = LocalDate.now();
        LocalDate to = from.plusDays(7L * DEFAULT_WEEKS);

        return scheduleRowRepo.countGroupZnpUnassigned(
                ScheduleRowStatus.CONFIRMED,
                scope,
                ZnpKind.groupKinds(),
                from,
                to
        );
    }

    /**
     * Срок в неделях: всё, что вне диапазона, — дефолт.
     *
     * Параметр приходит из адресной строки, и «weeks=99999» не должен
     * превращать страницу в выборку на десять лет.
     */
    public static int normalizeWeeks(Integer weeks) {
        return WorkshopDistributionService.normalizeWeeks(weeks);
    }

    /*
     * ============================================================
     * Сборка экрана
     * ============================================================
     */

    /**
     * Заказ + его ячейки + строки плана → строка раздела.
     *
     * Уровень ячеек строится по ЭКЗЕМПЛЯРАМ ПЛАНА, а не по матрице
     * комплектования. Планировщик заводит экземпляры по заказанному
     * количеству сразу при взятии заказа в работу (V56), а склад вносит
     * номера позже и вправе внести не все. Строка, построенная от
     * матрицы, молча теряла бы работы того изделия, которому номер ещё
     * не присвоен, — и первая же операция заказа («Комплектование
     * подсборок», которой этот номер и появляется) оставалась бы без
     * адресата.
     *
     * Ячейка сопоставляется экземпляру по порядку: склад закрывает
     * ближайший незакрытый экземпляр (см.
     * SubassemblyKittingService#completeScheduleRow), поэтому i-я по
     * моменту внесения ячейка принадлежит экземпляру с номером i.
     * Колонки-связи между матрицей и планом в схеме нет.
     */
    private GroupZnpView toView(
            ProductionOrder order,
            List<SerialProductionOrder> cells,
            List<OrderScheduleRow> rows,
            Map<Long, ProductSerial> serialsById,
            Map<String, List<AssignableExecutor>> capableCache,
            LocalDate from,
            LocalDate to
    ) {
        /*
         * Строки плана группируются по экземпляру, а внутри
         * экземпляра сохраняют порядок маршрута (seq) — так раскрытая
         * ячейка показывает работы в том порядке, в каком их ведёт
         * маршрут.
         */
        Map<Integer, List<OrderScheduleRow>> rowsByInstance =
                new LinkedHashMap<>();

        for (OrderScheduleRow row : rows) {
            Integer index = row.getInstanceIndex();

            if (index == null) {
                continue;
            }

            rowsByInstance
                    .computeIfAbsent(index, k -> new ArrayList<>())
                    .add(row);
        }

        /*
         * Показываем каждый экземпляр, о чём-либо известном: и тот, у
         * которого есть работы подразделения, и тот, которому склад уже
         * присвоил номер. Порядок — по номеру экземпляра, он же порядок
         * внесения ячеек.
         */
        java.util.TreeSet<Integer> indexes =
                new java.util.TreeSet<>(rowsByInstance.keySet());

        for (int i = 0; i < cells.size(); i++) {
            indexes.add(i);
        }

        List<GroupZnpSerialRow> serialRows =
                new ArrayList<>(indexes.size());

        for (Integer index : indexes) {
            ProductSerial serial = serialAt(cells, index, serialsById);

            serialRows.add(new GroupZnpSerialRow(
                    index + 1,
                    serial == null ? null : serial.getId(),
                    serial == null ? null : serial.getFullNumber(),
                    serial == null ? null : serial.getDisplayName(),
                    toOperations(
                            rowsByInstance.get(index),
                            capableCache,
                            from,
                            to
                    )
            ));
        }

        return new GroupZnpView(
                order.getId(),
                order.getNumber(),
                order.getZnpKindDisplayName(),
                order.getProductTypeName(),
                order.getClassifierLabel(),
                order.getFormattedQuantity(),
                order.getTechProcessCode(),
                order.getPriorityDisplayName(),
                order.getPriorityBadgeClass(),
                order.getStatusDisplayName(),
                order.getStatusBadgeClass(),
                serialRows,
                toBatchRows(rows, cells, serialsById, capableCache, from, to)
        );
    }

    /**
     * Строки плана одной ячейки → операции с подбором людей.
     */
    private List<GroupZnpOperationRow> toOperations(
            List<OrderScheduleRow> rows,
            Map<String, List<AssignableExecutor>> capableCache,
            LocalDate from,
            LocalDate to
    ) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }

        List<GroupZnpOperationRow> result = new ArrayList<>(rows.size());

        for (OrderScheduleRow row : rows) {
            result.add(new GroupZnpOperationRow(
                    row.getId(),
                    row.getOperation() == null
                            ? null
                            : row.getOperation().getId(),
                    operationName(row),
                    row.getPlannedDate(),
                    row.getLaborHours(),
                    row.getExecutor() != null,
                    row.getExecutor() == null
                            ? null
                            : row.getExecutor().getId(),
                    row.getExecutor() == null
                            ? null
                            : WorkshopStaffService.displayName(
                                    row.getExecutor()
                            ),
                    row.getCompletedAt() != null,
                    row.getCompletedAt() == null
                            ? null
                            : row.getCompletedAt().format(RU_DATE_TIME),
                    capableCache.computeIfAbsent(
                            capableKey(row),
                            k -> distributionService.capableExecutors(
                                    List.of(row), row.getPlannedDate(),
                                    from, to
                            )
                    )
            ));
        }

        return result;
    }

    /**
     * Сводка по операциям заказа — блок пакетного назначения.
     *
     * Операция показывается одной строкой с чекбоксом «выделить все»:
     * под ней строки ячеек, и мастер отмечает нужные (или все сразу),
     * когда работу ведёт один человек. Ключом остаётся {@code rowId},
     * поэтому снятая отметка исключает ячейку из назначения, а не
     * перекладывает её на другого.
     */
    private List<GroupZnpBatchRow> toBatchRows(
            List<OrderScheduleRow> rows,
            List<SerialProductionOrder> cells,
            Map<Long, ProductSerial> serialsById,
            Map<String, List<AssignableExecutor>> capableCache,
            LocalDate from,
            LocalDate to
    ) {
        if (rows.isEmpty()) {
            return List.of();
        }

        /*
         * Порядок операций — по маршруту, затем по дате плана:
         * мастер читает сводку как перечень этапов, а не как случайный
         * набор.
         */
        Map<String, List<OrderScheduleRow>> byOperation =
                new LinkedHashMap<>();

        for (OrderScheduleRow row : rows) {
            byOperation
                    .computeIfAbsent(
                            operationKey(row),
                            k -> new ArrayList<>()
                    )
                    .add(row);
        }

        List<GroupZnpBatchRow> result =
                new ArrayList<>(byOperation.size());

        for (List<OrderScheduleRow> group : byOperation.values()) {
            OrderScheduleRow head = group.get(0);

            List<GroupZnpAssignRow> assignRows =
                    new ArrayList<>(group.size());

            BigDecimal totalHours = BigDecimal.ZERO;
            long assignedCount = 0L;

            for (OrderScheduleRow row : group) {
                ProductSerial serial = serialAt(cells, row, serialsById);

                assignRows.add(new GroupZnpAssignRow(
                        row.getId(),
                        row.getInstanceIndex() == null
                                ? 0
                                : row.getInstanceIndex() + 1,
                        serial == null ? null : serial.getId(),
                        serial == null ? null : serial.getFullNumber(),
                        serial == null ? null : serial.getDisplayName(),
                        row.getExecutor() != null,
                        row.getExecutor() == null
                                ? null
                                : WorkshopStaffService.displayName(
                                        row.getExecutor()
                                ),
                        row.getCompletedAt() != null
                ));

                totalHours = totalHours.add(row.getLaborHours());

                if (row.getExecutor() != null) {
                    assignedCount++;
                }
            }

            result.add(new GroupZnpBatchRow(
                    head.getOperation() == null
                            ? null
                            : head.getOperation().getId(),
                    operationName(head),
                    head.getPlannedDate(),
                    hoursLabel(head.getLaborHours()),
                    totalHours,
                    assignRows,
                    assignedCount,
                    capableCache.computeIfAbsent(
                            capableKey(head),
                            k -> distributionService.capableExecutors(
                                    group, head.getPlannedDate(), from, to
                            )
                    )
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
     * Строки плана одного заказа.
     */
    private List<OrderScheduleRow> rowsOfOrder(
            List<OrderScheduleRow> rows,
            Long orderId
    ) {
        List<OrderScheduleRow> result = new ArrayList<>();

        for (OrderScheduleRow row : rows) {
            if (row.getProductionOrder() != null
                    && Objects.equals(row.getProductionOrder().getId(),
                            orderId)) {
                result.add(row);
            }
        }

        return result;
    }

    /**
     * Ячейки → группировка по заказу, порядок запроса сохраняется.
     */
    private Map<Long, List<SerialProductionOrder>> groupCellsByOrder(
            List<SerialProductionOrder> cells
    ) {
        Map<Long, List<SerialProductionOrder>> result = new LinkedHashMap<>();

        for (SerialProductionOrder link : cells) {
            if (link.getProductionOrder() == null
                    || link.getProductionOrder().getId() == null) {
                continue;
            }

            result.computeIfAbsent(
                    link.getProductionOrder().getId(),
                    k -> new ArrayList<>()
            ).add(link);
        }

        return result;
    }

    /**
     * Серийник ячейки из пакетно загруженной карты.
     */
    private ProductSerial serialOf(
            SerialProductionOrder link,
            Map<Long, ProductSerial> serialsById
    ) {
        if (link == null || link.getSerial() == null
                || link.getSerial().getId() == null) {
            return null;
        }

        return serialsById.get(link.getSerial().getId());
    }

    /**
     * Ячейка, соответствующая экземпляру строки плана.
     */
    private ProductSerial serialAt(
            List<SerialProductionOrder> cells,
            OrderScheduleRow row,
            Map<Long, ProductSerial> serialsById
    ) {
        return serialAt(cells, row.getInstanceIndex(), serialsById);
    }

    /**
     * Ячейка, соответствующая экземпляру по его номеру.
     *
     * Экземпляр без внесённой ячейки — норма, а не ошибка: склад
     * вносит номера по мере приёмки, и i-й экземпляр получает ячейку,
     * только когда i-я по счёту подсборка проведена в заказ.
     */
    private ProductSerial serialAt(
            List<SerialProductionOrder> cells,
            Integer index,
            Map<Long, ProductSerial> serialsById
    ) {
        if (index == null || index < 0 || index >= cells.size()) {
            return null;
        }

        return serialOf(cells.get(index), serialsById);
    }

    /**
     * Серийники всех ячеек страницы — одним запросом.
     */
    private Map<Long, ProductSerial> loadSerials(
            Collection<List<SerialProductionOrder>> cellsByOrder
    ) {
        Set<Long> ids = new LinkedHashSet<>();

        for (List<SerialProductionOrder> links : cellsByOrder) {
            for (SerialProductionOrder link : links) {
                if (link.getSerial() != null
                        && link.getSerial().getId() != null) {
                    ids.add(link.getSerial().getId());
                }
            }
        }

        if (ids.isEmpty()) {
            return Map.of();
        }

        Map<Long, ProductSerial> result = new HashMap<>();

        for (ProductSerial serial : serialRepo.findAllById(ids)) {
            if (serial != null && serial.getId() != null) {
                result.put(serial.getId(), serial);
            }
        }

        return result;
    }

    /**
     * Строки по идентификаторам с проверкой права руководителя.
     *
     * Читаются все запрошенные строки, а не только найденные:
     * отсутствующий {@code rowId} означает, что работу уже сняли с
     * плана, и молча назначить остальные было бы искажением выбора
     * мастера.
     */
    private List<OrderScheduleRow> loadAssignableRows(
            List<Long> ids,
            List<Long> unitIds
    ) {
        Map<Long, OrderScheduleRow> found = new LinkedHashMap<>();

        for (OrderScheduleRow row : scheduleRowRepo.findAllById(ids)) {
            found.put(row.getId(), row);
        }

        List<OrderScheduleRow> rows = new ArrayList<>(ids.size());

        for (Long id : ids) {
            OrderScheduleRow row = found.get(id);

            if (row == null) {
                throw new IllegalArgumentException(
                        "Работа уже снята с плана, обновите страницу"
                );
            }

            requireAssignable(row, unitIds);

            if (row.getExecutor() != null) {
                throw new IllegalArgumentException(
                        "Работа «" + operationName(row)
                                + "» уже назначена: снимите назначение"
                );
            }

            rows.add(row);
        }

        return rows;
    }

    /**
     * Проверка, что строку можно назначить: она в плане, принадлежит
     * групповому заказу и подразделению руководителя.
     */
    private void requireAssignable(
            OrderScheduleRow row,
            Collection<Long> unitIds
    ) {
        if (row.getStatus() != ScheduleRowStatus.CONFIRMED) {
            throw new IllegalArgumentException(
                    "Назначать можно только внесённые в план работы"
            );
        }

        if (row.getProductionOrder() == null
                || row.getProductionOrder().getZnpKind() == null
                || !row.getProductionOrder().getZnpKind().isGroupKind()) {
            throw new IllegalArgumentException(
                    "Работа относится не к групповому заказу"
            );
        }

        requireOwnUnit(row, unitIds);
    }

    /**
     * Граница подразделения: чужую работу мастер не трогает, даже
     * изменив идентификатор строки в форме.
     */
    private void requireOwnUnit(
            OrderScheduleRow row,
            Collection<Long> unitIds
    ) {
        if (row.getOrganizationUnit() == null
                || !cleanIds(unitIds)
                        .contains(row.getOrganizationUnit().getId())) {
            throw new IllegalArgumentException(
                    "Работа относится к другому подразделению"
            );
        }
    }

    /**
     * Кэш подбора: одна операция на одну дату даёт одинаковых
     * кандидатов для всех ячеек и всех заказов экрана.
     */
    private static String capableKey(OrderScheduleRow row) {
        Long operationId = row.getOperation() == null
                ? null
                : row.getOperation().getId();
        Long processId = row.getTechProcess() == null
                ? null
                : row.getTechProcess().getId();

        return operationId + "|" + row.getPlannedDate() + "|" + processId;
    }

    /**
     * Ключ группы сводки: операция и дата плана.
     *
     * Дата входит в ключ наравне с операцией: одна и та же операция
     * у разных ячеек могла быть запланирована на разные дни, и
     * смешать их в одной строке — значит показать мастеру одну дату
     * там, где их две.
     */
    private static String operationKey(OrderScheduleRow row) {
        return (row.getOperation() == null
                ? "0"
                : String.valueOf(row.getOperation().getId()))
                + "|" + row.getPlannedDate();
    }

    private static String operationName(OrderScheduleRow row) {
        if (row.getOperation() == null
                || row.getOperation().getOperationName() == null) {
            return "операция";
        }

        return row.getOperation().getOperationName().getName();
    }

    /** Трудоёмкость без хвостовых нулей: «1», а не «1.000». */
    private static String hoursLabel(BigDecimal hours) {
        if (hours == null) {
            return "—";
        }

        return hours.setScale(1, java.math.RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString();
    }

    /**
     * Идентификаторы без null и повторов: пустой список безопаснее
     * пустого {@code in ()} в JPQL, а {@link LinkedHashSet} убирает
     * дубли, не ломая порядок отметок мастера.
     */
    private static List<Long> cleanIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }

        return new ArrayList<>(
                new LinkedHashSet<>(ids).stream()
                        .filter(Objects::nonNull)
                        .toList()
        );
    }

    private AppUser findById(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Сотрудник не найден"
                ));
    }
}