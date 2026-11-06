package ru.company.production.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import ru.company.production.dto.GroupZnpPositions;
import ru.company.production.entity.ProductionOrderStatus;
import ru.company.production.entity.ScheduleRowStatus;
import ru.company.production.repository.AttachedCellView;
import ru.company.production.repository.OrderScheduleRowRepository;
import ru.company.production.repository.PendingDefectRepository;
import ru.company.production.repository.ProductionOrderListItemView;
import ru.company.production.repository.SerialProductionOrderRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Колонка «Где позиции»: позиция считается только там, где у экземпляра
 * есть незакрытая работа плана и внесённая складом ячейка, а позиция в
 * браке (заявка ждёт решения бюро) в счёт не входит.
 *
 * Подпись колонки — «подразделение (работа — N шт.)»: диспетчер ищет
 * глазами цех, а не наименование операции.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GroupZnpPositionsServiceTest {

    @Mock private OrderScheduleRowRepository scheduleRowRepository;
    @Mock private SerialProductionOrderRepository serialProductionOrderRepository;
    @Mock private PendingDefectRepository pendingDefectRepository;

    private GroupZnpPositionsService service;

    @BeforeEach
    void setUp() {
        service = new GroupZnpPositionsService(
                scheduleRowRepository,
                serialProductionOrderRepository,
                pendingDefectRepository
        );
    }

    @Test
    @DisplayName("Новый и закрытый заказ колонку не считают: запросов нет, прочерк")
    void skipsOrdersWithoutRunningStatus() {

        Map<Long, GroupZnpPositions> result = service.ofListItems(List.of(
                order(1L, ProductionOrderStatus.NEW),
                order(2L, ProductionOrderStatus.DONE),
                order(3L, ProductionOrderStatus.CANCELLED)
        ));

        assertTrue(result.isEmpty());
        verify(scheduleRowRepository, never())
                .findOpenPlanRowsOfProductionOrders(anyCollection());
    }

    @Test
    @DisplayName("Подпись: подразделение и работа; позиции считаются по внесённым ячейкам")
    void countsOnlyAttachedCells() {

        when(scheduleRowRepository.findOpenPlanRowsOfProductionOrders(anyCollection()))
                .thenReturn(List.of(
                        planRow(10L, 0, 1, "Цех подготовки производства", "Лакирование подсборок"),
                        planRow(10L, 1, 1, "Цех подготовки производства", "Лакирование подсборок"),
                        planRow(10L, 2, 1, "Цех подготовки производства", "Лакирование подсборок")
                ));
        when(serialProductionOrderRepository.findAttachedCellsByOrderIds(anyCollection()))
                .thenReturn(List.of(cell(10L, 1212, 22), cell(10L, 1212, 23)));
        when(pendingDefectRepository.findAwaitingDecisionFullNumbers())
                .thenReturn(Set.of());

        GroupZnpPositions positions = service
                .ofListItems(List.of(order(10L, ProductionOrderStatus.IN_WORK)))
                .get(10L);

        assertEquals("Цех подготовки производства", positions.unitName());
        assertEquals("Лакирование подсборок", positions.operationName());
        assertEquals(2L, positions.count());
        assertEquals("Цех подготовки производства (Лакирование подсборок — 2 шт.)",
                positions.label());
    }

    @Test
    @DisplayName("План без адреса подразделения: подпись остаётся только работой")
    void fallsBackToWorkWithoutUnit() {

        when(scheduleRowRepository.findOpenPlanRowsOfProductionOrders(anyCollection()))
                .thenReturn(List.of(planRow(15L, 0, 1, null, "Монтаж подсборок")));
        when(serialProductionOrderRepository.findAttachedCellsByOrderIds(anyCollection()))
                .thenReturn(List.of(cell(15L, 1212, 22)));
        when(pendingDefectRepository.findAwaitingDecisionFullNumbers())
                .thenReturn(Set.of());

        GroupZnpPositions positions = service
                .ofListItems(List.of(order(15L, ProductionOrderStatus.IN_WORK)))
                .get(15L);

        assertEquals("Монтаж подсборок — 1 шт.", positions.label());
    }

    @Test
    @DisplayName("Строка плана без адреса берёт подразделение самой операции")
    void takesUnitOfOperationWhenRowHasNone() {

        when(scheduleRowRepository.findOpenPlanRowsOfProductionOrders(anyCollection()))
                .thenReturn(List.of(new OrderScheduleRowRepository.OpenPlanRowView() {

                    @Override
                    public Long getOrderId() {
                        return 16L;
                    }

                    @Override
                    public Integer getInstanceIndex() {
                        return 0;
                    }

                    @Override
                    public Integer getSeq() {
                        return 1;
                    }

                    @Override
                    public String getOperationName() {
                        return "Монтаж подсборок";
                    }

                    @Override
                    public String getUnitName() {
                        return null;
                    }

                    @Override
                    public String getOperationUnitName() {
                        return "Цех монтажа";
                    }
                }));
        when(serialProductionOrderRepository.findAttachedCellsByOrderIds(anyCollection()))
                .thenReturn(List.of(cell(16L, 1212, 22)));
        when(pendingDefectRepository.findAwaitingDecisionFullNumbers())
                .thenReturn(Set.of());

        GroupZnpPositions positions = service
                .ofListItems(List.of(order(16L, ProductionOrderStatus.IN_WORK)))
                .get(16L);

        assertEquals("Цех монтажа (Монтаж подсборок — 1 шт.)", positions.label());
    }

    @Test
    @DisplayName("Ячейка в браке не ждёт работу: её снимают с комплектования")
    void ignoresPositionInDefect() {

        when(scheduleRowRepository.findOpenPlanRowsOfProductionOrders(anyCollection()))
                .thenReturn(List.of(
                        planRow(11L, 0, 1, "Цех", "Монтаж подсборок"),
                        planRow(11L, 1, 1, "Цех", "Монтаж подсборок")
                ));
        when(serialProductionOrderRepository.findAttachedCellsByOrderIds(anyCollection()))
                .thenReturn(List.of(cell(11L, 1212, 22), cell(11L, 1212, 23)));
        when(pendingDefectRepository.findAwaitingDecisionFullNumbers())
                .thenReturn(Set.of("№ 1212-00023"));

        GroupZnpPositions positions = service
                .ofListItems(List.of(order(11L, ProductionOrderStatus.IN_WORK)))
                .get(11L);

        assertEquals(1L, positions.count());
    }

    @Test
    @DisplayName("Ранняя работа маршрута подписывает экземпляр, а не последняя")
    void takesEarliestOpenWorkOfInstance() {

        when(scheduleRowRepository.findOpenPlanRowsOfProductionOrders(anyCollection()))
                .thenReturn(List.of(
                        planRow(12L, 0, 1, "Цех", "Монтаж подсборок"),
                        planRow(12L, 0, 2, "Цех", "Контроль ОТК")
                ));
        when(serialProductionOrderRepository.findAttachedCellsByOrderIds(anyCollection()))
                .thenReturn(List.of(cell(12L, 1212, 22)));
        when(pendingDefectRepository.findAwaitingDecisionFullNumbers())
                .thenReturn(Set.of());

        GroupZnpPositions positions = service
                .ofListItems(List.of(order(12L, ProductionOrderStatus.IN_WORK)))
                .get(12L);

        assertEquals("Монтаж подсборок", positions.operationName());
    }

    @Test
    @DisplayName("Разброска по работам: в подписи ведущая, остальные — в подсказке")
    void spreadsOverOperationsWithHint() {

        when(scheduleRowRepository.findOpenPlanRowsOfProductionOrders(anyCollection()))
                .thenReturn(List.of(
                        planRow(13L, 0, 1, "Цех монтажа", "Монтаж подсборок"),
                        planRow(13L, 1, 1, "Цех монтажа", "Монтаж подсборок"),
                        planRow(13L, 2, 1, "Бюро качества", "Контроль ОТК")
                ));
        when(serialProductionOrderRepository.findAttachedCellsByOrderIds(anyCollection()))
                .thenReturn(List.of(
                        cell(13L, 1212, 22),
                        cell(13L, 1212, 23),
                        cell(13L, 1212, 24)
                ));
        when(pendingDefectRepository.findAwaitingDecisionFullNumbers())
                .thenReturn(Set.of());

        GroupZnpPositions positions = service
                .ofListItems(List.of(order(13L, ProductionOrderStatus.IN_WORK)))
                .get(13L);

        assertEquals("Цех монтажа (Монтаж подсборок — 2 шт.)", positions.label());
        assertEquals("Позиции ждут работ: Цех монтажа (Монтаж подсборок) — 2; "
                + "Бюро качества (Контроль ОТК) — 1", positions.title());
    }

    @Test
    @DisplayName("Все работы проведены — колонка пустая, заказа в выдаче нет")
    void emptyWhenEverythingClosed() {

        when(scheduleRowRepository.findOpenPlanRowsOfProductionOrders(anyCollection()))
                .thenReturn(List.of());
        when(serialProductionOrderRepository.findAttachedCellsByOrderIds(anyCollection()))
                .thenReturn(List.of(cell(14L, 1212, 22)));
        when(pendingDefectRepository.findAwaitingDecisionFullNumbers())
                .thenReturn(Set.of());

        assertTrue(service
                .ofListItems(List.of(order(14L, ProductionOrderStatus.IN_WORK)))
                .isEmpty());
    }

    @Test
    @DisplayName("Пустая сводка даёт прочерк и не плодит подсказку")
    void emptySummarySignsDash() {

        assertTrue(GroupZnpPositions.EMPTY.isEmpty());
        assertEquals("—", GroupZnpPositions.EMPTY.label());
        assertNull(GroupZnpPositions.EMPTY.title());
    }

    @Test
    @DisplayName("Заказ только взят в работу: ячеек нет — первая работа без счёта")
    void signsFirstWorkWhenNoCellsYet() {

        when(scheduleRowRepository.findOpenPlanRowsOfProductionOrders(anyCollection()))
                .thenReturn(List.of(
                        planRow(16L, 0, 1, "Цех подготовки производства", "Монтаж подсборок"),
                        planRow(16L, 0, 2, "Цех сборки приборов", "Контроль ОТС")
                ));
        when(serialProductionOrderRepository.findAttachedCellsByOrderIds(anyCollection()))
                .thenReturn(List.of());
        when(pendingDefectRepository.findAwaitingDecisionFullNumbers())
                .thenReturn(Set.of());

        GroupZnpPositions positions = service
                .ofListItems(List.of(order(16L, ProductionOrderStatus.IN_WORK)))
                .get(16L);

        assertEquals("Цех подготовки производства (Монтаж подсборок)",
                positions.label());
        assertEquals(0L, positions.count());
    }

    @Test
    @DisplayName("Срок по плану: дата окончания в формате дд.мм.гггг")
    void formatsPlanDueDate() {

        when(scheduleRowRepository.findPlanEndDatesOfProductionOrders(
                any(ScheduleRowStatus.class), anyCollection()))
                .thenReturn(List.of(
                        planEndDate(17L, LocalDate.of(2026, 11, 20))
                ));

        assertEquals(
                Map.of(17L, "20.11.2026"),
                service.planDueDates(
                        List.of(order(17L, ProductionOrderStatus.IN_WORK))
                )
        );
    }

    @Test
    @DisplayName("Плана нет — срока в выдаче нет, колонка остаётся на карточке")
    void planDueDatesSkipsOrdersWithoutPlan() {

        when(scheduleRowRepository.findPlanEndDatesOfProductionOrders(
                any(ScheduleRowStatus.class), anyCollection()))
                .thenReturn(List.of());

        assertTrue(service.planDueDates(
                List.of(order(18L, ProductionOrderStatus.NEW))).isEmpty());
        assertTrue(service.planDueDates(List.of()).isEmpty());
    }

    /* ---------- строки списка ---------- */

    private static ProductionOrderListItemView order(
            Long id,
            ProductionOrderStatus status) {

        ProductionOrderListItemView row = mock(ProductionOrderListItemView.class);

        when(row.getId()).thenReturn(id);
        when(row.getStatus()).thenReturn(status);

        return row;
    }

    /* ---------- строки плана и матрицы ---------- */

    private static OrderScheduleRowRepository.OpenPlanRowView planRow(
            Long orderId,
            int instanceIndex,
            int seq,
            String unitName,
            String operationName) {

        return new OrderScheduleRowRepository.OpenPlanRowView() {

            @Override
            public Long getOrderId() {
                return orderId;
            }

            @Override
            public Integer getInstanceIndex() {
                return instanceIndex;
            }

            @Override
            public Integer getSeq() {
                return seq;
            }

            @Override
            public String getOperationName() {
                return operationName;
            }

            @Override
            public String getUnitName() {
                return unitName;
            }

            @Override
            public String getOperationUnitName() {
                return unitName;
            }
        };
    }

    private static OrderScheduleRowRepository.PlanEndDateView planEndDate(
            Long orderId,
            LocalDate endDate) {

        return new OrderScheduleRowRepository.PlanEndDateView() {

            @Override
            public Long getOrderId() {
                return orderId;
            }

            @Override
            public LocalDate getEndDate() {
                return endDate;
            }
        };
    }

    private static AttachedCellView cell(
            Long orderId,
            Integer classifierCode,
            Integer serial) {

        return new AttachedCellView() {

            @Override
            public Long getOrderId() {
                return orderId;
            }

            @Override
            public Long getSerialId() {
                return (long) serial;
            }

            @Override
            public Integer getClassifierCode() {
                return classifierCode;
            }

            @Override
            public Integer getSerial() {
                return serial;
            }

            @Override
            public String getClassifierName() {
                return "Ячейка 19''";
            }

            @Override
            public String getSensorName() {
                return null;
            }
        };
    }
}
