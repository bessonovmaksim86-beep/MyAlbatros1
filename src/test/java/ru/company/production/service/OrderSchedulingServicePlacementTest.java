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
 * Unit-тесты размещения операций в графике:
 * <ul>
 *   <li>размещение при достаточной мощности</li>
 *   <li>сдвиг при превышении maxLoadPercent</li>
 *   <li>учёт существующей занятости (occupancy)</li>
 *   <li>отсутствие исполнителей → условная мощность</li>
 *   <li>исчезающие сотрудники в день операции</li>
 *   </ul>
 */
@ExtendWith(MockitoExtension.class)
class OrderSchedulingServicePlacementTest {

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

    /* ======================== РАЗМЕЩЕНИЕ ======================== */

    @Nested
    @DisplayName("Размещение при достаточной мощности")
    class AdequateCapacityTests {

        @Test
        @DisplayName("Операция размещается в первый рабочий день, если загрузка допустима")
        void placeInFirstDay() throws Exception {
            // Операция: 1 исполнитель, 8ч мощность
            // Трудоёмкость: 8ч
            // Загрузка: 8/8 * 100 = 100%
            // maxLoadPercent = 100 → помещается
            Long operationId = 1L;
            int maxLoadPercent = 100;

            TechProcessOperation node = techProcessOperationNode(operationId, new BigDecimal("8"));
            when(staffBindingRepository.findCapableEmployeeIds(
                    eq(operationId), anyList(), anyBoolean()
            )).thenReturn(List.of(101L));

            OrderSchedulingService.CalendarSnapshot snap = snapshotOf();

            // Вызываем private метод через reflection
            Object placement = callPlaceOperation(
                    operationId, node,
                    LocalDate.of(2026, 10, 21),
                    new HashMap<>(),
                    maxLoadPercent, snap
            );

            assertNotNull(placement);
            assertEquals(LocalDate.of(2026, 10, 21),
                    getPlacementDate(placement));
            assertTrue(getExecutorBound(placement));
        }

        @Test
        @DisplayName("maxLoadPercent = 80 при 100% загрузки → сдвиг за горизонт")
        void shiftOnOverload() throws Exception {
            Long operationId = 1L;
            int maxLoadPercent = 80;

            TechProcessOperation node = techProcessOperationNode(operationId, new BigDecimal("8"));
            when(staffBindingRepository.findCapableEmployeeIds(
                    eq(operationId), anyList(), anyBoolean()
            )).thenReturn(List.of(101L));

            OrderSchedulingService.CalendarSnapshot snap = snapshotOf();

            Object placement = callPlaceOperation(
                    operationId, node,
                    LocalDate.of(2026, 10, 21),
                    new HashMap<>(),
                    maxLoadPercent, snap
            );

            /*
             * Один исполнитель — это 8 часов мощности, а операция тоже 8
             * часов: 100% в любой день. Порог 80% такой этап не принимает
             * никогда, и сдвиг ни к чему не приводит. Прежний тест ждал
             * 22.10 — он считал, что перегруз лечится переносом на день,
             * хотя вылечить его может только ещё один исполнитель либо
             * меньшая трудоёмкость. План уходит за горизонт, и обязан
             * быть помечен: молча поставить дату — значит выдать
             * невыполнимый график за выполнимый.
             */
            assertTrue(getShiftedPastHorizon(placement));
            assertTrue(getLoadPercent(placement) > maxLoadPercent);
            assertFalse(getPlacementDate(placement).isBefore(LocalDate.of(2026, 10, 21)));
        }

        @Test
        @DisplayName("2 исполнителя (16ч) при трудоёмкости 8ч → 50%, помещается")
        void twoExecutors() throws Exception {
            Long operationId = 1L;
            int maxLoadPercent = 100;

            TechProcessOperation node = techProcessOperationNode(operationId, new BigDecimal("8"));
            when(staffBindingRepository.findCapableEmployeeIds(
                    eq(operationId), anyList(), anyBoolean()
            )).thenReturn(List.of(101L, 102L));

            OrderSchedulingService.CalendarSnapshot snap = snapshotOf();

            Object placement = callPlaceOperation(
                    operationId, node,
                    LocalDate.of(2026, 10, 21),
                    new HashMap<>(),
                    maxLoadPercent, snap
            );

            assertEquals(LocalDate.of(2026, 10, 21),
                    getPlacementDate(placement));
            assertEquals(50, getLoadPercent(placement));
        }

