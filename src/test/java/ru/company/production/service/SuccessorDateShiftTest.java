package ru.company.production.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.company.production.entity.AppUser;
import ru.company.production.entity.Operation;
import ru.company.production.entity.OperationType;
import ru.company.production.entity.OperationTypeCode;
import ru.company.production.entity.OrderScheduleRow;
import ru.company.production.entity.ProductionOrder;
import ru.company.production.entity.TechProcess;
import ru.company.production.entity.TechProcessOperation;
import ru.company.production.repository.EmployeeAbsenceRepository;
import ru.company.production.repository.OrderScheduleRowRepository;
import ru.company.production.repository.TechProcessOperationControlParamRepository;
import ru.company.production.repository.TechProcessOperationRepository;
import ru.company.production.repository.TechProcessRepository;
import ru.company.production.repository.UserRepository;
import ru.company.production.repository.WorkshopStaffBindingRepository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Проверка хука переноса срока: проведение передачи подтягивает план
 * операции-преемника к факту передачи.
 *
 * <p>Смысл хука в том, что план преемника строят по календарю сдающего
 * подразделения и обычно закладывают с запасом. Когда позицию передают
 * досрочно, план приёмщика остаётся за горизонтом «Моих операций», и
 * уже привезённую работу приёмщик не видит вовсе — ровно тот случай,
 * из-за которого контроль ячеек БТК не появился в списке после
 * «Передачи в БТК».
 *
 * <p>Тест держит границу, за которую хук не имеет права заходить: план
 * позже факта передачи не двигается. Сдвиг вперёд означал бы, что
 * проведение одной работы передвигает срок другой без спроса, и
 * «сколько сделано к сроку» перестало бы считаться по плану.
 *
 * <p>Зависимости сервиса подставлены моками, но мокается ровно тот путь,
 * который проведение действительно проходит: строка загружается, право и
 * зависимости маршрута проверяются, факт записывается — и только потом
 * работает хук. Лишние заглушки здесь ошибкой: тест с ними подтверждал
 * бы собственный набор вызовов, а не поведение.
 */
@ExtendWith(MockitoExtension.class)
class SuccessorDateShiftTest {

    private static final long PROCESS_ID = 100L;
    private static final long ORDER_ID = 500L;
    private static final long TRANSFER_OPERATION_ID = 1L;
    private static final long SUCCESSOR_OPERATION_ID = 2L;
    private static final long TRANSFER_ROW_ID = 1L;
    private static final long SUCCESSOR_ROW_ID = 2L;

    private static final LocalDate FACT_DATE = LocalDate.of(2026, 11, 10);
    /**
     * Факт проведения перезаписывает completedAt на LocalDateTime.now(),
     * поэтому для nextWorkingDay нужен текущий день, а не константа.
     */
    private static final LocalDate NOW = LocalDate.now();

    @Mock private OrderScheduleRowRepository scheduleRowRepo;
    @Mock private WorkshopStaffBindingRepository bindingRepo;
    @Mock private TechProcessRepository techProcessRepo;
    @Mock private EmployeeAbsenceRepository absenceRepo;
    @Mock private UserRepository userRepository;
    @Mock private WorkshopStaffService staffService;
    @Mock private WorkshopDistributionSerialService serialService;
    @Mock private TechProcessOperationRepository routeNodeRepo;
    @Mock private TechProcessOperationControlParamRepository controlParamLinkRepo;
    @Mock private ControlReturnService controlReturnService;
    @Mock private UnitHandoverService handoverService;
    @Mock private WorkingDaysCalculator workingDays;

    private WorkshopDistributionService service;
    private AppUser performer;

    @BeforeEach
    void setUp() {
        service = new WorkshopDistributionService(
                scheduleRowRepo, bindingRepo, techProcessRepo,
                absenceRepo, userRepository, staffService, serialService,
                routeNodeRepo, controlParamLinkRepo, controlReturnService,
                handoverService, workingDays
        );

        performer = new AppUser();
        performer.setId(1L);
        performer.setUsername("sdp-head");
    }

    /** Справочная операция указанного типа. */
    private static Operation operation(Long id, OperationTypeCode type) {
        OperationType operationType = new OperationType();
        operationType.setCode(type.name());

        Operation operation = new Operation();
        operation.setId(id);
        operation.setOperationType(operationType);

        return operation;
    }

