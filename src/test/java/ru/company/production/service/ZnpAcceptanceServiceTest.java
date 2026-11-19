package ru.company.production.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.company.production.dto.InputControlResult;
import ru.company.production.dto.ZnpAcceptanceForm;
import ru.company.production.entity.AppUser;
import ru.company.production.entity.Operation;
import ru.company.production.entity.OperationName;
import ru.company.production.entity.OperationType;
import ru.company.production.entity.OrderScheduleRow;
import ru.company.production.entity.ProductClassifier;
import ru.company.production.entity.ProductSerial;
import ru.company.production.entity.ProductSerialOperationStatus;
import ru.company.production.entity.ProductType;
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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Приёмка ячеек на склад по групповому ЗНП.
 *
 * <p>Проверяется правило закрытия заказа: склад принимает только те
 * экземпляры, что дошли до приёмки по маршруту, а заказ завершается,
 * когда приняли ВСЕ непринятые ячейки его состава. Ошибка здесь
 * двоякая: примем недошедший экземпляр — проведём приёмку поверх
 * непройденного контроля; закроем заказ при неполном составе — изделия
 * исчезнут из плана, не побывав на складе.
 *
 * <p>Ячейки изолятора брака в расчёте участвуют косвенно: цех снимает их
 * строку из матрицы состава, поэтому они выпадают из непринятых и заказ
 * закрывается без них.
 */
@ExtendWith(MockitoExtension.class)
class ZnpAcceptanceServiceTest {

    @Mock private ProductionOrderRepository productionOrderRepository;
    @Mock private SerialProductionOrderRepository linkRepository;
    @Mock private ProductSerialRepository serialRepository;
    @Mock private ProductSerialOperationRepository serialOperationRepository;
    @Mock private OrderScheduleRowRepository scheduleRowRepository;
    @Mock private TechProcessOperationRepository techProcessOpRepository;
    @Mock private WorkshopDistributionService distributionService;
    @Mock private SerialMovementJournalService journal;

    private ZnpAcceptanceService service;

    private static final long ORDER_ID = 115L;
    private static final long PROCESS_ID = 6L;
    private static final long ACCEPTANCE_OP_ID = 90L;
    private static final int CELLS = 5;

    private ProductionOrder order;
    private Operation acceptance;

    @BeforeEach
    void setUp() {
        service = new ZnpAcceptanceService(
                productionOrderRepository,
                linkRepository,
                serialRepository,
                serialOperationRepository,
                scheduleRowRepository,
                techProcessOpRepository,
                distributionService,
                journal
        );

        acceptance = receivingOperation(ACCEPTANCE_OP_ID);
        order = groupZnp();
    }

    /* ============================================================
       ДАННЫЕ
       ============================================================ */

    private static AppUser receiver() {
        AppUser user = new AppUser();
        user.setId(4L);
        user.setUsername("wh-01");
        user.setFullName("Кладовщик К. К.");
        return user;
    }

    private static OperationType type(String code) {
        OperationType operationType = new OperationType();
        operationType.setCode(code);
        return operationType;
    }

    private static Operation receivingOperation(long id) {
        OperationName name = new OperationName();
        name.setName("Приёмка на склад");

        Operation operation = new Operation();
        operation.setId(id);
        operation.setOperationName(name);
        operation.setOperationType(type("RECEIVING"));
        return operation;
    }

    /** Групповой ЗНП на ячейки: заказ заводится изделием и количеством. */
    private static ProductionOrder groupZnp() {
        ProductClassifier classifier = new ProductClassifier();
        classifier.setId(21L);
        classifier.setCode(5102);
        classifier.setName("Ячейка");

        TechProcess process = new TechProcess();
        process.setId(PROCESS_ID);
        process.setCode("ТП-ЯЧ");

        ProductionOrder order = new ProductionOrder();
        order.setId(ORDER_ID);
        order.setNumber("ЗНП-2026-045");
        order.setZnpKind(ZnpKind.GROUP_CELL);
        order.setProductClassifier(classifier);
        order.setTechProcess(process);
        order.setQuantity(new BigDecimal("5.000"));
        order.setStatus(ProductionOrderStatus.IN_WORK);
        return order;
    }