        @Test
        @DisplayName("3 исполнителя, 3×8=24ч, трудоёмкость 8ч → 33%")
        void threeExecutors() throws Exception {
            Long operationId = 1L;
            int maxLoadPercent = 100;

            TechProcessOperation node = techProcessOperationNode(operationId, new BigDecimal("8"));
            when(staffBindingRepository.findCapableEmployeeIds(
                    eq(operationId), anyList(), anyBoolean()
            )).thenReturn(List.of(101L, 102L, 103L));

            OrderSchedulingService.CalendarSnapshot snap = snapshotOf();

            Object placement = callPlaceOperation(
                    operationId, node,
                    LocalDate.of(2026, 10, 21),
                    new HashMap<>(),
                    maxLoadPercent, snap
            );

            assertEquals(LocalDate.of(2026, 10, 21),
                    getPlacementDate(placement));
            assertEquals(33, getLoadPercent(placement));
        }
    }

    /* ======================== СУЩЕСТВУЮЩАЯ ЗАНЯТОСТЬ ======================== */

    @Nested
    @DisplayName("Учёт существующей занятости (occupancy)")
    class OccupancyTests {

        @Test
        @DisplayName("Существующая загрузка суммируется с новой трудоёмкостью")
        void occupancySummed() throws Exception {
            Long operationId = 1L;
            int maxLoadPercent = 100;

            TechProcessOperation node = techProcessOperationNode(operationId, new BigDecimal("8"));
            when(staffBindingRepository.findCapableEmployeeIds(
                    eq(operationId), anyList(), anyBoolean()
            )).thenReturn(List.of(101L));

            OrderSchedulingService.CalendarSnapshot snap = snapshotOf();

            // Существующая занятость: 8ч в этот день (чужой заказ)
            Map<OrderSchedulingService.StageDay, BigDecimal> occupancy = new HashMap<>();
            occupancy.merge(new OrderSchedulingService.StageDay(operationId, LocalDate.of(2026, 10, 21)),
                    new BigDecimal("8"), BigDecimal::add);

            Object placement = callPlaceOperation(
                    operationId, node,
                    LocalDate.of(2026, 10, 21),
                    occupancy,
                    maxLoadPercent, snap
            );

            // (8+8)/8 * 100 = 200% → сдвиг
            assertEquals(LocalDate.of(2026, 10, 22),
                    getPlacementDate(placement));
        }

        @Test
        @DisplayName("Операция размещается в день, где суммарная загрузка в норме")
        void occupancyFitsNextDay() throws Exception {
            Long operationId = 1L;
            int maxLoadPercent = 100;

            TechProcessOperation node = techProcessOperationNode(operationId, new BigDecimal("8"));
            when(staffBindingRepository.findCapableEmployeeIds(
                    eq(operationId), anyList(), anyBoolean()
            )).thenReturn(List.of(101L));

            OrderSchedulingService.CalendarSnapshot snap = snapshotOf();

            // 22.10 нет загрузки → помещается
            Map<OrderSchedulingService.StageDay, BigDecimal> occupancy = new HashMap<>();

            Object placement = callPlaceOperation(
                    operationId, node,
                    LocalDate.of(2026, 10, 21),
                    occupancy,
                    maxLoadPercent, snap
            );

            assertEquals(LocalDate.of(2026, 10, 21),
                    getPlacementDate(placement));
        }

