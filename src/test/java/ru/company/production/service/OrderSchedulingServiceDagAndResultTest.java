package ru.company.production.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.company.production.entity.*;
import ru.company.production.repository.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit-тесты топологического обхода маршрута и результата расчёта:
 * <ul>
 *   <li>топологический порядок операций DAG</li>
 *   <li>обработка цикла в DAG</li>
 *   <li>пустой маршрут</li>
 *   <li>ScheduleResult: confirmable / unassignedHint</li>
 *   <li>форматирование дат в результате</li>
 *   </ul>
 */
@ExtendWith(MockitoExtension.class)
class OrderSchedulingServiceDagAndResultTest {

    @Mock private CustomerOrderRepository customerOrderRepository;
    @Mock private ProductionOrderRepository productionOrderRepository;
    @Mock private TechProcessClassifierRepository techProcessClassifierRepository;
    @Mock private TechProcessRepository techProcessRepository;
    @Mock private OrderScheduleRowRepository scheduleRowRepository;
    @Mock private WorkshopStaffBindingRepository staffBindingRepository;
    @Mock private EmployeeAbsenceRepository employeeAbsenceRepository;
    @Mock private HolidayDayRepository holidayDayRepository;
    @Mock private OrderPriorityService orderPriorityService;

    private OrderSchedulingService service;

    @BeforeEach
    void setUp() {
        service = new OrderSchedulingService(
                customerOrderRepository,
                productionOrderRepository,
                techProcessClassifierRepository,
                techProcessRepository,
                scheduleRowRepository,
                staffBindingRepository,
                employeeAbsenceRepository,
                holidayDayRepository,
                orderPriorityService
        );
    }

    /* ======================== TOPологический порядок ======================== */

    @Nested
    @DisplayName("Топологический обход маршрута")
    class TopologicalOrderTests {

        @Test
        @DisplayName("Одна операция → возвращается в единственном числе")
        void singleOperation() throws Exception {
            TechProcess process = new TechProcess();
            process.setId(1L);

            Operation op = new Operation();
            op.setId(10L);
            op.setOperationName(new OperationName());
            op.getOperationName().setId(1L);
            op.getOperationName().setName("Монтаж");

            TechProcessOperation node = new TechProcessOperation();
            node.setId(10L);
            node.setOperation(op);
            node.setPredecessors(List.of());
            node.setLaborHours(BigDecimal.TEN);

            process.setOperations(List.of(node));

            when(techProcessRepository.findFullById(1L))
                    .thenReturn(Optional.of(process));

            List<OrderSchedulingService.PlannedRow> result = callOrderedRoute(1L);

            assertEquals(1, result.size());
        }

        @Test
        @DisplayName("Цепочка А→Б→В: порядок А, Б, В")
        void linearChain() throws Exception {
            TechProcess process = new TechProcess();
            process.setId(1L);

            Operation opA = createOperation(10L, "Монтаж А");
            Operation opB = createOperation(20L, "Монтаж Б");
            Operation opC = createOperation(30L, "Монтаж В");

            TechProcessOperation nodeA = new TechProcessOperation();
            nodeA.setId(10L);
            nodeA.setOperation(opA);
            nodeA.setPredecessors(List.of());

            TechProcessOperation nodeB = new TechProcessOperation();
            nodeB.setId(20L);
            nodeB.setOperation(opB);
            nodeB.setPredecessors(List.of(nodeA));

            TechProcessOperation nodeC = new TechProcessOperation();
            nodeC.setId(30L);
            nodeC.setOperation(opC);
            nodeC.setPredecessors(List.of(nodeB));

            process.setOperations(List.of(nodeA, nodeB, nodeC));

            when(techProcessRepository.findFullById(1L))
                    .thenReturn(Optional.of(process));

            List<OrderSchedulingService.PlannedRow> result = callOrderedRoute(1L);

            assertEquals(3, result.size());
        }

        @Test
        @DisplayName("Разветвление: А→Б, А→В, Б и В после А")
        void branching() throws Exception {
            TechProcess process = new TechProcess();
            process.setId(1L);

            Operation opA = createOperation(10L, "Разветвление А");
            Operation opB = createOperation(20L, "Разветвление Б");
            Operation opC = createOperation(30L, "Разветвление В");

            TechProcessOperation nodeA = new TechProcessOperation();
            nodeA.setId(10L);
            nodeA.setOperation(opA);
            nodeA.setPredecessors(List.of());

            TechProcessOperation nodeB = new TechProcessOperation();
            nodeB.setId(20L);
            nodeB.setOperation(opB);
            nodeB.setPredecessors(List.of(nodeA));

            TechProcessOperation nodeC = new TechProcessOperation();
            nodeC.setId(30L);
            nodeC.setOperation(opC);
            nodeC.setPredecessors(List.of(nodeA));

            process.setOperations(List.of(nodeA, nodeB, nodeC));

            when(techProcessRepository.findFullById(1L))
                    .thenReturn(Optional.of(process));

            List<OrderSchedulingService.PlannedRow> result = callOrderedRoute(1L);

            assertEquals(3, result.size());
            // Первая операция всегда А (без предшественников)
            assertEquals(10L, (long) result.get(0).nodeId);
        }