    /** Внесённая в заказ ячейка: полный номер = код + пятизначная серия. */
    private static SerialProductionOrder cell(int index) {
        ProductClassifier classifier = new ProductClassifier();
        classifier.setId(21L);
        classifier.setCode(5102);

        ProductSerial serial = new ProductSerial();
        serial.setId(1000L + index);
        serial.setProductType(ProductType.CELL);
        serial.setSerial(index + 1);
        serial.setProductClassifier(classifier);

        SerialProductionOrder link = new SerialProductionOrder();
        link.setId(2000L + index);
        link.setSerial(serial);
        return link;
    }

    private static List<SerialProductionOrder> cells(int count) {
        List<SerialProductionOrder> links = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            links.add(cell(i));
        }

        return links;
    }

    /** Открытая строка плана приёмки данного экземпляра. */
    private static OrderScheduleRow acceptanceRow(int instanceIndex) {
        ProductionOrder base = new ProductionOrder();
        base.setId(ORDER_ID);

        Operation operation = new Operation();
        operation.setId(ACCEPTANCE_OP_ID);

        OrderScheduleRow row = new OrderScheduleRow();
        row.setId(971L + instanceIndex);
        row.setSeq(5);
        row.setInstanceIndex(instanceIndex);
        row.setProductionOrder(base);
        row.setOperation(operation);
        return row;
    }

    private static List<OrderScheduleRow> acceptanceRows(int count) {
        List<OrderScheduleRow> rows = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            rows.add(acceptanceRow(i));
        }

        return rows;
    }

    /** Узел маршрута: приёмка техпроцесса заказа. */
    private TechProcessOperation routeNode() {
        TechProcess process = new TechProcess();
        process.setId(PROCESS_ID);

        TechProcessOperation node = new TechProcessOperation();
        node.setTechProcess(process);
        node.setPosition(5);
        node.setOperation(acceptance);
        return node;
    }

    private static ZnpAcceptanceForm allCells() {
        ZnpAcceptanceForm form = new ZnpAcceptanceForm();
        form.setAllCells(true);
        return form;
    }

    /** Приёмка по конкретным внесённым номерам ячеек. */
    private static ZnpAcceptanceForm chosenCells(String... numbers) {
        ZnpAcceptanceForm form = new ZnpAcceptanceForm();
        form.setCellNumbersText(String.join(",", numbers));
        return form;
    }

    /** Полный номер i-й по внесению ячейки: 5102 + 00001. */
    private static String cellNumber(int index) {
        return "5102" + String.format("%05d", index + 1);
    }

    /*
     * Заказ читается, его очередь открыта: строка приёмки есть и хотя
     * бы одна её копия разблокирована. Заблокированные экземпляры
     * передаёт blockedRows — они и есть «ещё в работе».
     */
    private void givenOrderWith(List<SerialProductionOrder> pending,
                                List<OrderScheduleRow> openRows,
                                List<Long> blockedRowIds) {

        when(productionOrderRepository.findDetailedById(ORDER_ID))
                .thenReturn(Optional.of(order));
        when(linkRepository.findPendingCellsByOrderId(ORDER_ID))
                .thenReturn(pending);
        when(techProcessOpRepository.findUnitNodesForProcesses(
                anyList(), anyList(), any())).thenReturn(List.of(routeNode()));
        // Строки плана приёмки того же основания, что и очередь заказа:
        // без них isAcceptanceOpen() не увидит planned-строк и отклонит
        // приёмку раньше, чем дойдёт до правила закрытия.
        when(scheduleRowRepository.findRouteRowEntitiesOfProductionOrders(anyList()))
                .thenReturn(openRows);
        when(distributionService.blockerLabelsOf(anyList()))
                .thenReturn(blockerLabels(blockedRowIds));
        // Эти два обращения случаются только когда приёмка дошла до
        // основной логики; в тесте «ни один экземпляр не дошёл» она
        // отклоняется раньше, поэтому стабы объявляем lenient.
        org.mockito.Mockito.lenient().when(
                scheduleRowRepository.findOpenRowsOfProductionOrder(
                        ORDER_ID, ACCEPTANCE_OP_ID)).thenReturn(openRows);
        // Порядок внесения ячеек задаёт сопоставление экземпляру плана
        // (i-я внесённая ячейка → экземпляр i): приёмка читает его, чтобы
        // провести ровно ту строку, чьё изделие дошло до склада.
        org.mockito.Mockito.lenient().when(
                linkRepository.findAttachedByOrderIds(
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(pending);
        org.mockito.Mockito.lenient().when(
                serialOperationRepository.findCurrentByProductionOrderId(
                        anyLong(), eq(ProductSerialOperationStatus.REWORKED)))
                .thenReturn(List.of());
    }

    private static Map<Long, String> blockerLabels(List<Long> blockedRowIds) {
        Map<Long, String> labels = new java.util.LinkedHashMap<>();

        for (Long id : blockedRowIds) {
            labels.put(id, "Передача в склад");
        }

        return labels;
    }

    /* ============================================================
       ПРАВИЛО ЗАКРЫТИЯ ЗАКАЗА
       ============================================================ */

    @Test
    @DisplayName("Часть ячеек ещё в работе — принимаем только дошедшие, заказ не закрывается")
    void acceptsOnlyInstancesThatReachedAcceptance() {

        List<OrderScheduleRow> open = acceptanceRows(CELLS);

        /* Экземпляр 0 дошёл до приёмки, остальные четверо — ещё на передаче. */
        givenOrderWith(
                cells(CELLS),
                open,
                List.of(open.get(1).getId(), open.get(2).getId(),
                        open.get(3).getId(), open.get(4).getId())
        );

        InputControlResult result = service.accept(ORDER_ID, allCells(), receiver());

        assertTrue(result.completed());
        assertTrue(result.message().contains("Принято на склад: 1"),
                "на склад уходит ровно дошедший экземпляр: " + result.message());
        assertTrue(result.message().contains("не дошли до приёмки"),
                "кладовщик видит, почему остальные не приняты: " + result.message());
        assertTrue(result.message().contains("Заказ остаётся в работе"),
                "заказ при неполном составе не закрывается: " + result.message());

        assertEquals(ProductionOrderStatus.IN_WORK, order.getStatus());

        /* Проведена ровно одна строка плана — разблокированная. */
        assertEquals(1, open.stream().filter(r -> r.getCompletedAt() != null).count());
    }

    @Test
    @DisplayName("Приняты все непринятые ячейки состава — заказ завершён")
    void closesOrderWhenWholeCompositionAccepted() {

        List<OrderScheduleRow> open = acceptanceRows(CELLS);

        givenOrderWith(cells(CELLS), open, List.of());

        InputControlResult result = service.accept(ORDER_ID, allCells(), receiver());

        assertTrue(result.completed());
        assertTrue(result.message().contains("Принято на склад: 5"), result.message());
        assertFalse(result.message().contains("остаётся в работе"), result.message());

        assertEquals(ProductionOrderStatus.DONE, order.getStatus());
        assertEquals(CELLS, open.stream().filter(r -> r.getCompletedAt() != null).count());
    }

    @Test
    @DisplayName("Внесён номер ячейки, чей экземпляр не дошёл, — приёмка отклонена")
    void rejectsCellOfInstanceThatDidNotReachAcceptance() {

        List<OrderScheduleRow> open = acceptanceRows(CELLS);

        /*
         * До склада дошёл экземпляр 0; кладовщик же вносит номер второй
         * по внесению ячейки — она принадлежит экземпляру 1, который
         * ещё на передаче.
         *
         * Раньше приёмка смотрела на позицию в выбранном перечне: одна
         * ячейка лежала бы на первой строке плана, и склад принял бы
         * непройденное изделие, проведя чужую работу.
         */
        givenOrderWith(
                cells(CELLS),
                open,
                List.of(open.get(1).getId(), open.get(2).getId(),
                        open.get(3).getId(), open.get(4).getId())
        );

        InputControlResult result = service.accept(
                ORDER_ID, chosenCells(cellNumber(1)), receiver());

        assertFalse(result.completed(),
                "принимать нечего: у внесённой ячейки предшественники не проведены");
        assertTrue(open.stream().allMatch(r -> r.getCompletedAt() == null),
                "ни одна строка плана не проведена");

        verify(serialRepository, org.mockito.Mockito.never())
                .markAcceptedByIds(anyList(), any(), any());
    }

    @Test
    @DisplayName("Проводится строка того экземпляра, чей номер внесён")
    void completesRowOfTheInstanceWhoseCellWasChosen() {

        List<OrderScheduleRow> open = acceptanceRows(CELLS);

        /* Дошли экземпляры 0 и 2; кладовщик вносит ячейку третьего по счёту изделия. */
        givenOrderWith(
                cells(CELLS),
                open,
                List.of(open.get(1).getId(), open.get(3).getId(),
                        open.get(4).getId())
        );

        InputControlResult result = service.accept(
                ORDER_ID, chosenCells(cellNumber(2)), receiver());

        assertTrue(result.completed(), result.message());
        assertTrue(result.message().contains("Принято на склад: 1"), result.message());

        /*
         * Разблокированных строк две (экземпляры 0 и 2), но проведена
         * обязана быть ровно строка принятого изделия — иначе цех
         * увидел бы принятым чужой экземпляр.
         */
        assertNotNull(open.get(2).getCompletedAt(),
                "проведена строка экземпляра принятой ячейки");
        assertNull(open.get(0).getCompletedAt(),
                "строка чужого экземпляра осталась открытой");
        assertEquals(1, open.stream().filter(r -> r.getCompletedAt() != null).count());
    }

    @Test
    @DisplayName("Ячейка в изоляторе брака заказ не держит — её нет в составе")
    void quarantinedCellDoesNotHoldOrderOpen() {

        List<OrderScheduleRow> open = acceptanceRows(CELLS);

        /*
         * Одну ячейку забраковали: цех снял её строку из матрицы, и в
         * непринятых её нет — состав заказа стал из четырёх ячеек.
         */
        givenOrderWith(cells(CELLS - 1), open, List.of(open.get(4).getId()));

        InputControlResult result = service.accept(ORDER_ID, allCells(), receiver());

        assertTrue(result.completed());
        assertEquals(ProductionOrderStatus.DONE, order.getStatus(),
                "заказ закрывается без забракованной ячейки: " + result.message());
    }

    @Test
    @DisplayName("Ни один экземпляр не дошёл — приёмка не проводится")
    void nothingAcceptedWhenAllInstancesBlocked() {

        List<OrderScheduleRow> open = acceptanceRows(CELLS);

        givenOrderWith(cells(CELLS), open, open.stream().map(OrderScheduleRow::getId).toList());

        InputControlResult result = service.accept(ORDER_ID, allCells(), receiver());

        assertFalse(result.completed(),
                "принимать нечего: ни одно изделие до склада не дошло");
        assertEquals(ProductionOrderStatus.IN_WORK, order.getStatus());
        assertTrue(open.stream().allMatch(r -> r.getCompletedAt() == null));

        verify(serialRepository, org.mockito.Mockito.never())
                .markAcceptedByIds(anyList(), any(), any());
    }

    @Test
    @DisplayName("Непринятых ячеек нет — отказ человеческим сообщением")
    void rejectsWhenNothingPending() {

        when(productionOrderRepository.findDetailedById(ORDER_ID))
                .thenReturn(Optional.of(order));
        when(linkRepository.findPendingCellsByOrderId(ORDER_ID))
                .thenReturn(List.of());

        InputControlResult result = service.accept(ORDER_ID, allCells(), receiver());

        assertFalse(result.completed());
        assertTrue(result.message().contains("нет непринятых ячеек"), result.message());
    }
}
