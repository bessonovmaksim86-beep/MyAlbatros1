package ru.company.production.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.company.production.dto.KittingCellRow;
import ru.company.production.dto.QuarantineFilter;
import ru.company.production.dto.QuarantineReasonGroup;
import ru.company.production.dto.RepairKittingGroup;
import ru.company.production.dto.RepairKittingZnpView;
import ru.company.production.entity.AppUser;
import ru.company.production.entity.Operation;
import ru.company.production.entity.OperationTypeCode;
import ru.company.production.entity.OrderScheduleRow;
import ru.company.production.entity.ProductClassifier;
import ru.company.production.entity.ProductSerial;
import ru.company.production.entity.ProductSerialOperation;
import ru.company.production.entity.ProductSerialOperationStatus;
import ru.company.production.entity.ProductionOrder;
import ru.company.production.entity.ProductionOrderStatus;
import ru.company.production.entity.QuarantineCellKind;
import ru.company.production.entity.RepairKittingItem;
import ru.company.production.entity.SerialProductionOrder;
import ru.company.production.entity.StaffBindingType;
import ru.company.production.entity.TechProcessOperation;
import ru.company.production.entity.WorkshopStaffBinding;
import ru.company.production.entity.ZnpKind;
import ru.company.production.repository.InputControlDefectGroupRepository;
import ru.company.production.repository.OperationRepository;
import ru.company.production.repository.OrderScheduleRowRepository;
import ru.company.production.repository.ProductClassifierRepository;
import ru.company.production.repository.ProductSerialOperationRepository;
import ru.company.production.repository.ProductSerialRepository;
import ru.company.production.repository.ProductionOrderRepository;
import ru.company.production.repository.RepairKittingItemRepository;
import ru.company.production.repository.SerialProductionOrderRepository;
import ru.company.production.repository.TechProcessOperationRepository;
import ru.company.production.repository.WorkshopStaffBindingRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Комплектование ремонтных заказов-комплектующих ячейками из изолятора.
 *
 * Диспетчер заводит ЗНП на ремонт ячеек или подсборок (V37) и ведёт его
 * по техпроцессу, в котором есть комплектовочная операция бюро
 * контроля. Bureau два — входного (V30) и технического (V2/V66): какое
 * именно комплектует заказ, решает подразделение операции маршрута, а
 * не вид заказа. Бюро эту операцию исполняет так: открывает изолятор
 * брака с тем же фильтром, что и служба качества, отмечает нужные
 * ячейки и проводит их — заказ закрывается не «галочкой», а списком
 * фактически взятых номеров ({@link RepairKittingItem}, V52).
 *
 * МЕНЮ ОДНО НА ОБА БЮРО. Раздел живёт в «Мои операции», адрес которого
 * открыт всем аутентифицированным, поэтому подбор заказов держится
 * сразу обоих подразделений, а границу «какие заказы видеть» проводит
 * {@link #permittedOperationIds}: исполнителю БВК доступны операции
 * только его подразделения, и заказ БТК он не увидит, и наоборот.
 * Держать два отдельных запроса означало бы расходиться в отборе при
 * первой же правке.
 *
 * ОТЛИЧИЕ ОТ СКЛАДСКОГО КОМПЛЕКТОВАНИЯ. Склад принимает ячейки, уже
 * выпущенные как серийные номера, и лишь подтверждает строку,
 * поставленную при комплектовании (см. ZnpAcceptanceService). Здесь же
 * состав заказа задан изделием и количеством, а не серийниками, поэтому
 * строку операции приходится создавать: без неё факт работы повис бы
 * только в журнале перемещений и не попал ни в матрицу изделия, ни в
 * загрузку исполнителя.
 *
 * ГРАНИЦА ПРАВ. Проводить вправе исполнитель, за которым закреплена
 * комплектовочная операция заказа, и руководитель бюро (или службы) —
 * тот видит заказы своего подразделения целиком. Проверка держится
 * здесь, а не в SecurityConfig: /my-operations/** открыт всем
 * аутентифицированным, и адрес не различает бюро и цех.
 */
@Service
@RequiredArgsConstructor
public class RepairKittingOperationService {

    /** Код подразделения «Бюро входного контроля» (миграция V30). */
    public static final String BVK_UNIT_CODE =
            RepairKittingService.BVK_UNIT_CODE;

    /** Код подразделения «Бюро технического контроля» (миграции V2, V66). */
    public static final String BTK_UNIT_CODE =
            RepairKittingService.BTK_UNIT_CODE;

    /**
     * Подразделения, чьи комплектовочные операции ведёт этот раздел.
     *
     * Оба бюро контроля: меню одно, а кому из них принадлежит заказ,
     * решает операция маршрута. Границу видимости держит
     * {@link #permittedOperationIds}, а не этот список.
     */
    private static final List<String> KITTING_UNIT_CODES = List.of(
            BVK_UNIT_CODE,
            BTK_UNIT_CODE
    );

    /**
     * Типы комплектовочной операции ремонтного заказа.
     *
     * Те же три кода, что у {@link RepairKittingService}: узел маршрута
     * обязан находиться по тому же признаку, по которому подобран сам
     * заказ, — иначе заказ попал бы в список, а провести его было бы не
     * по чему.
     */
    private static final List<String> KITTING_TYPE_CODES = List.of(
            OperationTypeCode.KITTING.name(),
            OperationTypeCode.KITTING_CELL.name(),
            OperationTypeCode.KITTING_SUBASSEMBLY.name()
    );

    /** Дата плана в подписи строки — тот же формат, что у ProductionOrder. */
    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("dd.MM.yyyy");

    /** Длина полного номера ячейки: 4 цифры кода + 5 цифр серии. */
    private static final int FULL_NUMBER_LENGTH = 9;

    /** Длина кода классификатора в начале полного номера. */
    private static final int CODE_LENGTH = 4;

    private final ProductionOrderRepository productionOrderRepository;
    private final TechProcessOperationRepository techProcessOperationRepository;
    private final RepairKittingItemRepository itemRepository;
    private final ProductSerialOperationRepository serialOperationRepository;
    private final ProductSerialRepository productSerialRepository;
    private final ProductClassifierRepository productClassifierRepository;
    private final OperationRepository operationRepository;
    private final WorkshopStaffBindingRepository bindingRepository;
    private final WorkshopStaffService staffService;
    private final InputControlService inputControlService;
    private final SerialMovementJournalService journalService;

    /*
     * Матрица комплектования (V20): SerialProductionOrder создаётся при
     * каждой внесённой ячейке. Цеховой resolveAttachedCells читает эту
     * таблицу, и без строки здесь «Получение комплектации» показывало
     * бы «—» вместо номера.
     */
    private final SerialProductionOrderRepository serialProductionOrderRepository;

    /*
     * План заказа (V56): строки графика второй ветви — то, что читает
     * цех в «Получении комплектации». Бюро обязано провести свою
     * комплектовочную строку само, иначе «Комплектование ремонтных
     * подсборок» осталось бы незакрытым в плане и следующая по маршруту
     * операция цеха не разблокировалась бы, даже когда весь состав
     * взят (тот же приём, что у SubassemblyKittingService).
     */
    private final OrderScheduleRowRepository scheduleRowRepository;

    /* ============================================================
     * МЕНЮ «ОПЕРАЦИЯ → ЗНП В РАБОТЕ»
     * ============================================================ */

    /**
     * Группы меню: операция бюро и её ремонтные заказы в работе.
     *
     * Группировка идёт по операции, а не по заказу, потому что
     * исполнитель мыслит операцией: заказов у одной операции несколько,
     * и плоский список заставил бы человека искать, к какой работе
     * относится каждый.
     *
     * Заказ привязывается к операции через маршрут: у ремонта нет
     * строки графика, связанной с заказом (у order_schedule_rows нет
     * колонки заказа на производство), зато есть техпроцесс, и именно в
     * нём стоит комплектовочная операция бюро — тот же признак, по
     * которому {@link RepairKittingService} отбирает заказы в раздел.
     *
     * Заказы, у которых маршрута или такой операции не нашлось, в меню
     * не попадают: провести их не по чему, и показывать заказ без кнопки
     * означало бы обещать то, что страница не выполняет.
     */
    @Transactional(readOnly = true)
    public List<RepairKittingGroup> findMyKittingGroups(AppUser user) {

        List<ProductionOrder> orders = repairOrders();

        if (orders.isEmpty()) {
            return List.of();
        }

        Map<Long, TechProcessOperation> nodeByProcess =
                kittingNodesByProcess(orders);

        if (nodeByProcess.isEmpty()) {
            return List.of();
        }

        Map<Long, Long> doneByOrder = doneCountByOrder(orders);
        Set<Long> permitted = permittedOperationIds(user);
        Map<GroupKey, List<ProductionOrder>> byGroup = new LinkedHashMap<>();
        Map<GroupKey, String> operationNames = new LinkedHashMap<>();

        if (permitted.isEmpty()) {
            return List.of();
        }

        for (ProductionOrder order : orders) {

            TechProcessOperation node = nodeByProcess.get(processId(order));

            if (node == null || node.getOperation() == null
                    || !permitted.contains(node.getOperation().getId())) {
                continue;
            }

            Operation operation = node.getOperation();
            GroupKey key = new GroupKey(
                    order.getZnpKind().requiredCellKind(),
                    operation.getId()
            );

            byGroup
                    .computeIfAbsent(key, unused -> new ArrayList<>())
                    .add(order);

            operationNames.putIfAbsent(key, operationName(operation));
        }

        List<RepairKittingGroup> groups = new ArrayList<>();

        for (Map.Entry<GroupKey, List<ProductionOrder>> entry
                : byGroup.entrySet()) {

            groups.add(new RepairKittingGroup(
                    entry.getKey().cellKind(),
                    entry.getKey().operationId(),
                    operationNames.get(entry.getKey()),
                    entry.getValue().stream()
                            .map(order -> toView(order,
                                    doneByOrder.getOrDefault(order.getId(), 0L)))
                            .toList()
            ));
        }

        /*
         * Порядок: подсборки, затем ячейки — тот же, что в enum
         * QuarantineCellKind и в переключателе режимов изолятора, чтобы
         * оба кабинета читались одинаково; внутри — по имени операции.
         */
        groups.sort(
                Comparator.comparingInt(
                        (RepairKittingGroup group) -> group.cellKind().ordinal())
                        .thenComparing(RepairKittingGroup::operationName)
        );

        return groups;
    }

    /**
     * Ключ группы: разновидность ячейки и операция.
     *
     * Отдельный record, а не строка «CELL:12»: составной ключ из
     * конкатенации пришлось бы разбирать, чтобы положить вид заказа в
     * готовую группу, а ошибка в разделителе осталась бы незамеченной.
     */
    private record GroupKey(QuarantineCellKind cellKind, Long operationId) {
    }

    /**
     * Число заказов в меню — счётчик пункта.
     *
     * Считается тем же подбором, что и сам список: счётчик обязан
     * показывать то, что человек увидит внутри, иначе цифра в меню
     * спорила бы с пустой страницей.
     */
    @Transactional(readOnly = true)
    public long countMyKittingOrders(AppUser user) {
        return findMyKittingGroups(user).stream()
                .mapToLong(RepairKittingGroup::orderCount)
                .sum();
    }

    /* ============================================================
     * ФОРМА КОМПЛЕКТОВАНИЯ
     * ============================================================ */

    /** Заказ в подписях, готовых к выводу (связи lazy, open-in-view выключен). */
    @Transactional(readOnly = true)
    public RepairKittingZnpView findOrderView(Long orderId) {

        return toView(
                requireRepairOrder(orderId),
                itemRepository.findFullNumbersByOrderId(orderId).size()
        );
    }

    /** Состав заказа: уже взятые ячейки с автором и датой проведения. */
    @Transactional(readOnly = true)
    public List<RepairKittingItem> findItems(Long orderId) {
        return itemRepository.findByOrderId(orderId);
    }

    /**
     * Перечень изолятора для этого заказа.
     *
     * Выборку отдаёт {@link InputControlService#findQuarantineCells} —
     * тот же запрос и тот же фильтр (разновидность, классификатор,
     * причина), что на странице «Изолятор брака»: ячейка обязана
     * выглядеть одинаково в обоих кабинетах, а дубль запроса разошёлся
     * бы с изолятором при первой же правке отбора.
     *
     * Разновидность подставляется по виду заказа, если пользователь её
     * не выбрал: в заказ на ремонт ячеек подсборка не входит, и
     * показывать её в перечне значило бы подталкивать к ошибке, которую
     * потом отклонит проверка проведения.
     *
     * Изделие подставляется по классификатору заказа по той же причине:
     * ремонтный ЗНП собран под одно изделие (5004 — ПВБ1), и подсборка
     * другого изделия (5005 — ПВБ2) в его состав не входит, хотя род у
     * них общий. Без этой подстановки перечень показывал бы все
     * подсборки изолятора, а селекта изделия в форме нет — отбирать
     * было бы нечем.
     *
     * Подстановка не перекрывает выбор человека: если изделие задано
     * фильтром, оно и остаётся (сужение внутри заказа безвредно, а
     * сброс фильтра возвращает именно изделие заказа).
     *
     * Уже взятые номера помечаются, а не убираются из перечня:
     * исполнитель обязан видеть, что заказ частично закрыт ранее (возможно,
     * другим контролёром), иначе он искал бы ячейку, которой «нет».
     */
    @Transactional(readOnly = true)
    public List<KittingCellRow> findCandidateCells(Long orderId,
                                                   QuarantineFilter filter) {

        ProductionOrder order = requireRepairOrder(orderId);
        QuarantineCellKind required = order.getZnpKind().requiredCellKind();

        QuarantineFilter applied = filter == null
                ? QuarantineFilter.empty()
                : filter;

        QuarantineFilter effective = new QuarantineFilter(
                applied.kind() == null ? required : applied.kind(),
                applied.classifierId() == null
                        ? order.getClassifierId()
                        : applied.classifierId(),
                applied.reasonId()
        );

        Set<String> takenByOrder = new LinkedHashSet<>(
                itemRepository.findFullNumbersByOrderId(orderId));
        Set<String> takenAnywhere = new LinkedHashSet<>(
                itemRepository.findAllFullNumbers());

        List<KittingCellRow> rows = new ArrayList<>();

        for (QuarantineCellView cell
                : inputControlService.findQuarantineCells(effective)) {

            rows.add(new KittingCellRow(
                    cell,
                    takenByOrder.contains(cell.getFullNumber()),
                    takenAnywhere.contains(cell.getFullNumber())
            ));
        }

        return rows;
    }

    /** Опции фильтра по классификатору — те же, что у изолятора. */
    @Transactional(readOnly = true)
    public List<InputControlDefectGroupRepository.QuarantineClassifierOption>
    findClassifierOptions() {
        return inputControlService.findQuarantineClassifierOptions();
    }

    /** Дерево причин брака — те же, что у изолятора. */
    @Transactional(readOnly = true)
    public List<QuarantineReasonGroup> findReasonGroups() {
        return inputControlService.findQuarantineReasonGroups();
    }

    /* ============================================================
     * ПРОВЕДЕНИЕ
     * ============================================================ */

    /**
     * Отмеченные ячейки берут в заказ и проводят по его операции.
     *
     * Порядок проверок — от дешёвой к важной: сначала заказ и право,
     * потом тип и количество, и только затем запись. Каждая проверка
     * отказывает до первой вставки, поэтому частичной проводки не
     * бывает: либо проведён весь отмеченный состав, либо ни одной
     * строки.
     *
     * Разновидность проверяется по изолятору, а не по полям формы:
     * чекбоксы пользователь не редактирует, но форму можно отправить
     * вручную, и тогда её содержимое — недоверенные данные.
     *
     * Количество ограничено количеством заказа: сверх него состав
     * заказывать некому, а «Готово 12 из 10» выглядело бы сбоем учёта.
     */
    @Transactional
    public KittingResult kit(Long orderId,
                             Collection<String> fullNumbers,
                             AppUser user) {

        ProductionOrder order = requireRepairOrder(orderId);
        TechProcessOperation node = kittingNode(order)
                .orElseThrow(() -> new IllegalStateException(
                        "У заказа нет комплектовочной операции бюро "
                                + "контроля — провести его не по чему"
                ));

        if (!canOperate(user, node.getOperation())) {
            throw new IllegalStateException(
                    "Комплектование проводит исполнитель, за которым закреплена "
                            + "комплектовочная операция бюро"
            );
        }

        Set<String> picked = cleanNumbers(fullNumbers);

        if (picked.isEmpty()) {
            throw new IllegalStateException(
                    "Отметьте ячейки, которые берёте в заказ"
            );
        }

        QuarantineCellKind required = order.getZnpKind().requiredCellKind();
        Map<String, QuarantineCellView> available = quarantineIndex();

        List<String> alreadyTaken = picked.stream()
                .filter(number -> itemRepository
                        .existsByProductionOrderIdAndFullNumber(orderId, number))
                .toList();

        if (!alreadyTaken.isEmpty()) {
            throw new IllegalStateException(
                    "Эти ячейки уже взяты в заказ «" + order.getNumber() + "»: "
                            + String.join(", ", alreadyTaken)
            );
        }

        /*
         * Разновидность проверяется раньше факта присутствия в изоляторе:
         * попытка взять подсборку в заказ на ячейки обязана получить
         * ответ «не тот тип», а не «такой ячейки нет» — последняя формулировка
         * противоречила бы тому, что человек видит на экране.
         */
        List<String> wrongKind = picked.stream()
                .filter(available::containsKey)
                .filter(number -> available.get(number).getKind() != required)
                .toList();

        if (!wrongKind.isEmpty()) {
            throw new IllegalStateException(
                    "Заказ «" + order.getNumber() + "» заявлен на "
                            + required.getDisplayName().toLowerCase()
                            + ". Не тот тип ячеек: " + String.join(", ", wrongKind)
            );
        }

        List<String> unknown = picked.stream()
                .filter(number -> !available.containsKey(number))
                .toList();

        if (!unknown.isEmpty()) {
            throw new IllegalStateException(
                    "В изоляторе брака этих ячеек нет: " + String.join(", ", unknown)
            );
        }

        /*
         * Изделие проверяется следом за родом: перечень изолятора уже
         * отобран по классификатору заказа, но форма приходит от
         * человека, и адрес можно отправить вручную. Подсборка другого
         * изделия (5005 — ПВБ2) в заказ на 5004 — ПВБ1 не входит, хотя
         * род у них общий: заказ закрывается изделием, и чужая позиция
         * не закрыла бы его, а маршрут заказа проходил бы по чужому
         * техпроцессу.
         *
         * Заказ без изделия (классификатор не выбран) проверки не
         * получает: отвергать нечем, а выдумывать изделие — значит
         * отказывать по признаку, которого в заказе нет.
         */
        Long orderClassifier = order.getClassifierId();

        if (orderClassifier != null) {
            List<String> wrongClassifier = picked.stream()
                    .filter(available::containsKey)
                    .filter(number ->
                            !orderClassifier.equals(
                                    available.get(number).getClassifierId()))
                    .toList();

            if (!wrongClassifier.isEmpty()) {
                throw new IllegalStateException(
                        "Заказ «" + order.getNumber() + "» заявлен на изделие "
                                + order.getClassifierLabel()
                                + ". Не то изделие: "
                                + String.join(", ", wrongClassifier)
                );
            }
        }

        long requiredCount = requiredCount(order);
        long done = itemRepository.findFullNumbersByOrderId(orderId).size();
        long room = Math.max(requiredCount - done, 0L);

        if (room == 0) {
            throw new IllegalStateException(
                    "Заказ «" + order.getNumber() + "» уже укомплектован: "
                            + done + " из " + requiredCount
            );
        }

        if (picked.size() > room) {
            throw new IllegalStateException(
                    "В заказ «" + order.getNumber() + "» осталось взять " + room
                            + ", а отмечено " + picked.size()
            );
        }

        LocalDateTime moment = LocalDateTime.now();
        Operation operation = node.getOperation();

        List<RepairKittingItem> items = new ArrayList<>();

        for (String number : picked) {
            items.add(newItem(order, available.get(number), user, moment));
        }

        itemRepository.saveAll(items);

        for (RepairKittingItem item : items) {
            performSerialOperation(item, order, operation, node, user, moment);
            /*
             * Строка плана комплектования закрывается на каждую взятую
             * ячейку: цеховой «получатель» блокируется по экземпляру
             * (номер основания + позиция + номер экземпляра + пара
             * маршрута), поэтому взятая ячейка обязана снять ровно одну
             * открытую строку предка — иначе следующая операция осталась
             * бы в «ждёт», даже когда весь состав набран.
             */
            completeScheduleRow(order, operation, user);
        }

        journalService.recordKittedToRepairZnp(items, order, user, moment);

        long totalDone = done + items.size();
        boolean completed = requiredCount > 0 && totalDone >= requiredCount;

        return new KittingResult(
                "Проведено " + items.size() + " яч. по заказу «"
                        + order.getNumber() + "»: "
                        + totalDone + " из " + requiredCount,
                items.size(),
                completed,
                completed ? kittingListUrl(node) : null
        );
    }

    /**
     * Проведение строки плана комплектования.
     *
     * Берётся ближайшая незакрытая строка этой операции заказа (тот же
     * приём, что у {@link SubassemblyKittingService}): порядок по
     * номеру экземпляра важен, — строки проводятся последовательно, и
     * закрытие всегда идёт по ближайшему незакрытому, иначе следующая
     * по маршруту операция цеха разблокировалась бы не для того изделия.
     *
     * Открытых строк может не оказаться вовсе (заказ взят в работу до
     * V56 либо техпроцесс пуст) — это норма, и тогда проводить нечего.
     */
    private void completeScheduleRow(ProductionOrder order,
                                     Operation operation,
                                     AppUser user) {

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
        row.setCompletedBy(user);
        scheduleRowRepository.save(row);
    }

    /**
     * Адрес списка комплектования того бюро, чья операция ведёт заказ.
     *
     * Редирект после полного укомплектования ведёт в перечень бюро —
     * именно там заказ остаётся видимым как «Скомплектовано»
     * (только просмотр). Подразделение берётся по операции маршрута, а
     * не по виду заказа: какое бюро комплектует, решает подразделение
     * операции (тот же признак, что у {@link #permittedOperationIds}).
     * Неизвестное подразделение ведёт в БВК — список по умолчанию.
     */
    private String kittingListUrl(TechProcessOperation node) {

        String unitCode = node.getOperation() == null
                || node.getOperation().getOrganizationUnit() == null
                ? null
                : node.getOperation().getOrganizationUnit().getCode();

        if (BTK_UNIT_CODE.equals(unitCode)) {
            return "/technical-control/repair-kitting";
        }

        return "/input-control/repair-kitting";
    }

    /**
     * Проведение ячейки по комплектовочной операции.
     *
     * Если у номера есть экземпляр в реестре, закрывается (или
     * создаётся) строка product_serial_operations в рамках этого заказа
     * — иначе факт работы не попал бы ни в матрицу изделия, ни в
     * загрузку исполнителя.
     *
     * Для номеров, в реестр не внесённых (партия прихода «всей
     * партией»), строки не будет: привязывать не к чему, а выдумывать
     * экземпляр — значит засорить реестр. Запись состава и события
     * журнала при этом остаётся, и прослеживаемость номера она даёт
     * полную.
     */
    private void performSerialOperation(RepairKittingItem item,
                                        ProductionOrder order,
                                        Operation operation,
                                        TechProcessOperation node,
                                        AppUser user,
                                        LocalDateTime moment) {

        Optional<ProductSerial> found = findCell(item.getFullNumber());

        if (found.isEmpty()) {
            return;
        }

        ProductSerial entity = found.get();

        /*
         * Матрица комплектования (V20): строка SerialProductionOrder
         * отвечает на вопрос «чем укомплектован экземпляр», и именно её
         * читает цех в «Получении комплектации» (resolveAttachedCells →
         * findAttachedCellsByOrderIds). Без строки здесь цех видел бы
         * «—» вместо номера ячейки, даже когда комплектация проведена.
         *
         * Порядок внесения задаёт сопоставление ячейки экземпляру плана
         * (i-я по внесению ячейка — экземпляру с номером i), поэтому
         * строка создаётся на каждую взятую ячейку, а не на заказ.
         *
         * Уникальный ключ uk_spo_serial_order (серия + заказ) отклонил
         * бы повтор, поэтому существующая связь пропускается: повторный
         * клик не должен ломать уже внесённую ячейку.
         */
        attachToOrderMatrix(entity, order, operation, user);

        Optional<ProductSerialOperation> last =
                serialOperationRepository
                        .findFirstBySerialIdAndOperationIdOrderByAttemptDesc(
                                entity.getId(), operation.getId());

        /* Незакрытая попытка этой же операции — проводится она, а не новая. */
        Optional<ProductSerialOperation> open = last
                .filter(existing -> existing.getActualDate() == null
                        && !existing.isReworked());

        if (open.isPresent()) {

            ProductSerialOperation row = open.get();

            row.setProductionOrder(order);
            row.setStatus(ProductSerialOperationStatus.DONE);
            /*
             * План не подставляется при закрытии открытой попытки:
             * факт (текущая дата) будет раньше срока заказа, и
             * CHECK chk_pso_dates нарушится. Если план уже стоял —
             * оставляем; если не стоял — оставляем null, как в
             * SubassemblyKittingService.
             */
            row.setActualDate(moment.toLocalDate());
            row.setExecutor(user);
            row.setPerformedBy(user);

            serialOperationRepository.save(row);

            return;
        }

        ProductSerialOperation row = new ProductSerialOperation();

        row.setSerial(entity);
        row.setOperation(operation);
        row.setProductionOrder(order);
        /* Проведение после переделки — следующая попытка, прежняя остаётся в истории. */
        row.setAttempt(last.map(existing -> existing.getAttempt() + 1).orElse(1));
        row.setStatus(ProductSerialOperationStatus.DONE);
        row.setActualDate(moment.toLocalDate());
        row.setExecutor(user);
        row.setPerformedBy(user);
        row.setSeq(node.getPosition() == null ? 1 : node.getPosition());
        row.setNote("Комплектование ремонтного ЗНП из изолятора брака");

        serialOperationRepository.save(row);
    }

    /**
     * Внесение ячейки в матрицу комплектования заказа (V20).
     *
     * Строка {@link SerialProductionOrder} — единственный источник, из
     * которого цех подписывает работу номером ячейки: у группового и
     * ремонтного ЗНП серийника выпуска нет, и без этой связи
     * «Получение комплектации» показывало бы прочерк там, где номер
     * уже взят в заказ.
     *
     * Существующая связь пропускается: уникальный ключ
     * {@code uk_spo_serial_order} отклонил бы вторую строку той же
     * пары, а пользователю нужно сообщение о деле, а не о ключи базы.
     */
    private void attachToOrderMatrix(ProductSerial serial,
                                     ProductionOrder order,
                                     Operation operation,
                                     AppUser user) {

        if (serial.getId() == null || order.getId() == null) {
            return;
        }

        if (serialProductionOrderRepository
                .existsBySerial_IdAndProductionOrder_Id(serial.getId(), order.getId())) {
            return;
        }

        SerialProductionOrder link = new SerialProductionOrder();

        link.setSerial(serial);
        link.setProductionOrder(order);
        link.setOperation(operation);
        link.setCreatedBy(user);
        link.setNote("Комплектование ремонтного ЗНП из изолятора брака");

        serialProductionOrderRepository.save(link);
    }

    /**
     * Ячейка реестра по полному номеру: код классификатора + серия.
     *
     * Номер разбирается по тому же шаблону, по которому собирается
     * (см. ProductSerial#getFullNumber()); всё, что ему не
     * соответствует, в реестре не ищется — это и есть ответ «номера в
     * реестре нет», а не ошибка.
     */
    private Optional<ProductSerial> findCell(String fullNumber) {

        if (fullNumber == null
                || fullNumber.length() != FULL_NUMBER_LENGTH
                || !fullNumber.chars().allMatch(Character::isDigit)) {
            return Optional.empty();
        }

        try {
            return productSerialRepository.findCellByClassifierCodeAndSerial(
                    Integer.parseInt(fullNumber.substring(0, CODE_LENGTH)),
                    Integer.parseInt(fullNumber.substring(CODE_LENGTH))
            );
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
    }

    /**
     * Строка состава заказа.
     *
     * Классификатор читается, а не ставится ссылкой: он нужен и для
     * отчёта, и для подписи строки после сохранения, а open-in-view
     * выключен — ссылка на загруженный объект дешевле ссылки, которая
     * при чтении ушла бы в ленивую загрузку вне сессии.
     */
    private RepairKittingItem newItem(ProductionOrder order,
                                      QuarantineCellView cell,
                                      AppUser user,
                                      LocalDateTime moment) {

        RepairKittingItem item = new RepairKittingItem();

        item.setProductionOrder(order);
        item.setFullNumber(cell.getFullNumber());
        item.setCellKind(cell.getKind());
        item.setKittedBy(user);
        item.setKittedAt(moment);

        Long classifierId = cell.getClassifierId();

        if (classifierId != null) {
            item.setProductClassifier(
                    productClassifierRepository.findById(classifierId).orElse(null));
        }

        return item;
    }

    /* ============================================================
     * ПРАВО ПРОВОДИТЬ
     * ============================================================ */

    /**
     * Проводит ли этот пользователь работу по этой операции.
     *
     * Исполнитель бюро — только по операциям, закреплённым за ним в
     * карточке сотрудника (привязка «Операция», V23): тот же признак,
     * что у проведения экземпляра работы в цехе. Руководителю бюро и
     * руководителю службы доступны все операции их подразделения —
     * заказ в ремонте закрывает тот, кто вёл его в тот день, и
     * закреплять комплектование на одного человека некому.
     *
     * Отсутствующая операция означает «маршрут заказа не настроен»:
     * такое заказ провести нельзя никому.
     */
    @Transactional(readOnly = true)
    public boolean canOperate(AppUser user, Operation operation) {

        return operation != null
                && permittedOperationIds(user).contains(operation.getId());
    }

    /**
     * Идентификаторы операций, которые этот пользователь вправе
     * проводить.
     *
     * Отдельный метод, а не проверка «на месте»: меню перебирает
     * десятки заказов, и запрос за привязками на каждый заказ дал бы
     * столько же обращений к базе, сколько заказов в списке. Здесь
     * набор читается один раз на заход на страницу.
     *
     * Пустое значение для ролей вне бюро — «некому»: список меню у
     * такой роли пуст, и это не отказ, а отсутствие предмета работы.
     */
    private Set<Long> permittedOperationIds(AppUser user) {

        if (user == null || user.getRole() == null) {
            return Set.of();
        }

        return switch (user.getRole()) {
            /*
             * Исполнителю бюро доступны все операции его подразделения, а
             * не только закреплённые в карточке: планировщик закрепляет
             * комплектовочную операцию за бюро целиком, внутри бюро
             * распределения нет и работу берёт тот контролёр, который
             * свободен. Личная привязка при этом продолжает учитываться —
             * она расширяет право, когда операцию записали на человека, а
             * подразделение в карточке поменяли позже.
             *
             * Правило одинаково для обоих бюро: граница проходит по
             * подразделению учётной записи, поэтому контролёр БВК не
             * получает операций БТК, и наоборот.
             */
            case BVK_EXECUTOR, BTK_EXECUTOR -> union(
                    unitOperationIds(user),
                    boundOperationIds(user)
            );
            case BVK_MASTER, BTK_MASTER, SK_GENERAL -> unitOperationIds(user);
            default -> Set.of();
        };
    }

    /** Объединение прав без потери порядка: подразделение, затем личное. */
    private static Set<Long> union(Set<Long> primary, Set<Long> extra) {

        Set<Long> ids = new LinkedHashSet<>(primary);
        ids.addAll(extra);

        return ids;
    }

    /** Операции, закреплённые за сотрудником в его карточке. */
    private Set<Long> boundOperationIds(AppUser user) {

        if (user.getId() == null) {
            return Set.of();
        }

        Set<Long> ids = new LinkedHashSet<>();

        for (WorkshopStaffBinding binding
                : bindingRepository.findAllByEmployeeIds(List.of(user.getId()))) {

            if (binding.getBindingType() == StaffBindingType.OPERATION
                    && binding.getOperation() != null) {
                ids.add(binding.getOperation().getId());
            }
        }

        return ids;
    }

    /** Операции подразделения (для руководителя службы — всех её подразделений). */
    private Set<Long> unitOperationIds(AppUser user) {

        List<Long> scope = staffService.unitScope(user);

        if (scope.isEmpty()) {
            return Set.of();
        }

        Set<Long> ids = new LinkedHashSet<>();

        for (Operation operation
                : operationRepository.findAllByOrganizationUnitIds(scope)) {
            ids.add(operation.getId());
        }

        return ids;
    }

    /* ============================================================
     * ВСПОМОГАТЕЛЬНОЕ
     * ============================================================ */

    /**
     * Ремонтные заказы в работе по обоим бюро контроля.
     *
     * Подбор держится тех же типов и подразделений, что и поиск узла
     * маршрута ({@link #kittingNodesByProcess}) и отбор раздела у
     * {@link RepairKittingService}: список, счётчик и форма обязаны
     * сходиться, иначе заказ попал бы в меню без возможности провести
     * его. Кому из бюро заказ принадлежит, решает уже
     * {@link #permittedOperationIds} — исполнитель видит только
     * операции своего подразделения.
     */
    private List<ProductionOrder> repairOrders() {
        return productionOrderRepository.findRepairKittingOrders(
                ProductionOrderStatus.IN_WORK,
                ZnpKind.repairKinds(),
                KITTING_TYPE_CODES,
                KITTING_UNIT_CODES
        );
    }

    /** Заказ по идентификатору — только активный ремонтный и в работе. */
    private ProductionOrder requireRepairOrder(Long orderId) {

        if (orderId == null) {
            throw new IllegalArgumentException("Заказ не выбран");
        }

        ProductionOrder order = productionOrderRepository
                .findDetailedById(orderId)
                .orElseThrow(() -> new IllegalStateException(
                        "Заказ на производство не найден"
                ));

        if (!order.isActive()
                || order.getZnpKind() == null
                || !order.getZnpKind().isRepairKind()) {
            throw new IllegalStateException(
                    "Комплектование проводится только по заказу на ремонт "
                            + "ячеек или подсборок"
            );
        }

        if (order.getStatus() != ProductionOrderStatus.IN_WORK) {
            throw new IllegalStateException(
                    "Заказ «" + order.getNumber() + "» не в работе — "
                            + order.getStatusDisplayName()
            );
        }

        return order;
    }

    /**
     * Комплектовочные узлы маршрутов для перечня заказов — один запрос.
     *
     * Ключ — идентификатор техпроцесса, значение — первый узел маршрута
     * (запрос отсортирован по позиции): если в маршруте несколько
     * комплектовочных операций бюро, ведёт та, что стоит раньше, — тот
     * же порядок, что при плане операций изделия.
     *
     * Поиск держится тех же типов и подразделений, что и подбор заказов
     * ({@link #repairOrders}), — список и форма обязаны сходиться: если
     * заказ попал в меню, узел маршрута обязан найтись по тому же
     * признаку, иначе провести его было бы нечем.
     */
    private Map<Long, TechProcessOperation> kittingNodesByProcess(
            List<ProductionOrder> orders) {

        List<Long> processIds = orders.stream()
                .map(RepairKittingOperationService::processId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        if (processIds.isEmpty()) {
            return Map.of();
        }

        Map<Long, TechProcessOperation> result = new LinkedHashMap<>();

        for (TechProcessOperation node
                : techProcessOperationRepository
                        .findKittingNodesForProcesses(
                                processIds,
                                KITTING_TYPE_CODES,
                                KITTING_UNIT_CODES)) {

            if (node.getTechProcess() == null
                    || node.getTechProcess().getId() == null
                    || node.getOperation() == null) {
                continue;
            }

            result.putIfAbsent(node.getTechProcess().getId(), node);
        }

        return result;
    }

    private Optional<TechProcessOperation> kittingNode(ProductionOrder order) {

        Long processId = processId(order);

        if (processId == null) {
            return Optional.empty();
        }

        return Optional.ofNullable(
                kittingNodesByProcess(List.of(order)).get(processId));
    }

    /** Уже взятые ячейки по всем заказам сразу — счётчик «Готово X из Y». */
    private Map<Long, Long> doneCountByOrder(List<ProductionOrder> orders) {

        List<Long> ids = orders.stream()
                .map(ProductionOrder::getId)
                .filter(Objects::nonNull)
                .toList();

        Map<Long, Long> counts = new LinkedHashMap<>();

        if (ids.isEmpty()) {
            return counts;
        }

        for (RepairKittingItem item : itemRepository.findByOrderIds(ids)) {

            if (item.getProductionOrder() == null) {
                continue;
            }

            counts.merge(item.getProductionOrder().getId(), 1L, Long::sum);
        }

        return counts;
    }

    /**
     * Индекс изолятора: номер → строка.
     *
     * Строки берутся без фильтра по разновидности: проверка обязана
     * отличить «ячейки в изоляторе нет» от «есть, но не того типа» —
     * отказ «нет в изоляторе» при попытке взять подсборку в заказ на
     * ячейки был бы неправдой и отправил бы человека искать номер,
     * который у него перед глазами.
     */
    private Map<String, QuarantineCellView> quarantineIndex() {

        Map<String, QuarantineCellView> index = new LinkedHashMap<>();

        for (QuarantineCellView cell : inputControlService
                .findQuarantineCells(QuarantineFilter.empty())) {

            index.putIfAbsent(cell.getFullNumber(), cell);
        }

        return index;
    }

    private static Long processId(ProductionOrder order) {
        return order.getTechProcess() == null
                ? null
                : order.getTechProcess().getId();
    }

    private static String operationName(Operation operation) {

        if (operation.getOperationName() == null
                || operation.getOperationName().getName() == null
                || operation.getOperationName().getName().isBlank()) {
            return "Комплектование";
        }

        return operation.getOperationName().getName();
    }

    /** Количество заказа в штуках: дробного количества у группового заказа нет. */
    private static long requiredCount(ProductionOrder order) {

        BigDecimal quantity = order.getQuantity();

        return quantity == null
                ? 0L
                : quantity.setScale(0, RoundingMode.DOWN).longValue();
    }

    /**
     * Номера из формы: без пустых строк и повторов.
     *
     * LinkedHashSet, а не Set: порядок обхода определяет порядок строк
     * состава и порядок событий журнала, и он должен соответствовать
     * порядку, в котором пользователь отмечал ячейки.
     */
    private static Set<String> cleanNumbers(Collection<String> numbers) {

        Set<String> result = new LinkedHashSet<>();

        if (numbers == null) {
            return result;
        }

        for (String number : numbers) {

            if (number == null) {
                continue;
            }

            String trimmed = number.trim();

            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }

        return result;
    }

    private static RepairKittingZnpView toView(ProductionOrder order, long done) {

        /*
         * Планом считается срок исполнения заказа, а дата заведения
         * подставляется только если срока нет: исполнитель планирует
         * день работы по сроку, и дата создания документа ему о дне
         * работы не говорит.
         */
        LocalDate plan = order.getDueDate() == null
                ? order.getOrderDate()
                : order.getDueDate();

        return new RepairKittingZnpView(
                order.getId(),
                order.getNumber(),
                plan == null ? "—" : plan.format(DATE_FORMATTER),
                order.getClassifierLabel(),
                order.getClassifierId(),
                order.getFormattedQuantity(),
                techProcessLabel(order),
                order.getZnpKind(),
                done,
                requiredCount(order)
        );
    }

    /** Маршрут подписывается кодом: карточка заказа покажет его наименование. */
    private static String techProcessLabel(ProductionOrder order) {
        String code = order.getTechProcessCode();

        return code == null || code.isBlank() ? "—" : code;
    }

    /**
     * Итог проведения: подпись для страницы и число проведённых ячеек.
     *
     * Число возвращается отдельно — форма после возврата обязана
     * показать обновлённый состав, а не тот же список с надписью.
     *
     * Признак «укомплектовано полностью» и адрес списка бюро нужны
     * контроллеру: набранный заказ больше нечего проводить, и человек
     * обязан вернуться в перечень, где заказ остался видимым как
     * «Скомплектовано». Пустой адрес — «вести некуда», частичная
     * проводка остаётся на форме.
     */
    public record KittingResult(String message,
                                long kittedCount,
                                boolean completed,
                                String listUrl) {
    }
}