        @Test
        @DisplayName("Пустой маршрут → пустой список")
        void emptyRoute() throws Exception {
            TechProcess process = new TechProcess();
            process.setId(1L);
            process.setOperations(List.of());

            when(techProcessRepository.findFullById(1L))
                    .thenReturn(Optional.of(process));

            List<OrderSchedulingService.PlannedRow> result = callOrderedRoute(1L);

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("Техпроцесс не найден → пустой список")
        void processNotFound() throws Exception {
            when(techProcessRepository.findFullById(999L))
                    .thenReturn(Optional.empty());

            List<OrderSchedulingService.PlannedRow> result = callOrderedRoute(999L);

            assertTrue(result.isEmpty());
        }
    }

    /* ======================== SCHEDULE RESULT ======================== */

    @Nested
    @DisplayName("ScheduleResult")
    class ScheduleResultTests {

        @Test
        @DisplayName("confirmable = true когда нет незакреплённых операций")
        void confirmableTrue() {
            OrderSchedulingService.ScheduleResult result =
                    new OrderSchedulingService.ScheduleResult(
                            "ЗО-100",
                            "Высокий",
                            LocalDate.of(2026, 10, 23),
                            LocalDate.of(2026, 11, 15),
                            LocalDate.of(2026, 11, 10),
                            LocalDate.of(2026, 11, 5),
                            true,
                            null,
                            List.of(),
                            List.of(),
                            List.of(),
                            List.of()
                    );

            assertTrue(result.confirmable());
            assertNull(result.unassignedHint());
        }

        @Test
        @DisplayName("confirmable = false когда есть незакреплённые операции")
        void confirmableFalse() {
            OrderSchedulingService.ScheduleResult result =
                    new OrderSchedulingService.ScheduleResult(
                            "ЗО-100",
                            "Обычный",
                            LocalDate.of(2026, 10, 23),
                            LocalDate.of(2026, 11, 15),
                            LocalDate.of(2026, 11, 10),
                            LocalDate.of(2026, 11, 5),
                            false,
                            "План выходит за крайний день",
                            List.of(),
                            List.of(),
                            List.of("Предупреждение"),
                            List.of("Монтаж — Датчик ДУУ-1")
                    );

            assertFalse(result.confirmable());
            assertNotNull(result.unassignedHint());
            assertTrue(result.unassignedHint().contains("Монтаж"));
            assertTrue(result.unassignedHint().contains("Датчик"));
        }

        @Test
        @DisplayName("unassignedHint описывает количество операций")
        void unassignedHintPlural() {
            OrderSchedulingService.ScheduleResult result =
                    new OrderSchedulingService.ScheduleResult(
                            "ЗО-100",
                            "Обычный",
                            LocalDate.of(2026, 10, 23),
                            LocalDate.of(2026, 11, 15),
                            LocalDate.of(2026, 11, 10),
                            LocalDate.of(2026, 11, 5),
                            false,
                            null,
                            List.of(),
                            List.of(),
                            List.of(),
                            List.of(
                                    "Монтаж А — Позиция 1",
                                    "Сварка Б — Позиция 2",
                                    "Контроль В — Позиция 3"
                            )
                    );

            assertFalse(result.confirmable());
            String hint = result.unassignedHint();
            assertTrue(hint.contains("операциями"));
            assertTrue(hint.contains("3"));
        }

        @Test
        @DisplayName("Форматирование дат: dd.MM.yyyy")
        void formattedDates() {
            OrderSchedulingService.ScheduleResult result =
                    new OrderSchedulingService.ScheduleResult(
                            "ЗО-100",
                            "Высокий",
                            LocalDate.of(2026, 10, 23),
                            LocalDate.of(2026, 11, 15),
                            LocalDate.of(2026, 11, 10),
                            LocalDate.of(2026, 11, 5),
                            true,
                            null,
                            List.of(),
                            List.of(),
                            List.of(),
                            List.of()
                    );

            assertEquals("23.10.2026", result.getFormattedStartDate());
            assertEquals("15.11.2026", result.getFormattedDueDate());
            assertEquals("10.11.2026", result.getFormattedDeadline());
            assertEquals("05.11.2026", result.getFormattedLastDate());
        }

        @Test
        @DisplayName("Форматирование null-дат → прочерк")
        void formattedNullDates() {
            OrderSchedulingService.ScheduleResult result =
                    new OrderSchedulingService.ScheduleResult(
                            "ЗО-100",
                            "Высокий",
                            null,
                            null,
                            null,
                            null,
                            true,
                            null,
                            List.of(),
                            List.of(),
                            List.of(),
                            List.of()
                    );

            assertEquals("—", result.getFormattedStartDate());
            assertEquals("—", result.getFormattedDueDate());
            assertEquals("—", result.getFormattedDeadline());
            assertEquals("—", result.getFormattedLastDate());
        }
    }

