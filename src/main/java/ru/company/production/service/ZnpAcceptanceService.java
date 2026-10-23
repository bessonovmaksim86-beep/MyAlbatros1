package ru.company.production.service;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.company.production.dto.InputControlResult;
import ru.company.production.dto.ZnpAcceptanceForm;
import ru.company.production.dto.ZnpAcceptanceRow;
import ru.company.production.entity.AppUser;
import ru.company.production.entity.CustomerOrder;
import ru.company.production.entity.Operation;
import ru.company.production.entity.OrderScheduleRow;
import ru.company.production.entity.ProductSerial;
import ru.company.production.entity.ProductSerialOperation;
import ru.company.production.entity.ProductSerialOperationStatus;
import ru.company.production.entity.ProductionOrder;
import ru.company.production.entity.ProductionOrderStatus;
import ru.company.production.entity.SerialProductionOrder;
import ru.company.production.entity.TechProcess;
import ru.company.production.entity.TechProcessOperation;
import ru.company.production.entity.ZnpKind;
import ru.company.production.repository.OrderScheduleRowRepository;
import ru.company.production.repository.ProductSerialOperationRepository;
import ru.company.production.repository.ProductSerialRepository;
import ru.company.production.repository.ProductionOrderRepository;
import ru.company.production.repository.SerialProductionOrderRepository;
import ru.company.production.repository.TechProcessOperationRepository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Приёмка на склад ячеек, вошедших в заказ-комплектующий (ЗНП).
 *
 * Раздел стоит после «Приёмки с БВК»: туда ячейка приходит подсборкой
 * и проходит входной контроль, а сюда попадает уже ячейкой, собранной
 * в состав конкретного ЗНП. Склад принимает не партию оприходования, а
 * заказ-комплектующий: единица приёмки — все непринятые ячейки состава
 * одного ЗНП разом.
 *
 * Приёмка делает ровно два дела, и оба — события склада, а не
 * производства:
 *
 *   * отмечает принятые ячейки (product_serials.accepted_* — тот же
 *       механизм, что у приёмки после БВК, см. {@link BvkAcceptanceService});
 *   * закрывает комплектовочную операцию этих ячеек в рамках заказа
 *       (product_serial_operations.status → DONE с фактом), потому что
 *       для склада приёмка и есть подтверждение: ячейку укомплектовали
 *       и она на месте.
 *
 * Заказ выбирается по признаку комплектующего: серийный ЗНП (родитель
 * задан) либо групповой вид из {@link ZnpKind#groupKinds()} — тот, что
 * диспетчер заводит изделием и количеством с выбором техпроцесса.
 * Ячейка привязана к такому заказу операцией комплектования в начале
 * маршрута, то есть строкой матрицы {@link SerialProductionOrder}.
 * Ремонтные виды в отбор не входят: их состав возвращён из изолятора,
 * и приёмку этих ячеек проводит комплектование ремонтных ЗНП.
 *
 * ПРИЁМКА ОТКРЫВАЕТСЯ ТОЛЬКО ПО СВОЕЙ ОЧЕРЕДИ В МАРШРУТЕ.
 *
 * Ячейку вносят в заказ на операции комплектования, то есть в начале
 * маршрута, а склад принимает её в конце — по операции приёмки. Пока
 * предшествующие операции техпроцесса не закрыты, принимать нечего:
 * заказ в списке остаётся (кладовщик иначе не понял бы, куда делся
 * ЗНП), но кнопки проведения нет и счётчик меню такой заказ не
 * считает. Готовность операции меряется тем же правилом графика, что и
 * в распределении работ ({@link WorkshopDistributionService
 * #blockerLabelsOf}), иначе два экрана разошлись бы во мнении, можно ли
 * проводить работу.
 *
 * Принимается ровно то, что дошло до склада: ячейка, отвалившаяся по
 * ходу техпроцесса, в состав заказа не внесена, а значит, и в приёмку
 * не попадёт — количество приёмки всегда не больше заказанного.
 *
 * Приёмку проводят один раз: повторное проведение отклоняется, а не
 * перетирает прежнюю отметку — иначе журнал перемещений заполнился бы
 * дублями ACCEPTED на одну ячейку.
 */
@Service
@RequiredArgsConstructor
public class ZnpAcceptanceService {

    /** Виды, ведущие приёмку ячеек: набор считается от {@link ZnpKind}. */
    private static final List<ZnpKind> ACCEPTED_KINDS = ZnpKind.groupKinds();

    /** Типы операций, которыми склад принимает подсборки и ячейки. */
    private static final List<String> ACCEPTANCE_TYPE_CODES = List.of(
            "RECEIVING",
            "KITTING_SUBASSEMBLY"
    );

    /** Подразделение-владелец операций приёмки (миграция V2). */
    private static final String WAREHOUSE_UNIT_CODE =
            "SEMI_FINISHED_PRODUCT_WAREHOUSE";

    /** Лимит номеров в сообщении. */
    private static final int MESSAGE_NUMBER_LIMIT = 10;

    private final ProductionOrderRepository productionOrderRepository;
    private final SerialProductionOrderRepository linkRepository;
    private final ProductSerialRepository serialRepository;
    private final ProductSerialOperationRepository serialOperationRepository;
    private final OrderScheduleRowRepository scheduleRowRepository;
    private final TechProcessOperationRepository techProcessOpRepository;
    private final WorkshopDistributionService distributionService;
    private final SerialMovementJournalService journal;

    /**
     * Заказы-комплектующие с непринятыми ячейками — список раздела.
     *
     * Заказ из списка не убирают: кладовщик обязан видеть, что ЗНП ждёт
     * своей очереди, иначе искал бы пропавший заказ вслепую. Строка
     * несёт признак доступности приёмки — по нему шаблон прячет кнопку.
     */
    @Transactional(readOnly = true)
    public List<ZnpAcceptanceRow> findPendingOrders() {

        List<ProductionOrder> orders =
                linkRepository.findPendingCellZnp(ACCEPTED_KINDS);

        Map<Long, Boolean> available = acceptanceAvailability(orders);

        return orders.stream()
                .map(order -> new ZnpAcceptanceRow(
                        order,
                        Boolean.TRUE.equals(available.get(order.getId()))
                ))
                .toList();
    }

    /**
     * Заказов, ожидающих приёмки ячеек: счётчик пункта меню.
     *
     * Считает только заказы с открытой приёмкой: цифра меню обязана
     * равняться числу кнопок «Принять на склад» в списке, иначе
     * кладовщик шёл бы по счётчику на экран, где провести нечего.
     */
    @Transactional(readOnly = true)
    public long countPending() {
        return findPendingOrders().stream()
                .filter(ZnpAcceptanceRow::acceptanceAvailable)
                .count();
    }

    /**
     * Данные экрана приёмки: заказ, его непринятые ячейки и подпись
     * комплектовочной операции.
     *
     * Заказ читается детальным запросом (родитель, изделие, операция —
     * связи ленивые), непринятые ячейки — отдельным запросом с fetch
     * классификатора, чтобы строка списка печатала полный номер.
     *
     * Признак доступности передаётся и здесь: экран открывают и по
     * прямой ссылке, и по счётчику, и без него кладовщик увидел бы
     * кнопку там, где приёмка ещё не открылась.
     */
    @Transactional(readOnly = true)
    public ZnpAcceptanceView loadView(Long orderId) {

        ProductionOrder order = requireComponentZnp(orderId);

        List<SerialProductionOrder> cells =
                linkRepository.findPendingCellsByOrderId(orderId);

        return new ZnpAcceptanceView(
                order,
                cells,
                operationName(order, cells),
                techProcessName(order),
                isAcceptanceOpen(order, contextOf(List.of(order)))
        );
    }

    /*
     * ============================================================
     * Открыта ли приёмка: очередь операции в плане производства
     * ============================================================
     */

    /**
     * Снимок, по которому отвечают на вопрос «открыта ли приёмка».
     *
     * Всё читается пакетами на список заказов: раздел и его счётчик
     * кладовщик открывает за смену много раз, а проверка на каждую
     * строку дала бы по два запроса на заказ.
     *
     * @param routeOperations операция приёмки техпроцесса по его id —
     *                        одна читка узлов маршрутов на список
     * @param rows            строки плана всех заказов списка
     * @param blockers        заблокированные строки плана по подписям
     *                        маршрута
     */
    private record AcceptanceContext(
            Map<Long, Operation> routeOperations,
            List<OrderScheduleRow> rows,
            Map<Long, String> blockers
    ) {
    }

    private AcceptanceContext contextOf(List<ProductionOrder> orders) {

        if (orders.isEmpty()) {
            return new AcceptanceContext(Map.of(), List.of(), Map.of());
        }

        List<OrderScheduleRow> rows = scheduleRowsOf(orders);

        return new AcceptanceContext(
                routeOperationsByProcess(orders),
                rows,
                distributionService.blockerLabelsOf(rows)
        );
    }

    /**
     * Открыта ли приёмка каждого заказа списка.
     *
     * Ключ — id заказа; заказа без ответа не остаётся, иначе счётчик
     * молчал бы о строке, которую список показал.
     */
    private Map<Long, Boolean> acceptanceAvailability(
            List<ProductionOrder> orders) {

        if (orders.isEmpty()) {
            return Map.of();
        }

        AcceptanceContext context = contextOf(orders);

        Map<Long, Boolean> result = new LinkedHashMap<>();

        for (ProductionOrder order : orders) {
            result.put(order.getId(), isAcceptanceOpen(order, context));
        }

        return result;
    }

    /**
     * Открыта ли приёмка заказа по готовому снимку плана.
     *
     * Операции приёмки в маршруте нет — провести приёмку не по чем:
     * диспетчер обязан завести её в техпроцессе, и без неё склад не
     * имеет права закрывать состав заказа.
     *
     * Строки плана нет — заказ не поставлен в планирование по этой
     * операции, а склад не может знать, дошёл ли маршрут до приёмки, и
     * самовольно её не открывает.
     *
     * Приёмка закрыта по плану, но непринятые ячейки остались
     * (например, кладовщик внёс состав позже), — принять их всё равно
     * нужно, иначе номер навсегда повиснет непринятым.
     *
     * Из непроведённых строк хоть одна не заблокирована
     * предшественниками — операция на очереди, склад принимает;
     * заблокированы все — маршрут ещё не дошёл, приёмка ждёт.
     */
    private boolean isAcceptanceOpen(ProductionOrder order,
                                     AcceptanceContext context) {

        Operation acceptance = acceptanceOperationOf(order, context);

        if (acceptance == null || acceptance.getId() == null) {
            return false;
        }

        List<OrderScheduleRow> planned =
                acceptanceRowsOf(order, acceptance, context.rows());

        if (planned.isEmpty()) {
            return false;
        }

        boolean anyOpen = planned.stream()
                .anyMatch(row -> row.getCompletedAt() == null);

        if (!anyOpen) {
            return true;
        }

        return planned.stream()
                .filter(row -> row.getCompletedAt() == null)
                .anyMatch(row -> !context.blockers().containsKey(row.getId()));
    }

    /**
     * Строки плана операции приёмки данного заказа.
     *
     * Оснований у строки графика два (V56): у самостоятельного и
     * группового ЗНП — сам заказ, у вложенного в выпуск — заказ
     * покупателя, чьим маршрутом работу и планируют. Ветвь основания
     * задаёт признак, по которому строка относится к заказу, поэтому
     * сравниваются и признак, и номер основания: без признака
     * совпавшие номера двух реестров приписали бы чужой план этому
     * заказу (см. baseKind в {@code OpenInstanceRowView}).
     */
    private static List<OrderScheduleRow> acceptanceRowsOf(
            ProductionOrder order,
            Operation acceptance,
            List<OrderScheduleRow> rows) {

        Long productionOrderId = order.getId();
        Long customerOrderId = customerOrderIdOf(order);

        return rows.stream()
                .filter(row -> row.getOperation() != null
                        && acceptance.getId().equals(row.getOperation().getId()))
                .filter(row -> matchesBase(row, productionOrderId, customerOrderId))
                .toList();
    }

    /** Строка принадлежит основанию данного заказа. */
    private static boolean matchesBase(OrderScheduleRow row,
                                       Long productionOrderId,
                                       Long customerOrderId) {

        if (row.isProductionOrderBased()) {
            return row.getProductionOrder() != null
                    && productionOrderId != null
                    && productionOrderId.equals(row.getProductionOrder().getId());
        }

        return row.getCustomerOrder() != null
                && customerOrderId != null
                && customerOrderId.equals(row.getCustomerOrder().getId());
    }

    /**
     * Операции приёмки техпроцессов списка заказов — один запрос.
     *
     * Приёмку опознают по типу и подразделению, а не по позиции:
     * маршруты разных изделий ведут приёмку на разных шагах.
     */
    private Map<Long, Operation> routeOperationsByProcess(
            List<ProductionOrder> orders) {

        List<Long> processIds = orders.stream()
                .map(ProductionOrder::getTechProcess)
                .filter(Objects::nonNull)
                .map(TechProcess::getId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        if (processIds.isEmpty()) {
            return Map.of();
        }

        Map<Long, Operation> result = new LinkedHashMap<>();

        for (TechProcessOperation node
                : techProcessOpRepository.findUnitNodesForProcesses(
                        processIds,
                        ACCEPTANCE_TYPE_CODES,
                        WAREHOUSE_UNIT_CODE
                )) {

            if (node.getTechProcess() == null
                    || node.getTechProcess().getId() == null) {
                continue;
            }

            result.putIfAbsent(
                    node.getTechProcess().getId(),
                    node.getOperation()
            );
        }

        return result;
    }

    /**
     * Операция приёмки, по которой склад закрывает состав заказа.
     *
     * У заказа, вложенного в выпуск, она задана на самом заказе
     * (маршрут такому заказу не назначают, его работу планирует выпуск);
     * у самостоятельного и группового — живёт внутри техпроцесса и
     * берётся из пакетного снимка маршрутов.
     */
    private static Operation acceptanceOperationOf(
            ProductionOrder order,
            AcceptanceContext context) {

        Operation orderOperation = order.getProductionOperation();

        if (orderOperation != null) {
            return orderOperation;
        }

        TechProcess process = order.getTechProcess();

        if (process == null || process.getId() == null) {
            return null;
        }

        return context.routeOperations().get(process.getId());
    }

    /**
     * Строки графика заказов списка — обе ветви основания одним махом.
     *
     * Пустую ветвь в запрос не отдают: `in` с пустой коллекцией
     * Hibernate отвергает, а в списке раздела одна из ветвей пуста
     * почти всегда.
     */
    private List<OrderScheduleRow> scheduleRowsOf(
            List<ProductionOrder> orders) {

        List<Long> productionOrderIds = orders.stream()
                .map(ProductionOrder::getId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        List<Long> customerOrderIds = orders.stream()
                .map(ZnpAcceptanceService::customerOrderIdOf)
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        List<OrderScheduleRow> rows = new ArrayList<>();

        if (!productionOrderIds.isEmpty()) {
            rows.addAll(scheduleRowRepository
                    .findRouteRowEntitiesOfProductionOrders(productionOrderIds));
        }

        if (!customerOrderIds.isEmpty()) {
            rows.addAll(scheduleRowRepository
                    .findRouteRowEntitiesOfOrders(customerOrderIds));
        }

        return rows;
    }

    /** Заказ покупателя — основание плана вложенного в выпуск ЗНП. */
    private static Long customerOrderIdOf(ProductionOrder order) {

        CustomerOrder customerOrder = order.getCustomerOrder();

        return customerOrder == null ? null : customerOrder.getId();
    }

    /**
     * Проводит приёмку: непринятые ячейки состава заказа отмечают
     * принятыми на склад, их комплектовочная операция закрывается фактом.
     *
     * Всё или ничего: принимают все непринятые ячейки заказа разом, и
     * если принять нечего (заказ уже принят целиком или ячеек нет),
     * сервис отказывает человеческим сообщением, а не проводит пустую
     * приёмку.
     *
     * Отметка «отметить все» берёт состав заказа целиком; без неё
     * принимают ровно те номера, что внёс кладовщик, — и сверяют их с
     * составом, чтобы чужой номер не лёг на склад.
     */
    @Transactional
    public InputControlResult accept(
            Long orderId,
            ZnpAcceptanceForm form,
            AppUser receiver
    ) {

        ProductionOrder order = requireComponentZnp(orderId);

        List<SerialProductionOrder> pending =
                linkRepository.findPendingCellsByOrderId(orderId);

        if (pending.isEmpty()) {
            return InputControlResult.rejected(
                    "В заказе «" + order.getNumber()
                            + "» нет непринятых ячеек — приёмку уже провели "
                            + "или в его составе ещё нет ячеек"
            );
        }

        /*
         * Очередь операции проверяется и здесь, а не только в шаблоне:
         * экран открывают и по прямой ссылке, и по устаревшему
         * счётчику, и приёмка раньше предшествующих операций разметала
         * бы маршрут в обратном порядке.
         */
        AcceptanceContext context = contextOf(List.of(order));

        if (!isAcceptanceOpen(order, context)) {
            return InputControlResult.rejected(
                    "Приёмка заказа «" + order.getNumber()
                            + "» ещё не открылась: предшествующие операции "
                            + "техпроцесса не проведены. Склад примет состав, "
                            + "когда очередь дойдёт до приёмки на склад"
            );
        }

        /*
         * Разбираем номера из формы: если отмечено «все» — берём все
         * pending, иначе парсим cellNumbersText и сверяем с ожидаемым.
         */
        List<String> expectedNumbers = pending.stream()
                .map(link -> link.getSerial().getFullNumber())
                .toList();

        /*
         * Разбор перечня: отметка «отметить все» берёт состав заказа
         * целиком — сервер подставляет сам перечень, а не то, что
         * набрал кладовщик. Без отметки читают поле ввода; пустое поле
         * — отказ, а не молчаливая приёмка всего состава: кладовщик
         * обязан либо внести номера, либо поставить отметку.
         */
        List<String> chosen = form.isAllCells()
                ? expectedNumbers
                : parseNumbers(form.getCellNumbersText(), expectedNumberLength(expectedNumbers));

        if (chosen.isEmpty()) {
            return InputControlResult.rejected(
                    "Внесите номера принимаемых ячеек или отметьте "
                            + "«отметить все номера ячеек»"
            );
        }

        /*
         * Проверяем, что все введённые номера есть в составе заказа.
         */
        Set<String> expectedSet = Set.copyOf(expectedNumbers);
        List<String> unknown = chosen.stream()
                .filter(n -> !expectedSet.contains(n))
                .toList();
        if (!unknown.isEmpty()) {
            return InputControlResult.rejected(
                    "Номера «" + join(unknown) + "» не входят в состав "
                            + "заказа «" + order.getNumber() + "»"
            );
        }

        LocalDateTime moment = LocalDateTime.now();

        List<ProductSerial> serials = pending.stream()
                .map(SerialProductionOrder::getSerial)
                .filter(s -> chosen.contains(s.getFullNumber()))
                .toList();

        if (serials.isEmpty()) {
            return InputControlResult.rejected(
                    "Ни одна из внесённых ячеек не найдена в составе заказа — "
                            + "проверьте номера"
            );
        }

        List<Long> ids = serials.stream()
                .map(ProductSerial::getId)
                .toList();

        serialRepository.markAcceptedByIds(ids, moment, receiver);

        /*
         * Приёмка по заказу-комплектующему переводит позицию в готовую
         * ячейку (V75): принятое после БВК изделие лежит на складе
         * подсборкой, а состав заказа принимается уже ячейкой. Без
         * второй отметки реестр наличия показал бы её подсборкой, и её
         * нельзя было бы взять в комплектование.
         */
        serialRepository.markCellReadyByIds(ids, moment, receiver);
        journal.recordAccepted(serials, receiver, moment);
        journal.recordCellReady(serials, receiver, moment);
        closeKittingOperations(orderId, Set.copyOf(ids), receiver, moment);
        completeScheduleRows(
                order,
                acceptanceOperationOf(order, context),
                ids.size(),
                receiver,
                moment
        );

        /*
         * Групповой ЗНП приёмкой на склад завершается: его маршрут
         * ведёт к этой операции, и после неё заказывать больше нечего.
         *
         * Серийный ЗНП здесь не закрывают: он входит в выпуск, и его
         * жизнь кончается не складом, а сдачей датчика — заказ закроет
         * FinishedProductClosureService, когда номер пройдёт СГП.
         */
        if (order.isGroupZnp()) {
            order.setStatus(ProductionOrderStatus.DONE);
            productionOrderRepository.save(order);
        }

        return InputControlResult.accepted(
                "Принято на склад: " + ids.size() + " яч. по заказу «"
                        + order.getNumber() + "»"
        );
    }

    /**
     * Проводит строки плана приёмки по числу принятых ячеек.
     *
     * Цех живёт планом: пока строка «Приёмка на склад» непроведена,
     * зависимость маршрута держит следующую за ней работу закрытой, а
     * руководитель подразделения видит незакрытую операцию склада.
     * Принятая ячейка закрывает ближайший непроведённый экземпляр —
     * ровно тот же порядок по номеру экземпляра, что и при внесении
     * ячеек в заказ (см. {@code SubassemblyKittingService
     * #completeScheduleRow}), иначе разблокировка отстала бы от
     * фактического состава заказа.
     *
     * Проводится не больше экземпляров, чем приняли ячеек: заказ на
     * пять изделий при трёх принятых оставляет два экземпляра
     * открытыми, и это правда, а не недосмотр склада.
     *
     * Заказ, вложенный в выпуск, своей ветви строк не имеет — его
     * работу планирует заказ покупателя и проводит исполнитель в «Мои
     * операции», поэтому пустой перечень здесь норма.
     */
    private void completeScheduleRows(ProductionOrder order,
                                      Operation acceptance,
                                      int acceptedCount,
                                      AppUser receiver,
                                      LocalDateTime moment) {

        if (order.getId() == null || acceptance == null
                || acceptance.getId() == null || acceptedCount <= 0) {
            return;
        }

        List<OrderScheduleRow> open = scheduleRowRepository
                .findOpenRowsOfProductionOrder(order.getId(), acceptance.getId());

        List<OrderScheduleRow> closing = open.stream()
                .limit(acceptedCount)
                .toList();

        for (OrderScheduleRow row : closing) {
            row.setCompletedAt(moment);
            row.setCompletedBy(receiver);
        }

        if (!closing.isEmpty()) {
            scheduleRowRepository.saveAll(closing);
        }
    }

    /**
     * Закрывает комплектовочную операцию принятых ячеек в рамках заказа.
     *
     * Берёт актуальные попытки операций именно этого ЗНП (по колонке
     * production_order_id, V20) и закрывает те, что относятся к принятым
     * ячейкам и ещё не закрыты фактом. Строку не выдумывает: операция
     * появляется при комплектовании, приёмка лишь подтверждает её
     * выполнение складом, поэтому незакрытых строк может и не быть —
     * тогда закрывать нечего, и это норма, а не ошибка.
     */
    private void closeKittingOperations(
            Long orderId,
            Set<Long> acceptedSerialIds,
            AppUser receiver,
            LocalDateTime moment) {

        LocalDate today = moment.toLocalDate();

        List<ProductSerialOperation> open =
                serialOperationRepository.findCurrentByProductionOrderId(
                        orderId,
                        ProductSerialOperationStatus.REWORKED
                ).stream()
                .filter(op -> op.getActualDate() == null)
                .filter(op -> op.getSerial() != null
                        && acceptedSerialIds.contains(op.getSerial().getId()))
                .toList();

        for (ProductSerialOperation operation : open) {
            operation.setStatus(ProductSerialOperationStatus.DONE);
            operation.setActualDate(today);
            operation.setPerformedBy(receiver);
        }

        if (!open.isEmpty()) {
            serialOperationRepository.saveAll(open);
        }
    }

    /**
     * Заказ обязан быть комплектующим: серийным ЗНП с комплектовочной
     * операцией на самом заказе либо групповым ЗНП с техпроцессом.
     */
    private ProductionOrder requireComponentZnp(Long orderId) {

        ProductionOrder order = productionOrderRepository
                .findDetailedById(orderId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Заказ на производство не найден"
                ));

        if (!order.isComponent()) {
            throw new IllegalStateException(
                    "Приёмка ячеек проходит по заказам-комплектующим, "
                            + "а заказ «" + order.getNumber() + "» им не является"
            );
        }

        return order;
    }

    /**
     * Наименование техпроцесса заказа — текст вместо lazy.
     *
     * Групповые ЗНП выбирают ТП при создании, и кладовщик должен видеть
     * какой маршрут прошёл заказ. Для серийного ЗНП ТП не назначается,
     * поэтому возвращается прочерк.
     */
    private static String techProcessName(ProductionOrder order) {
        TechProcess tp = order.getTechProcess();
        if (tp == null) {
            return "—";
        }
        StringBuilder sb = new StringBuilder();
        if (tp.getCode() != null && !tp.getCode().isBlank()) {
            sb.append(tp.getCode());
        }
        if (tp.getNote() != null && !tp.getNote().isBlank()) {
            if (sb.length() > 0) sb.append(" — ");
            sb.append(tp.getNote());
        }
        return sb.length() > 0 ? sb.toString() : "—";
    }

    /**
     * Наименование комплектовочной операции заказа — текст вместо lazy.
     *
     * Для серийного ЗНП операция берётся на самом заказе, для группового —
     * из первой строки матрицы комплектования, где хранится операция,
     * привязавшая ячейку к заказу.
     */
    private static String operationName(ProductionOrder order,
                                        List<SerialProductionOrder> cells) {

        Operation operation = order.getProductionOperation();

        if (operation != null
                && operation.getOperationName() != null
                && operation.getOperationName().getName() != null) {
            return operation.getOperationName().getName();
        }

        if (!cells.isEmpty()) {
            Operation linkOp = cells.get(0).getOperation();
            if (linkOp != null
                    && linkOp.getOperationName() != null
                    && linkOp.getOperationName().getName() != null) {
                return linkOp.getOperationName().getName();
            }
        }

        return "—";
    }

    /*
     * ============================================================
     * Разбор поля с номерами
     * ============================================================
     */

    /**
     * Длина полного номера по перечню заказа.
     *
     * Номер ячейки строится из кода классификатора (V27), поэтому его
     * длина зависит от изделия: жёсткая константа отсекла бы номера
     * изделий с другим кодом, и кладовщик не принял бы состав, глядя на
     * верный номер.
     */
    private static int expectedNumberLength(List<String> numbers) {
        return numbers.isEmpty() ? 0 : numbers.get(0).length();
    }

    /**
     * Разбор поля с номерами: строки разделяются переводом, запятой
     * или точкой с запятой, сканер добавляет префиксы вроде «CELL:»
     * и пробелы.
     *
     * Оставляют только цифры той же длины, что и номера заказа: длина
     * берётся от ожидаемого перечня, а не константой — коды
     * классификаторов разных изделий различаются, и жёсткая девятка
     * отсекла бы целые партии.
     */
    private static List<String> parseNumbers(String text, int numberLength) {

        if (text == null || text.isBlank()) {
            return List.of();
        }

        Set<String> numbers = new LinkedHashSet<>();

        for (String line : text.split("[,;\\r\\n]+")) {

            String digits = line.replaceAll("\\D", "");

            if (digits.length() == numberLength) {
                numbers.add(digits);
            }
        }

        return List.copyOf(numbers);
    }

    /**
     * Перечень номеров в сообщении: длинный список обрезают, иначе
     * плашка растянулась бы на весь экран.
     */
    private static String join(List<String> numbers) {
        return numbers.size() <= MESSAGE_NUMBER_LIMIT
                ? String.join(", ", numbers)
                : String.join(", ", numbers.subList(0, MESSAGE_NUMBER_LIMIT))
                + " …";
    }

    /**
     * Экран приёмки: заказ, его непринятые ячейки и подпись операции.
     *
     * {@code operationName} и {@code techProcessName} отдаются строкой,
     * а не через связь заказа: open-in-view выключен, и обращение шаблона
     * к наименованию упало бы на ленивой связи.
     */
    public record ZnpAcceptanceView(
            ProductionOrder order,
            List<SerialProductionOrder> cells,
            String operationName,
            String techProcessName,
            boolean acceptanceAvailable
    ) {

        public List<String> cellNumbers() {
            return cells.stream()
                    .map(link -> link.getSerial().getFullNumber())
                    .collect(Collectors.toList());
        }
    }
}