        @Test
        @DisplayName("2 экземпляра одной операции: второй видит загрузку первого")
        void twoInstances() throws Exception {
            Long operationId = 1L;
            int maxLoadPercent = 100;

            TechProcessOperation node = techProcessOperationNode(operationId, new BigDecimal("8"));
            when(staffBindingRepository.findCapableEmployeeIds(
                    eq(operationId), anyList(), anyBoolean()
            )).thenReturn(List.of(101L));

            OrderSchedulingService.CalendarSnapshot snap = snapshotOf();

            Map<OrderSchedulingService.StageDay, BigDecimal> occupancy = new HashMap<>();

            // Первый экземпляр: 8/8 = 100%
            Object first = callPlaceOperation(
                    operationId, node,
                    LocalDate.of(2026, 10, 21),
                    occupancy,
                    maxLoadPercent, snap
            );
            assertEquals(LocalDate.of(2026, 10, 21), getPlacementDate(first));

            // Обновляем occupancy как это делает реальный алгоритм
            occupancy.merge(new OrderSchedulingService.StageDay(operationId, LocalDate.of(2026, 10, 21)),
                    new BigDecimal("8"), BigDecimal::add);

            // Второй экземпляр: (8+8)/8 = 200% → сдвиг
            Object second = callPlaceOperation(
                    operationId, node,
                    LocalDate.of(2026, 10, 21),
                    occupancy,
                    maxLoadPercent, snap
            );
            assertEquals(LocalDate.of(2026, 10, 22), getPlacementDate(second));
        }
    }

    /* ======================== ОТСУТСТВИЕ ИСПОЛНИТЕЛЕЙ ======================== */

    @Nested
    @DisplayName("Отсутствие исполнителей")
    class NoExecutorsTests {

        @Test
        @DisplayName("Нет закреплённых исполнителей → условная мощность 8ч")
        void noExecutors() throws Exception {
            Long operationId = 1L;
            int maxLoadPercent = 100;

            TechProcessOperation node = techProcessOperationNode(operationId, new BigDecimal("8"));
            when(staffBindingRepository.findCapableEmployeeIds(
                    eq(operationId), anyList(), anyBoolean()
            )).thenReturn(List.of());

            OrderSchedulingService.CalendarSnapshot snap = snapshotOf();

            Object placement = callPlaceOperation(
                    operationId, node,
                    LocalDate.of(2026, 10, 21),
                    new HashMap<>(),
                    maxLoadPercent, snap
            );

            // power = 8 (WORKDAY_HOURS), load = 8/8 = 100%
            assertEquals(LocalDate.of(2026, 10, 21),
                    getPlacementDate(placement));
            assertTrue(getLoadPercent(placement) >= 100);
            assertFalse(getExecutorBound(placement));
        }

        @Test
        @DisplayName("Все исполнители отсутствуют → сдвиг за горизонт")
        void allExecutorsAbsent() throws Exception {
            Long operationId = 1L;
            int maxLoadPercent = 100;

            TechProcessOperation node = techProcessOperationNode(operationId, new BigDecimal("8"));
            when(staffBindingRepository.findCapableEmployeeIds(
                    eq(operationId), anyList(), anyBoolean()
            )).thenReturn(List.of(101L));

            // 21.10 — отсутствие сотрудника 101
            Map<LocalDate, Set<Long>> absences = new HashMap<>();
            absences.put(LocalDate.of(2026, 10, 21), Set.of(101L));

            OrderSchedulingService.CalendarSnapshot snap = new OrderSchedulingService.CalendarSnapshot(
                    LocalDate.of(2026, 9, 1),
                    LocalDate.of(2027, 1, 31),
                    Set.of(),
                    absences
            );

            Object placement = callPlaceOperation(
                    operationId, node,
                    LocalDate.of(2026, 10, 21),
                    new HashMap<>(),
                    maxLoadPercent, snap
            );

            /*
             * 21.10 — исполнитель absent, present=0 → capacity=0 →
             * percent(8, 0) = 1000 > 100 → сдвиг. 22.10 — absent пуст,
             * present=1, capacity=8, percent=100% ≤ 100 → помещается.
             */
            assertEquals(LocalDate.of(2026, 10, 22),
                    getPlacementDate(placement));
            assertFalse(getShiftedPastHorizon(placement));
        }
    }

    /* ======================== СДВИГ ======================== */

    @Nested
    @DisplayName("Сдвиг при высокой загрузке")
    class ShiftOnHighLoadTests {

