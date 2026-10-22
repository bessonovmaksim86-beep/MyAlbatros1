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

            CalendarSnapshot snap = snapshotOf();

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
        @DisplayName("maxLoadPercent = 80 при 100% загрузки → сдвиг на следующий день")
        void shiftOnOverload() throws Exception {
            Long operationId = 1L;
            int maxLoadPercent = 80;

            TechProcessOperation node = techProcessOperationNode(operationId, new BigDecimal("8"));
            when(staffBindingRepository.findCapableEmployeeIds(
                    eq(operationId), anyList(), anyBoolean()
            )).thenReturn(List.of(101L));

            CalendarSnapshot snap = snapshotOf();

            Object placement = callPlaceOperation(
                    operationId, node,
                    LocalDate.of(2026, 10, 21),
                    new HashMap<>(),
                    maxLoadPercent, snap
            );

            // 100% > 80% → сдвиг на 22.10
            assertEquals(LocalDate.of(2026, 10, 22),
                    getPlacementDate(placement));
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

            CalendarSnapshot snap = snapshotOf();

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

            CalendarSnapshot snap = snapshotOf();

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

            CalendarSnapshot snap = snapshotOf();

            // Существующая занятость: 8ч в этот день (чужой заказ)
            Map<StageDayKey, BigDecimal> occupancy = new HashMap<>();
            occupancy.merge(new StageDayKey(operationId, LocalDate.of(2026, 10, 21)),
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

            CalendarSnapshot snap = snapshotOf();

            // 22.10 нет загрузки → помещается
            Map<StageDayKey, BigDecimal> occupancy = new HashMap<>();

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

            CalendarSnapshot snap = snapshotOf();

            Map<StageDayKey, BigDecimal> occupancy = new HashMap<>();

            // Первый экземпляр: 8/8 = 100%
            Object first = callPlaceOperation(
                    operationId, node,
                    LocalDate.of(2026, 10, 21),
                    occupancy,
                    maxLoadPercent, snap
            );
            assertEquals(LocalDate.of(2026, 10, 21), getPlacementDate(first));

            // Обновляем occupancy как это делает реальный алгоритм
            occupancy.merge(new StageDayKey(operationId, LocalDate.of(2026, 10, 21)),
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

            CalendarSnapshot snap = snapshotOf();

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
        @DisplayName("Все исполнители отсутствуют → условная мощность")
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

            CalendarSnapshot snap = new CalendarSnapshot(
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

            // power = 8 (условно, present=0), load = 100%
            assertEquals(LocalDate.of(2026, 10, 21),
                    getPlacementDate(placement));
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
            int maxLoadPercent = 80;

            TechProcessOperation node = techProcessOperationNode(operationId, new BigDecimal("8"));
            when(staffBindingRepository.findCapableEmployeeIds(
                    eq(operationId), anyList(), anyBoolean()
            )).thenReturn(List.of(101L));

            CalendarSnapshot snap = snapshotOf();

            // 21.10: 8ч, 22.10: 8ч, 23.10: 8ч
            Map<StageDayKey, BigDecimal> occupancy = new HashMap<>();
            occupancy.merge(new StageDayKey(operationId, LocalDate.of(2026, 10, 21)),
                    new BigDecimal("8"), BigDecimal::add);
            occupancy.merge(new StageDayKey(operationId, LocalDate.of(2026, 10, 22)),
                    new BigDecimal("8"), BigDecimal::add);
            occupancy.merge(new StageDayKey(operationId, LocalDate.of(2026, 10, 23)),
                    new BigDecimal("8"), BigDecimal::add);

            Object placement = callPlaceOperation(
                    operationId, node,
                    LocalDate.of(2026, 10, 21),
                    occupancy,
                    maxLoadPercent, snap
            );

            // 21: 16/8=200%, 22: 16/8=200%, 23: 16/8=200%, 26: 8/8=100% > 80
            // 26.10 — понедельник
            assertEquals(LocalDate.of(2026, 10, 26),
                    getPlacementDate(placement));
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

            CalendarSnapshot snap = snapshotOf();

            // 23.10 (ПТ) полностью загружена
            Map<StageDayKey, BigDecimal> occupancy = new HashMap<>();
            occupancy.merge(new StageDayKey(operationId, LocalDate.of(2026, 10, 23)),
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

            CalendarSnapshot snap = snapshotOf();

            // Существующая загрузка: 10ч из 16ч
            Map<StageDayKey, BigDecimal> occupancy = new HashMap<>();
            occupancy.merge(new StageDayKey(operationId, LocalDate.of(2026, 10, 21)),
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

            CalendarSnapshot snap = snapshotOf();

            Map<StageDayKey, BigDecimal> occupancy = new HashMap<>();
            occupancy.merge(new StageDayKey(operationId, LocalDate.of(2026, 10, 21)),
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

    private CalendarSnapshot snapshotOf() {
        return new CalendarSnapshot(
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
        node.setPredecessors(List.of());
        WorkPlace wp = new WorkPlace();
        wp.setId(1L);
        node.setWorkPlaces(List.of(wp));
        Operation op = new Operation();
        op.setId(id);
        node.setOperation(op);
        return node;
    }

    /**
     * Вызывает приватный метод placeOperation через reflection.
     */
    private Object callPlaceOperation(Long operationId, TechProcessOperation node,
                                      LocalDate earliest, Map<StageDayKey, BigDecimal> occupancy,
                                      int maxLoadPercent, CalendarSnapshot calendar)
            throws Exception {
        java.lang.reflect.Method m = OrderSchedulingService.class
                .getDeclaredMethod("placeOperation",
                        Long.class, TechProcessOperation.class,
                        LocalDate.class, Map.class, int.class,
                        OrderSchedulingService.CalendarSnapshot.class);
        m.setAccessible(true);
        return m.invoke(service,
                operationId, node, earliest,
                convertOccupancy(occupancy), maxLoadPercent, calendar);
    }

    private Map<Object, BigDecimal> convertOccupancy(Map<StageDayKey, BigDecimal> input) {
        Map<Object, BigDecimal> result = new HashMap<>();
        for (var entry : input.entrySet()) {
            result.put(new StageDayKeyWrapper(entry.getKey().operationId(),
                    entry.getKey().date()), entry.getValue());
        }
        return result;
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

    /** Ключ для occupancy-мапы (совпадает с внутренним StageDay). */
    private record StageDayKey(Long operationId, LocalDate date) {
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof StageDayKey that)) return false;
            return Objects.equals(operationId, that.operationId)
                    && Objects.equals(date, that.date);
        }

        @Override
        public int hashCode() {
            return Objects.hash(operationId, date);
        }
    }

    /** Обёртка для StageDay — reflection не видит приватный record. */
    private static class StageDayKeyWrapper {
        private final Long operationId;
        private final LocalDate date;

        StageDayKeyWrapper(Long operationId, LocalDate date) {
            this.operationId = operationId;
            this.date = date;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof StageDayKeyWrapper that)) return false;
            return Objects.equals(operationId, that.operationId)
                    && Objects.equals(date, that.date);
        }

        @Override
        public int hashCode() {
            return Objects.hash(operationId, date);
        }
    }
}