    /** Строка плана того же экземпляра (позиция 1, экземпляр 1). */
    private static OrderScheduleRow row(Long id, Operation operation,
            LocalDate plannedDate) {

        TechProcess process = new TechProcess();
        process.setId(PROCESS_ID);

        ProductionOrder order = new ProductionOrder();
        order.setId(ORDER_ID);
        order.setNumber("ЗНП-2026-44");

        OrderScheduleRow row = new OrderScheduleRow();
        row.setId(id);
        row.setTechProcess(process);
        row.setOperation(operation);
        row.setProductionOrder(order);
        row.setItemPosition(1);
        row.setInstanceIndex(1);
        row.setPlannedDate(plannedDate);

        return row;
    }

    /** Узел маршрута пары «техпроцесс + операция». */
    private static TechProcessOperation node(Long id, Operation operation) {
        TechProcessOperation node = new TechProcessOperation();
        node.setId(id);
        node.setOperation(operation);

        return node;
    }

    /**
     * Проводимая строка: исполнитель совпадает с автором проведения,
     * иначе право закрывать работу не пускает дальше проверок.
     */
    private OrderScheduleRow transferRow(Operation operation) {
        OrderScheduleRow row = row(TRANSFER_ROW_ID, operation,
                FACT_DATE.minusDays(1));
        row.setExecutor(performer);

        when(scheduleRowRepo.findById(TRANSFER_ROW_ID))
                .thenReturn(Optional.of(row));

        return row;
    }

    /** Все строки, записанные сервисом за проведение. */
    private List<OrderScheduleRow> savedRows() {
        ArgumentCaptor<OrderScheduleRow> captor =
                ArgumentCaptor.forClass(OrderScheduleRow.class);

        verify(scheduleRowRepo, times(2)).save(captor.capture());

        return captor.getAllValues();
    }

    private OrderScheduleRow savedRow(Long id) {
        return savedRows().stream()
                .filter(saved -> id.equals(saved.getId()))
                .findFirst()
                .orElse(null);
    }

    @Nested
    @DisplayName("план преемника раньше факта передачи")
    class PlanBeforeFact {

        @Test
        @DisplayName("срок преемника подтягивается к дню передачи")
        void movesSuccessorToTransferDay() {
            LocalDate planned = LocalDate.of(2026, 11, 5);

            Operation transferOperation =
                    operation(TRANSFER_OPERATION_ID, OperationTypeCode.TRANSFER);
            Operation successorOperation =
                    operation(SUCCESSOR_OPERATION_ID, OperationTypeCode.CONTROL);

            transferRow(transferOperation);

            OrderScheduleRow successorRow =
                    row(SUCCESSOR_ROW_ID, successorOperation, planned);

            TechProcessOperation transferNode =
                    node(10L, transferOperation);
            TechProcessOperation successorNode =
                    node(20L, successorOperation);
            successorNode.setPredecessors(Set.of(transferNode));

            when(routeNodeRepo.findNodeOfProcessAndOperation(
                    PROCESS_ID, TRANSFER_OPERATION_ID))
                    .thenReturn(Optional.of(transferNode));
            when(routeNodeRepo.findSuccessorsOfNode(10L))
                    .thenReturn(List.of(successorNode));
            when(scheduleRowRepo.findOpenRowsOfProductionOrderInstanceOperation(
                    ORDER_ID, 1, 1, PROCESS_ID, SUCCESSOR_OPERATION_ID))
                    .thenReturn(List.of(successorRow));
            when(workingDays.nextWorkingDay(NOW))
                    .thenReturn(NOW);

            service.completeRow(TRANSFER_ROW_ID, performer);

            OrderScheduleRow saved = savedRow(SUCCESSOR_ROW_ID);

            assertNotNull(saved, "план преемника не перенесён");
            assertEquals(NOW, saved.getPlannedDate());
            assertEquals(planned, saved.getOriginalPlannedDate(),
                    "прежний план потерян — колонка «изначально"
                            + " планировалась» станет нечем заполнить");
        }
    }

    @Nested
    @DisplayName("план преемника позже факта передачи")
    class PlanAfterFact {