        @Test
        @DisplayName("Многозагруженный этап: размещает через несколько дней")
        void shiftMultipleDays() throws Exception {
            Long operationId = 1L;
            int maxLoadPercent = 100;

            TechProcessOperation node = techProcessOperationNode(operationId, new BigDecimal("8"));
            when(staffBindingRepository.findCapableEmployeeIds(
                    eq(operationId), anyList(), anyBoolean()
            )).thenReturn(List.of(101L));

            OrderSchedulingService.CalendarSnapshot snap = snapshotOf();

            // 21.10: 8ч, 22.10: 8ч, 23.10: 8ч
            Map<OrderSchedulingService.StageDay, BigDecimal> occupancy = new HashMap<>();
            occupancy.merge(new OrderSchedulingService.StageDay(operationId, LocalDate.of(2026, 10, 21)),
                    new BigDecimal("8"), BigDecimal::add);
            occupancy.merge(new OrderSchedulingService.StageDay(operationId, LocalDate.of(2026, 10, 22)),
                    new BigDecimal("8"), BigDecimal::add);
            occupancy.merge(new OrderSchedulingService.StageDay(operationId, LocalDate.of(2026, 10, 23)),
                    new BigDecimal("8"), BigDecimal::add);

            Object placement = callPlaceOperation(
                    operationId, node,
                    LocalDate.of(2026, 10, 21),
                    occupancy,
                    maxLoadPercent, snap
            );

            // 21: 16/8=200%, 22: 16/8=200%, 23: 16/8=200%, 26: 8/8=100% ≤ 100
            // 26.10 — понедельник
            // Порог 100, а не 80: один исполнитель даёт 8 часов, операция
            // занимает все 8 — при 80% не помещается ни один день, и
            // вместо проверки сдвига получили бы просто уход за горизонт.
            assertEquals(LocalDate.of(2026, 10, 26),
                    getPlacementDate(placement));
            assertFalse(getShiftedPastHorizon(placement));
        }

        @Test
        @DisplayName("Сдвиг пропускает выходные")
        void shiftSkipsWeekend() throws Exception {
            Long operationId = 1L;
            int maxLoadPercent = 100;

            TechProcessOperation node = techProcessOperationNode(operationId, new BigDecimal("8"));
            when(staffBindingRepository.findCapableEmployeeIds(
                    eq(operationId), anyList(), anyBoolean()
            )).thenReturn(List.of(101L));

            OrderSchedulingService.CalendarSnapshot snap = snapshotOf();

            // 23.10 (ПТ) полностью загружена
            Map<OrderSchedulingService.StageDay, BigDecimal> occupancy = new HashMap<>();
            occupancy.merge(new OrderSchedulingService.StageDay(operationId, LocalDate.of(2026, 10, 23)),
                    new BigDecimal("8"), BigDecimal::add);

            Object placement = callPlaceOperation(
                    operationId, node,
                    LocalDate.of(2026, 10, 23),
                    occupancy,
                    maxLoadPercent, snap
            );

            // 23: 200%, 24: СБ, 25: ВС, 26: ПН → 100%
            assertEquals(LocalDate.of(2026, 10, 26),
                    getPlacementDate(placement));
        }
    }

    /* ======================== ТРУДОЁМКОСТЬ ======================== */

    @Nested
    @DisplayName("Влияние трудоёмкости на загрузку")
    class LaborHoursTests {

        @Test
        @DisplayName("Малая трудоёмкость помещается при частичной загрузке")
        void smallLaborFits() throws Exception {
            Long operationId = 1L;
            int maxLoadPercent = 100;

            TechProcessOperation node = techProcessOperationNode(operationId, new BigDecimal("1"));
            when(staffBindingRepository.findCapableEmployeeIds(
                    eq(operationId), anyList(), anyBoolean()
            )).thenReturn(List.of(101L, 102L));

            OrderSchedulingService.CalendarSnapshot snap = snapshotOf();

            // Существующая загрузка: 10ч из 16ч
            Map<OrderSchedulingService.StageDay, BigDecimal> occupancy = new HashMap<>();
            occupancy.merge(new OrderSchedulingService.StageDay(operationId, LocalDate.of(2026, 10, 21)),
                    new BigDecimal("10"), BigDecimal::add);

            Object placement = callPlaceOperation(
                    operationId, node,
                    LocalDate.of(2026, 10, 21),
                    occupancy,
                    maxLoadPercent, snap
            );

            // (10+1)/16 * 100 = 68.75% → 69% ≤ 100%
            assertEquals(LocalDate.of(2026, 10, 21),
                    getPlacementDate(placement));
            assertEquals(69, getLoadPercent(placement));
        }

