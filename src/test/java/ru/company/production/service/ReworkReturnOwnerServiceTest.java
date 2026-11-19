package ru.company.production.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.company.production.entity.ControlReturn;
import ru.company.production.entity.CustomerOrder;
import ru.company.production.entity.OrderScheduleRow;
import ru.company.production.entity.OrganizationUnit;
import ru.company.production.entity.ProductionService;
import ru.company.production.entity.ReworkReturn;
import ru.company.production.entity.ReworkReturn.ReturnType;
import ru.company.production.repository.ControlReturnRepository;
import ru.company.production.repository.OrderScheduleRowRepository;
import ru.company.production.repository.ReworkReturnRepository;
import ru.company.production.service.ReworkReturnOwnerService.Ownership;
import ru.company.production.service.ReworkReturnOwnerService.Predecessor;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

/**
 * Владение изделием на доработку — последний участок перед контролем.
 *
 * <p>Правило определяет, какому цеху показывать карточку и куда
 * изделие можно отправить назад. Ошибка здесь дороже ошибки раздела:
 * карточка у не-владельца снимает чужое проведение, а потерянный
 * владелец оставляет изделие в разделе навсегда.
 *
 * <p>Проверяются границы участка (разделители — склад и контроль),
 * пропуск уже отправленных назад участков, захват изделия внутренней
 * переделкой и тип предшественника для кнопки отправки.
 */
@ExtendWith(MockitoExtension.class)
class ReworkReturnOwnerServiceTest {

    @Mock private OrderScheduleRowRepository scheduleRowRepository;
    @Mock private ControlReturnRepository controlReturnRepository;
    @Mock private ReworkReturnRepository reworkReturnRepository;

    private ReworkReturnOwnerService service;

    @BeforeEach
    void setUp() {
        service = new ReworkReturnOwnerService(
                scheduleRowRepository,
                controlReturnRepository,
                reworkReturnRepository
        );
    }

    /* ============================================================
       ДАННЫЕ
       ============================================================ */

    private static final long ORDER_ID = 31L;

    private static ProductionService productionService(String code) {
        ProductionService svc = new ProductionService();
        svc.setCode(code);
        return svc;
    }

    private static OrganizationUnit unit(long id, String serviceCode) {
        OrganizationUnit unit = new OrganizationUnit();
        unit.setId(id);
        unit.setService(productionService(serviceCode));
        return unit;
    }

    private static OrganizationUnit workshopUnit(long id) {
        return unit(id, "SDP");
    }

    private static CustomerOrder order() {
        CustomerOrder order = new CustomerOrder();
        order.setId(ORDER_ID);
        return order;
    }

    private static OrderScheduleRow row(long id,
                                        int seq,
                                        OrganizationUnit unit) {
        OrderScheduleRow row = new OrderScheduleRow();
        row.setId(id);
        row.setSeq(seq);
        row.setCustomerOrder(order());
        row.setItemPosition(0);
        row.setInstanceIndex(0);
        row.setOrganizationUnit(unit);
        return row;
    }

    private static ControlReturn signal(OrderScheduleRow row) {
        ControlReturn ret = new ControlReturn();
        ret.setScheduleRow(row);
        ret.setReason("дефект");
        return ret;
    }

    private static ReworkReturn rework(OrderScheduleRow row,
                                       ReturnType type) {
        ReworkReturn ret = new ReworkReturn();
        ret.setScheduleRow(row);
        ret.setReturnType(type);
        return ret;
    }

    /*
     * Маршрут экземпляра отдаётся репозиторием; сигналы и возвраты —
     * своими запросами сервиса. Заглушки настраиваются на весь маршрут
     * разом: сервис читает их по id строк маршрута.
     */
    private void routeOf(List<OrderScheduleRow> route,
                         List<ControlReturn> signals,
                         List<ReworkReturn> reworks) {

        when(scheduleRowRepository.findFullRouteOfInstance(
                ORDER_ID, 0, 0)).thenReturn(route);
        when(controlReturnRepository.findOpenByRowIds(anyList()))
                .thenReturn(signals);
        when(reworkReturnRepository.findOpenByRowIds(anyList()))
                .thenReturn(reworks);
    }

    /* ============================================================
       ВЛАДЕЛЕЦ
       ============================================================ */

    @Test
    @DisplayName("Владелец — последний участок перед контролем, а не весь цех")
    void ownerIsLastSegmentBeforeControl() {

        /* 1-цех1 / 2-цех2 / 3,4-цех1 / 5-БТК: сигнал на строках 3,4. */
        OrderScheduleRow a = row(1L, 1, workshopUnit(10L));
        OrderScheduleRow b = row(2L, 2, workshopUnit(20L));
        OrderScheduleRow c = row(3L, 3, workshopUnit(10L));
        OrderScheduleRow d = row(4L, 4, workshopUnit(10L));
        OrderScheduleRow control = row(5L, 5, unit(30L, "SQ"));

        routeOf(
                List.of(a, b, c, d, control),
                List.of(signal(c), signal(d)),
                List.of()
        );

        Ownership ownership = service.resolve(a);

        /*
         * Карточка — участку 3,4 цеха 10: он держал изделие
         * непосредственно до контроля. Ранний участок того же цеха
         * (строка 1) в неё не входит.
         */
        assertTrue(ownership.isOwnedBy(List.of(10L)));
        assertEquals(List.of(c, d), ownership.getOwnerRows());
        assertEquals(10L, ownership.getOwnerUnitId());
    }