    /* ======================== PLANNED ROW ======================== */

    @Nested
    @DisplayName("PlannedRow")
    class PlannedRowTests {

        @Test
        @DisplayName("PlannedRow.toEntity создаёт строку с CONFIRMED")
        void plannedRowToEntity() {
            Operation op = createOperation(1L, "Тест");
            OrganizationUnit unit = new OrganizationUnit();
            unit.setId(1L);
            unit.setName("Участок 1");

            OrderSchedulingService.PlannedRow row =
                    new OrderSchedulingService.PlannedRow(
                            1,   // itemPosition
                            0,   // instanceIndex
                            10L, // nodeId
                            new TechProcess(),
                            op,
                            unit,
                            1,   // seq
                            LocalDate.of(2026, 10, 25),
                            new BigDecimal("8"),
                            50
                    );

            CustomerOrder order = new CustomerOrder();
            order.setId(1L);
            order.setNumber("ЗО-100");

            OrderScheduleRow entity = row.toEntity(order);

            assertEquals(ScheduleRowStatus.CONFIRMED, entity.getStatus());
            assertEquals(order, entity.getCustomerOrder());
            assertEquals(1, entity.getItemPosition());
            assertEquals(0, entity.getInstanceIndex());
            assertEquals(1, entity.getSeq());
            assertEquals(LocalDate.of(2026, 10, 25), entity.getPlannedDate());
            assertEquals(new BigDecimal("8"), entity.getLaborHours());
            assertEquals(50, entity.getLoadPercent());
        }

        @Test
        @DisplayName("PlannedRow.toView создаёт представление строки")
        void plannedRowToView() {
            Operation op = createOperation(1L, "Тест");
            OrganizationUnit unit = new OrganizationUnit();
            unit.setId(1L);
            unit.setName("Участок 1");

            ProductClassifier classifier = new ProductClassifier();
            classifier.setCode("8888");
            classifier.setDisplayName("Датчик ДУУ-М");

            CustomerOrderItem item = new CustomerOrderItem();
            item.setPosition(1);
            item.setProductClassifier(classifier);

            TechProcess process = new TechProcess();
            process.setCode("TP-001");

            OrderSchedulingService.PlannedRow row =
                    new OrderSchedulingService.PlannedRow(
                            1, 0, 10L,
                            process,
                            op,
                            unit,
                            1,
                            LocalDate.of(2026, 10, 25),
                            new BigDecimal("8"),
                            50
                    );

            ScheduleRowView view = row.toView(3, item, process);

            assertEquals(1, view.itemPosition());
            assertEquals(0, view.instanceIndex());
            assertEquals("1/3", view.instanceLabel());
            assertEquals("Датчик ДУУ-М", view.itemName());
            assertEquals("TP-001", view.techProcessCode());
            assertEquals(1, view.seq());
            assertEquals("Тест", view.operationName());
            assertEquals("8.00", view.laborHours());
            assertEquals(50, view.loadPercent());
        }

        @Test
        @DisplayName("PlannedRow с null трудоёмкостью → ZERO")
        void nullLaborHours() {
            Operation op = createOperation(1L, "Тест");
            OrganizationUnit unit = new OrganizationUnit();
            unit.setId(1L);

            OrderSchedulingService.PlannedRow row =
                    new OrderSchedulingService.PlannedRow(
                            1, 0, 10L,
                            new TechProcess(),
                            op,
                            unit,
                            1,
                            LocalDate.of(2026, 10, 25),
                            null, // laborHours
                            50
                    );

            assertEquals(BigDecimal.ZERO, row.laborHours);
        }
    }

    /* ======================== ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ ======================== */

    private Operation createOperation(Long id, String name) {
        OperationName nameObj = new OperationName();
        nameObj.setId(1L);
        nameObj.setName(name);

        OperationType type = new OperationType();
        type.setId(1L);
        type.setCode("MOUNT");

        UserRole role = new UserRole();
        role.setId(1L);
        role.setCode("TECHNICIAN");

        ProductionService service = new ProductionService();
        service.setId(1L);

        Operation op = new Operation();
        op.setId(id);
        op.setOperationName(nameObj);
        op.setOperationType(type);
        op.setExecutorRole(role);
        op.setService(service);
        return op;
    }

    /**
     * Вызывает приватный метод orderedRoute через reflection.
     */
    @SuppressWarnings("unchecked")
    private List<OrderSchedulingService.PlannedRow> callOrderedRoute(Long processId)
            throws Exception {
        java.lang.reflect.Method m = OrderSchedulingService.class
                .getDeclaredMethod("orderedRoute", Long.class);
        m.setAccessible(true);
        return (List<OrderSchedulingService.PlannedRow>) m.invoke(service, processId);
    }
}