        @Test
        @DisplayName("Большая трудоёмкость → сдвиг")
        void largeLaborShifts() throws Exception {
            Long operationId = 1L;
            int maxLoadPercent = 100;

            TechProcessOperation node = techProcessOperationNode(operationId, new BigDecimal("10"));
            when(staffBindingRepository.findCapableEmployeeIds(
                    eq(operationId), anyList(), anyBoolean()
            )).thenReturn(List.of(101L, 102L));

            OrderSchedulingService.CalendarSnapshot snap = snapshotOf();

            Map<OrderSchedulingService.StageDay, BigDecimal> occupancy = new HashMap<>();
            occupancy.merge(new OrderSchedulingService.StageDay(operationId, LocalDate.of(2026, 10, 21)),
                    new BigDecimal("10"), BigDecimal::add);

            Object placement = callPlaceOperation(
                    operationId, node,
                    LocalDate.of(2026, 10, 21),
                    occupancy,
                    maxLoadPercent, snap
            );

            // (10+10)/16 * 100 = 125% > 100% → сдвиг
            assertEquals(LocalDate.of(2026, 10, 22),
                    getPlacementDate(placement));
        }
    }

    /* ======================== ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ ======================== */

    private OrderSchedulingService.CalendarSnapshot snapshotOf() {
        return new OrderSchedulingService.CalendarSnapshot(
                LocalDate.of(2026, 9, 1),
                LocalDate.of(2027, 1, 31),
                Set.of(),
                Map.of()
        );
    }

    private TechProcessOperation techProcessOperationNode(Long id, BigDecimal laborHours) {
        TechProcessOperation node = new TechProcessOperation();
        node.setId(id);
        node.setLaborHours(laborHours);
        node.setPredecessors(Set.of());
        WorkPlace wp = new WorkPlace();
        wp.setId(1L);
        node.setWorkPlaces(Set.of(wp));
        Operation op = new Operation();
        op.setId(id);
        node.setOperation(op);
        return node;
    }

    /**
     * Вызывает приватный метод placeOperation через reflection.
     *
     * Ключи занятости передаются настоящие — Service#StageDay: фальшивая
     * обёртка с теми же полями в map сервиса не совпалась бы ни с одной
     * строкой, и накопленная занятость перестала бы влиять на сдвиг,
     * оставив проверку пройденной.
     */
    private Object callPlaceOperation(
            Long operationId,
            TechProcessOperation node,
            LocalDate earliest,
            Map<OrderSchedulingService.StageDay, BigDecimal> occupancy,
            int maxLoadPercent,
            OrderSchedulingService.CalendarSnapshot calendar) throws Exception {
        java.lang.reflect.Method m = OrderSchedulingService.class
                .getDeclaredMethod("placeOperation",
                        Long.class, TechProcessOperation.class,
                        LocalDate.class, Map.class, int.class,
                        OrderSchedulingService.CalendarSnapshot.class);
        m.setAccessible(true);
        return m.invoke(service,
                operationId, node, earliest,
                occupancy, maxLoadPercent, calendar);
    }

    private LocalDate getPlacementDate(Object placement) throws Exception {
        java.lang.reflect.Field f = placement.getClass().getDeclaredField("date");
        f.setAccessible(true);
        return (LocalDate) f.get(placement);
    }

    private int getLoadPercent(Object placement) throws Exception {
        java.lang.reflect.Field f = placement.getClass().getDeclaredField("loadPercent");
        f.setAccessible(true);
        return (int) f.get(placement);
    }

    private boolean getExecutorBound(Object placement) throws Exception {
        java.lang.reflect.Field f = placement.getClass().getDeclaredField("executorBound");
        f.setAccessible(true);
        return (boolean) f.get(placement);
    }

    private boolean getShiftedPastHorizon(Object placement) throws Exception {
        java.lang.reflect.Field f = placement.getClass().getDeclaredField("shiftedPastHorizon");
        f.setAccessible(true);
        return (boolean) f.get(placement);
    }
}