    @Test
    @DisplayName("Предшественник — цех: изделие можно отправить назад")
    void predecessorWorkshopCanReceive() {

        OrderScheduleRow a = row(1L, 1, workshopUnit(10L));
        OrderScheduleRow b = row(2L, 2, workshopUnit(20L));
        OrderScheduleRow c = row(3L, 3, workshopUnit(30L));

        routeOf(List.of(a, b, c), List.of(signal(c)), List.of());

        Ownership ownership = service.resolve(a);

        assertEquals(Predecessor.WORKSHOP, ownership.getPredecessor());
        assertEquals(20L, ownership.getPredecessorUnitId());
        assertTrue(ownership.canSendToPredecessor());
    }

    @Test
    @DisplayName("Предшественник — склад: отправить назад некому")
    void warehousePredecessorBlocksSending() {

        OrderScheduleRow storage = row(1L, 1, unit(10L, "SGD"));
        OrderScheduleRow shop = row(2L, 2, workshopUnit(20L));

        routeOf(List.of(storage, shop), List.of(signal(shop)), List.of());

        Ownership ownership = service.resolve(shop);

        assertEquals(Predecessor.WAREHOUSE, ownership.getPredecessor());
        assertFalse(ownership.canSendToPredecessor());
        assertTrue(ownership.predecessorIsWarehouse());
    }

    @Test
    @DisplayName("Владелец первый — предшественника нет")
    void firstSegmentHasNoPredecessor() {

        OrderScheduleRow first = row(1L, 1, workshopUnit(10L));
        OrderScheduleRow control = row(2L, 2, unit(30L, "SQ"));

        routeOf(List.of(first, control), List.of(signal(first)), List.of());

        Ownership ownership = service.resolve(first);

        assertTrue(ownership.isOwnedBy(List.of(10L)));
        assertEquals(Predecessor.NONE, ownership.getPredecessor());
    }

    /* ============================================================
       ОЧЕРЕДЬ РЕШЕНИЙ
       ============================================================ */

    @Test
    @DisplayName("Участок уже отправил изделие назад — владеет предшественник")
    void forwardedSegmentYieldsOwnership() {

        OrderScheduleRow a = row(1L, 1, workshopUnit(10L));
        OrderScheduleRow b = row(2L, 2, workshopUnit(20L));
        OrderScheduleRow c = row(3L, 3, workshopUnit(30L));

        /*
         * Цех 30 отправил изделие в предшественника: его участок
         * вычеркнут, решение переходит к цеху 20. Сигнал БТК на
         * строке цеха 20 — цех 30 закрыл свой сигнал решением об
         * отправке назад, а сигнал цеха 20 остался открыт.
         */
        routeOf(
                List.of(a, b, c),
                List.of(signal(b)),
                List.of(rework(c, ReturnType.PREDECESSOR))
        );

        Ownership ownership = service.resolve(a);

        assertTrue(ownership.isOwnedBy(List.of(20L)));
        assertEquals(List.of(b), ownership.getOwnerRows());
    }

    @Test
    @DisplayName("Участок забрал изделие на внутреннюю переделку — карточки нет")
    void internalReworkSuspendsCard() {

        OrderScheduleRow a = row(1L, 1, workshopUnit(10L));
        OrderScheduleRow b = row(2L, 2, workshopUnit(20L));

        routeOf(
                List.of(a, b),
                List.of(signal(b)),
                List.of(rework(b, ReturnType.INTERNAL))
        );

        Ownership ownership = service.resolve(a);

        /*
         * Цех 20 уже решил переделывать сам: карточка исчезает у всех,
         * и назад очередь не двигается — иначе цех 10 получил бы
         * изделие, о котором сосед ещё не отказался.
         */
        assertFalse(ownership.hasOwner());
        assertTrue(ownership.isTakenInternally());
    }

    @Test
    @DisplayName("Сигналов нет — владельца нет")
    void noSignalsNoOwner() {

        OrderScheduleRow a = row(1L, 1, workshopUnit(10L));
        OrderScheduleRow b = row(2L, 2, workshopUnit(20L));

        routeOf(List.of(a, b), List.of(), List.of());

        assertFalse(service.resolve(a).hasOwner());
    }

    @Test
    @DisplayName("Пустой маршрут — владельца нет")
    void emptyRouteNoOwner() {

        when(scheduleRowRepository.findFullRouteOfInstance(
                ORDER_ID, 0, 0)).thenReturn(List.of());

        assertFalse(service.resolve(row(1L, 1, workshopUnit(10L))).hasOwner());
    }

    @Test
    @DisplayName("Строка без основания — маршрут из одной строки")
    void standaloneRowRoutesItself() {

        OrderScheduleRow lone = new OrderScheduleRow();
        lone.setId(7L);
        lone.setSeq(1);
        lone.setOrganizationUnit(workshopUnit(10L));

        /*
         * Строка вне заказа и вне заказа на производство — отдельная
         * работа: её маршрут — она сама, и владение считается по ней.
         */
        when(controlReturnRepository.findOpenByRowIds(anyList()))
                .thenReturn(new ArrayList<>(List.of(signal(lone))));
        when(reworkReturnRepository.findOpenByRowIds(anyList()))
                .thenReturn(List.of());

        Ownership ownership = service.resolve(lone);

        assertTrue(ownership.isOwnedBy(List.of(10L)));
        assertEquals(List.of(lone), ownership.getOwnerRows());
    }
}