        @Test
        @DisplayName("срок преемника остаётся прежним")
        void keepsLaterPlan() {
            LocalDate planned = LocalDate.of(2026, 11, 25);

            Operation transferOperation =
                    operation(TRANSFER_OPERATION_ID, OperationTypeCode.TRANSFER);
            Operation successorOperation =
                    operation(SUCCESSOR_OPERATION_ID, OperationTypeCode.CONTROL);

            transferRow(transferOperation);

            TechProcessOperation transferNode =
                    node(10L, transferOperation);
            TechProcessOperation successorNode =
                    node(20L, successorOperation);

            when(routeNodeRepo.findNodeOfProcessAndOperation(
                    PROCESS_ID, TRANSFER_OPERATION_ID))
                    .thenReturn(Optional.of(transferNode));
            when(routeNodeRepo.findSuccessorsOfNode(10L))
                    .thenReturn(List.of(successorNode));
            when(scheduleRowRepo.findOpenRowsOfProductionOrderInstanceOperation(
                    ORDER_ID, 1, 1, PROCESS_ID, SUCCESSOR_OPERATION_ID))
                    .thenReturn(List.of(
                            row(SUCCESSOR_ROW_ID, successorOperation, planned)));

            service.completeRow(TRANSFER_ROW_ID, performer);

            /*
             * Проведение записывает только свою строку: раз план
             * преемника и так наступит раньше передачи, двигать его
             * нечем, и любая правка здесь была бы порчей плана.
             */
            ArgumentCaptor<OrderScheduleRow> captor =
                    ArgumentCaptor.forClass(OrderScheduleRow.class);
            verify(scheduleRowRepo, times(1)).save(captor.capture());
            assertEquals(TRANSFER_ROW_ID, captor.getValue().getId());
        }
    }

    @Nested
    @DisplayName("проведение не передаточной операции")
    class NotTransferType {

        @Test
        @DisplayName("чужой тип операции план преемника не трогает")
        void leavesRouteAlone() {
            transferRow(operation(
                    TRANSFER_OPERATION_ID, OperationTypeCode.PRODUCTION));

            service.completeRow(TRANSFER_ROW_ID, performer);

            verify(routeNodeRepo, never())
                    .findNodeOfProcessAndOperation(
                            PROCESS_ID, TRANSFER_OPERATION_ID);
            verify(scheduleRowRepo, never())
                    .findOpenRowsOfProductionOrderInstanceOperation(
                            ORDER_ID, 1, 1, PROCESS_ID, SUCCESSOR_OPERATION_ID);
        }
    }

    @Nested
    @DisplayName("преемника у передачи нет")
    class SuccessorNotFound {

        @Test
        @DisplayName("последняя операция маршрута проводится без ошибок")
        void completesQuietly() {
            Operation transferOperation =
                    operation(TRANSFER_OPERATION_ID, OperationTypeCode.TRANSFER);

            transferRow(transferOperation);

            when(routeNodeRepo.findNodeOfProcessAndOperation(
                    PROCESS_ID, TRANSFER_OPERATION_ID))
                    .thenReturn(Optional.of(node(10L, transferOperation)));
            when(routeNodeRepo.findSuccessorsOfNode(10L))
                    .thenReturn(Collections.emptyList());

            assertDoesNotThrow(
                    () -> service.completeRow(TRANSFER_ROW_ID, performer));

            ArgumentCaptor<OrderScheduleRow> captor =
                    ArgumentCaptor.forClass(OrderScheduleRow.class);
            verify(scheduleRowRepo, times(1)).save(captor.capture());

            OrderScheduleRow saved = captor.getValue();
            assertEquals(TRANSFER_ROW_ID, saved.getId());
            assertNotNull(saved.getCompletedAt(),
                    "факт передачи не записан");
            assertEquals(FACT_DATE.minusDays(1), saved.getPlannedDate(),
                    "проведение не должно менять собственный план строки");
        }
    }

    /** Факт проведения ставится сегодня — тест не зависит от даты запуска. */
    @Test
    @DisplayName("проведение фиксирует факт и автора отметки")
    void recordsFactAndAuthor() {
        Operation transferOperation =
                operation(TRANSFER_OPERATION_ID, OperationTypeCode.TRANSFER);

        OrderScheduleRow row = transferRow(transferOperation);

        when(routeNodeRepo.findNodeOfProcessAndOperation(
                PROCESS_ID, TRANSFER_OPERATION_ID))
                .thenReturn(Optional.empty());

        service.completeRow(TRANSFER_ROW_ID, performer);

        LocalDateTime completedAt = row.getCompletedAt();

        assertNotNull(completedAt);
        assertEquals(performer.getId(), row.getCompletedBy().getId());
        assertEquals(LocalTime.now().getHour(), completedAt.getHour());
    }
}
