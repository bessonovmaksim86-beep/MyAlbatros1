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
import ru.company.production.entity.ControlReturn;
import ru.company.production.entity.CustomerOrder;
import ru.company.production.entity.Operation;
import ru.company.production.entity.OperationName;
import ru.company.production.entity.OrderScheduleRow;
import ru.company.production.entity.OrganizationUnit;
import ru.company.production.entity.ReworkReturn;
import ru.company.production.entity.ReworkReturn.ReturnType;
import ru.company.production.entity.ReworkReturn.ReworkStatus;
import ru.company.production.entity.Role;
import ru.company.production.entity.WorkPlace;
import ru.company.production.repository.AttachedCellView;
import ru.company.production.repository.ControlReturnRepository;
import ru.company.production.repository.OrderScheduleRowRepository;
import ru.company.production.repository.ProductionOrderSerialProjection;
import ru.company.production.repository.ReworkReturnRepository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Возврат на доработку — решение начальника цеха.
 *
 * <p>Смысл раздела в границе переделки: БТК возвращает изделие сигналом,
 * а цех сам решает, какие свои операции перепроводить. Ошибка здесь
 * заметна в плане — сняли лишнее, и закрытая работа пропала; сняли
 * мало, и следующая по маршруту осталась разблокированной над
 * недоделанным изделием.
 *
 * <p>Поэтому проверяются четыре вещи: снятие касается ровно отмеченных
 * строк, каскад забирает только идущие после них, снятая работа
 * возвращается в распределение новой датой (прежняя остаётся в
 * истории), и сигнал БТК закрывается решением цеха — иначе изделие
 * навсегда осталось бы в разделе.
 *
 * <p>Календарь переделки берётся настоящий, а не мок: {@link
 * WorkingDaysCalculator} не имеет зависимостей, а даты переноса —
 * ровно то, что тест и обязан утверждать.
 */
@ExtendWith(MockitoExtension.class)
class ReworkReturnServiceTest {

    @Mock private ReworkReturnRepository reworkReturnRepository;
    @Mock private ControlReturnRepository controlReturnRepository;
    @Mock private OrderScheduleRowRepository scheduleRowRepository;
    @Mock private WorkshopStaffService staffService;
    @Mock private WorkshopDistributionSerialService serialService;

    private ReworkReturnService service;

    @BeforeEach
    void setUp() {
        service = new ReworkReturnService(
                reworkReturnRepository,
                controlReturnRepository,
                scheduleRowRepository,
                staffService,
                serialService,
                new WorkingDaysCalculator()
        );
    }

    /* ============================================================
       ДАННЫЕ
       ============================================================ */

    private static final long UNIT_ID = 7L;
    private static final long ORDER_ID = 31L;

    /** Дата плана принятой работы — та, что возврат обязан сохранить. */
    private static final LocalDate ACCEPTED_DATE =
            LocalDate.of(2026, 11, 2);

    private static AppUser user(Role role) {
        AppUser appUser = new AppUser();
        appUser.setId(1L);
        appUser.setUsername("sdp-01");
        appUser.setFullName("Петров П. П.");
        appUser.setRole(role);
        return appUser;
    }

    /**
     * Строка маршрута цеха: тот же экземпляр, что в тесте возврата БТК,
     * но с операцией — по ней карточка показывает наименование.
     */
    private static OrderScheduleRow row(long id,
                                        int seq,
                                        CustomerOrder order,
                                        OrganizationUnit unit,
                                        boolean completed) {
        OperationName name = new OperationName();
        name.setName("Сборка узла");

        Operation operation = new Operation();
        operation.setId(50L + seq);
        operation.setOperationName(name);

        OrderScheduleRow row = new OrderScheduleRow();
        row.setId(id);
        row.setSeq(seq);
        row.setOperation(operation);
        row.setCustomerOrder(order);
        row.setItemPosition(1);
        row.setInstanceIndex(0);
        row.setOrganizationUnit(unit);
        row.setPlannedDate(ACCEPTED_DATE);

        if (completed) {
            row.setCompletedAt(LocalDateTime.of(2026, 11, 2, 10, 0));
            row.setCompletedBy(user(Role.SDP_SA_EXECUTOR));
        }

        return row;
    }

    /** Открытый сигнал БТК по строке — основание карточки. */
    private static ControlReturn controlReturn(OrderScheduleRow row,
                                               String reason) {
        ControlReturn ret = new ControlReturn();
        ret.setScheduleRow(row);
        ret.setReason(reason);
        ret.setControlOperation("Контроль ячеек из производства");
        ret.setReturnedBy(user(Role.BTK_MASTER));
        ret.setReturnedAt(LocalDateTime.of(2026, 11, 3, 9, 0));
        return ret;
    }

    /**
     * Пара «заказ + подразделение», общая для всех карточек теста:
     * один экземпляр, несколько строк его маршрута.
     */
    private static CustomerOrder order() {
        CustomerOrder order = new CustomerOrder();
        order.setId(ORDER_ID);
        return order;
    }

    private static OrganizationUnit unit() {
        OrganizationUnit unit = new OrganizationUnit();
        unit.setId(UNIT_ID);
        return unit;
    }

    /* ============================================================
       ВОЗВРАТ НА ДОРАБОТКУ В СВОЙ ЦЕХ
       ============================================================ */

    @Nested
    @DisplayName("Отметить операции для переделки")
    class MarkForRework {

        @Test
        @DisplayName("Снимает проведение ровно с отмеченных строк")
        void clearsOnlyChosenRows() {

            OrderScheduleRow chosen = row(100L, 5, order(), unit(), true);
            OrderScheduleRow later = row(101L, 6, order(), unit(), true);

            AppUser master = user(Role.SDP_SA_MASTER);

            when(staffService.unitScope(master)).thenReturn(List.of(UNIT_ID));
            when(scheduleRowRepository.findAllById(List.of(100L)))
                    .thenReturn(List.of(chosen));
            when(controlReturnRepository.findOpenByRowIds(any()))
                    .thenReturn(List.of());

            String message = service.markForRework(
                    List.of(100L), "Не пропаян разъём X2", master);

            assertNull(chosen.getCompletedAt());
            assertNull(chosen.getCompletedBy());

            /*
             * Маршрут экземпляра запрос не вернул — каскаду цеплять
             * нечего, и идущая после операция остаётся закрытой:
             * переделка снимает только те работы, которые правда идут
             * после отмеченной в техпроцессе этого изделия.
             */
            assertNotNull(later.getCompletedAt());

            assertEquals("Возвращено на доработку: 1", message);
        }

        @Test
        @DisplayName("Заводит возврат на каждую снятую строку")
        void writesReturnPerClearedRow() {

            OrderScheduleRow chosen = row(100L, 5, order(), unit(), true);

            AppUser master = user(Role.SDP_SA_MASTER);

            when(staffService.unitScope(master)).thenReturn(List.of(UNIT_ID));
            when(scheduleRowRepository.findAllById(List.of(100L)))
                    .thenReturn(List.of(chosen));
            when(controlReturnRepository.findOpenByRowIds(any()))
                    .thenReturn(List.of());

            service.markForRework(List.of(100L), "  Перепутать маркировку  ", master);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<ReworkReturn>> captor =
                    ArgumentCaptor.forClass(List.class);

            verify(reworkReturnRepository).saveAll(captor.capture());

            List<ReworkReturn> returns = captor.getValue();

            assertEquals(1, returns.size());

            /*
             * Причина без обрамляющих пробелов: её читает исполнитель,
             * и ведущие пробелы ломают сравнение причин в истории.
             */
            assertEquals("Перепутать маркировку", returns.get(0).getReason());
            assertEquals(ReturnType.INTERNAL, returns.get(0).getReturnType());
            assertEquals(ReworkStatus.IN_REWORK, returns.get(0).getStatus());
            assertEquals(master, returns.get(0).getReturnedBy());
            assertNotNull(returns.get(0).getReturnedAt());
            assertNull(returns.get(0).getResolvedAt());
            assertEquals(100L, returns.get(0).getScheduleRow().getId());
        }

        @Test
        @DisplayName("Идущие после проведённые строки снимаются всегда")
        void cascadeClearsFollowingCompletedRows() {

            OrderScheduleRow earlier = row(99L, 4, order(), unit(), true);
            OrderScheduleRow chosen = row(100L, 5, order(), unit(), true);
            OrderScheduleRow later = row(101L, 6, order(), unit(), true);
            OrderScheduleRow open = row(102L, 7, order(), unit(), false);

            AppUser master = user(Role.SDP_SA_MASTER);

            when(staffService.unitScope(master)).thenReturn(List.of(UNIT_ID));
            when(scheduleRowRepository.findAllById(List.of(100L)))
                    .thenReturn(List.of(chosen));
            when(scheduleRowRepository.findRowsOfInstanceInUnits(
                    ORDER_ID, 1, 0, List.of(UNIT_ID)))
                    .thenReturn(List.of(earlier, chosen, later, open));
            when(controlReturnRepository.findOpenByRowIds(any()))
                    .thenReturn(List.of());

            String message = service.markForRework(
                    List.of(100L), "Отклонение по параметру", master);

            /*
             * Каскад тянет только идущие после и только проведённые:
             * переделанная работа не может оставаться под закрытой,
             * а незакрытую снимать нечего.
             */
            assertNull(chosen.getCompletedAt());
            assertNull(later.getCompletedAt());

            /*
             * Строка до отмеченной не трогается: её цех сдал без
             * замечаний, и переделка назад по маршруту бессмысленна.
             */
            assertNotNull(earlier.getCompletedAt());

            assertEquals("Возвращено на доработку: 2", message);
        }

        @Test
        @DisplayName("Причина берётся из шапки возврата, а не с формы")
        void reasonComesFromControlReturn() {

            OrderScheduleRow chosen = row(100L, 5, order(), unit(), true);

            AppUser master = user(Role.SDP_SA_MASTER);

            when(staffService.unitScope(master)).thenReturn(List.of(UNIT_ID));
            when(scheduleRowRepository.findAllById(List.of(100L)))
                    .thenReturn(List.of(chosen));
            when(controlReturnRepository.findOpenByRowIds(any()))
                    .thenReturn(List.of(
                            controlReturn(chosen, "Не пропаян разъём X2")));

            /*
             * Поле причины на экране убрано, поэтому параметр пуст:
             * сервер обязан подставить причину того возврата БТК, ради
             * которого карточка и показана.
             */
            service.markForRework(List.of(100L), null, master);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<ReworkReturn>> captor =
                    ArgumentCaptor.forClass(List.class);

            verify(reworkReturnRepository).saveAll(captor.capture());

            assertEquals(
                    "Не пропаян разъём X2",
                    captor.getValue().get(0).getReason()
            );
        }

        @Test
        @DisplayName("Нет причины ни в запросе, ни в возврате — отказ без записей")
        void missingReasonRejectedBeforeWrites() {

            OrderScheduleRow chosen = row(100L, 5, order(), unit(), true);

            AppUser master = user(Role.SDP_SA_MASTER);

            when(staffService.unitScope(master)).thenReturn(List.of(UNIT_ID));
            when(scheduleRowRepository.findAllById(List.of(100L)))
                    .thenReturn(List.of(chosen));
            when(controlReturnRepository.findOpenByRowIds(any()))
                    .thenReturn(List.of());

            assertThrows(IllegalArgumentException.class,
                    () -> service.markForRework(List.of(100L), "   ", master));

            verify(scheduleRowRepository, never()).save(any());
            verifyNoInteractions(reworkReturnRepository);
        }

        @Test
        @DisplayName("Снятая строка уходит в распределение новой датой, прежняя — в истории")
        void clearedRowGoesBackToDistributionWithNewDate() {

            OrderScheduleRow chosen = row(100L, 5, order(), unit(), true);
            chosen.setExecutor(user(Role.SDP_SA_EXECUTOR));
            chosen.setWorkPlace(new WorkPlace());

            AppUser master = user(Role.SDP_SA_MASTER);

            when(staffService.unitScope(master)).thenReturn(List.of(UNIT_ID));
            when(scheduleRowRepository.findAllById(List.of(100L)))
                    .thenReturn(List.of(chosen));
            when(controlReturnRepository.findOpenByRowIds(any()))
                    .thenReturn(List.of());

            service.markForRework(List.of(100L), "Прозвон не прошёл", master);

            /*
             * Закрепление снято: работа, с которой сняли факт,
             * возвращается мастеру — иначе она осталась бы «за своим
             * исполнителем» и в очередь распределения не попала.
             */
            assertNull(chosen.getExecutor());
            assertNull(chosen.getWorkPlace());

            /*
             * Дата плана — ближайший рабочий день, а не дата принятой
             * работы: под прошедшей датой строка выпала бы из горизонта.
             */
            LocalDate expected =
                    new WorkingDaysCalculator().nextWorkingDay(LocalDate.now());

            assertEquals(expected, chosen.getPlannedDate());
            assertTrue(expected.isAfter(ACCEPTED_DATE));

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<ReworkReturn>> captor =
                    ArgumentCaptor.forClass(List.class);

            verify(reworkReturnRepository).saveAll(captor.capture());

            /* Прежняя дата сохранена в возврате — она видна в истории. */
            assertEquals(ACCEPTED_DATE, captor.getValue().get(0).getPreviousPlannedDate());
        }

        @Test
        @DisplayName("Сигнал БТК закрывается — карточка уходит из раздела")
        void controlReturnIsClosedByDecision() {

            OrderScheduleRow chosen = row(100L, 5, order(), unit(), true);
            ControlReturn signal = controlReturn(chosen, "Не пропаян разъём X2");

            AppUser master = user(Role.SDP_SA_MASTER);

            when(staffService.unitScope(master)).thenReturn(List.of(UNIT_ID));
            when(scheduleRowRepository.findAllById(List.of(100L)))
                    .thenReturn(List.of(chosen));
            when(controlReturnRepository.findOpenByRowIds(any()))
                    .thenReturn(List.of(signal));

            service.markForRework(List.of(100L), null, master);

            assertNotNull(signal.getResolvedAt());
            assertEquals(master, signal.getResolvedBy());

            verify(controlReturnRepository).saveAll(List.of(signal));
        }

        @Test
        @DisplayName("Отметок нет — отказ")
        void emptySelectionRejected() {

            AppUser master = user(Role.SDP_SA_MASTER);

            assertThrows(IllegalArgumentException.class,
                    () -> service.markForRework(List.of(), "причина", master));

            verifyNoInteractions(reworkReturnRepository, scheduleRowRepository);
        }

        @Test
        @DisplayName("Исполнитель — отказ: раздел принадлежит руководителю")
        void executorRejected() {

            AppUser executor = user(Role.SDP_SA_EXECUTOR);

            assertThrows(IllegalStateException.class,
                    () -> service.markForRework(List.of(100L), "причина", executor));

            verifyNoInteractions(
                    reworkReturnRepository,
                    scheduleRowRepository,
                    staffService
            );
        }

        @Test
        @DisplayName("Чужое подразделение — отказ")
        void foreignUnitRejected() {

            OrganizationUnit foreign = new OrganizationUnit();
            foreign.setId(9L);

            OrderScheduleRow chosen = row(100L, 5, order(), foreign, true);

            AppUser master = user(Role.SDP_SA_MASTER);

            when(staffService.unitScope(master)).thenReturn(List.of(UNIT_ID));
            when(scheduleRowRepository.findAllById(List.of(100L)))
                    .thenReturn(List.of(chosen));

            assertThrows(IllegalStateException.class,
                    () -> service.markForRework(List.of(100L), "причина", master));

            verifyNoInteractions(reworkReturnRepository);
        }

        @Test
        @DisplayName("Строка исчезла из плана — отказ")
        void missingRowRejected() {

            AppUser master = user(Role.SDP_SA_MASTER);

            when(scheduleRowRepository.findAllById(List.of(100L, 101L)))
                    .thenReturn(List.of());

            assertThrows(IllegalArgumentException.class,
                    () -> service.markForRework(
                            List.of(100L, 101L), "причина", master));

            verifyNoInteractions(reworkReturnRepository, staffService);
        }
    }

    /* ============================================================
       ОТПРАВКА В ПРЕДШЕСТВУЮЩИЙ ЦЕХ
       ============================================================ */

    @Nested
    @DisplayName("Отправить в предшествующий цех")
    class SendToPredecessor {

        @Test
        @DisplayName("Снимает проведённые строки своего цеха")
        void clearsCompletedRowsOfOwnUnits() {

            OrderScheduleRow chosen = row(100L, 5, order(), unit(), true);
            OrderScheduleRow sibling = row(101L, 6, order(), unit(), true);
            OrderScheduleRow open = row(102L, 7, order(), unit(), false);

            AppUser master = user(Role.SDP_SA_MASTER);

            when(staffService.unitScope(master)).thenReturn(List.of(UNIT_ID));
            when(scheduleRowRepository.findAllById(List.of(100L)))
                    .thenReturn(List.of(chosen));
            when(scheduleRowRepository.findRowsOfInstanceInUnits(
                    ORDER_ID, 1, 0, List.of(UNIT_ID)))
                    .thenReturn(List.of(chosen, sibling, open));

            String message = service.sendToPredecessor(
                    List.of(100L), "Изделие не годится", master);

            /*
             * Отмечена одна операция, а снимаются все проведённые строки
             * экземпляра: изделие уходит назад по маршруту, и своему цеху
             * здесь ничего не оставляют.
             */
            assertNull(chosen.getCompletedAt());
            assertNull(sibling.getCompletedAt());

            assertEquals("Отправлено в предшествующий цех: 1, снято проведений: 2",
                    message);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<ReworkReturn>> captor =
                    ArgumentCaptor.forClass(List.class);

            verify(reworkReturnRepository).saveAll(captor.capture());

            assertEquals(ReturnType.PREDECESSOR,
                    captor.getValue().get(0).getReturnType());
        }

        @Test
        @DisplayName("Нечего снимать — отказ, возврат не заводится")
        void nothingCompletedRejected() {

            OrderScheduleRow chosen = row(100L, 5, order(), unit(), false);

            AppUser master = user(Role.SDP_SA_MASTER);

            when(staffService.unitScope(master)).thenReturn(List.of(UNIT_ID));
            when(scheduleRowRepository.findAllById(List.of(100L)))
                    .thenReturn(List.of(chosen));
            when(scheduleRowRepository.findRowsOfInstanceInUnits(
                    ORDER_ID, 1, 0, List.of(UNIT_ID)))
                    .thenReturn(List.of(chosen));

            assertThrows(IllegalStateException.class,
                    () -> service.sendToPredecessor(List.of(100L), "причина", master));

            verifyNoInteractions(reworkReturnRepository);
        }

        @Test
        @DisplayName("Пустая причина — отказ без обращений к базе")
        void blankReasonRejected() {

            AppUser master = user(Role.SDP_SA_MASTER);

            assertThrows(IllegalArgumentException.class,
                    () -> service.sendToPredecessor(List.of(100L), "  ", master));

            verifyNoInteractions(
                    reworkReturnRepository,
                    scheduleRowRepository,
                    staffService
            );
        }
    }

    /* ============================================================
       ЗАКРЫТИЕ ПЕРЕДЕЛКИ ПОВТОРНЫМ ПРОВЕДЕНИЕМ
       ============================================================ */

    @Nested
    @DisplayName("Закрытие переделки")
    class Resolve {

        @Test
        @DisplayName("Повторное проведение закрывает открытые возвраты")
        void closesOpenReturns() {

            OrderScheduleRow row = new OrderScheduleRow();
            row.setId(5L);

            ReworkReturn open = new ReworkReturn();
            open.setId(88L);
            open.setScheduleRow(row);
            open.setReason("Не пропаян разъём X2");
            open.setStatus(ReworkStatus.IN_REWORK);

            AppUser performer = user(Role.SDP_SA_EXECUTOR);

            when(reworkReturnRepository.findOpenByRowIds(List.of(5L)))
                    .thenReturn(List.of(open));

            service.resolveReturns(5L, performer);

            assertEquals(ReworkStatus.COMPLETED, open.getStatus());
            assertNotNull(open.getResolvedAt());
            assertEquals(performer, open.getResolvedBy());

            verify(reworkReturnRepository).saveAll(List.of(open));
        }

        @Test
        @DisplayName("Возвратов нет — записей нет")
        void nothingToClose() {

            when(reworkReturnRepository.findOpenByRowIds(List.of(5L)))
                    .thenReturn(List.of());

            service.resolveReturns(5L, user(Role.SDP_SA_EXECUTOR));

            verify(reworkReturnRepository, never()).saveAll(any());
        }

        @Test
        @DisplayName("Нет строки или исполнителя — молча ничего")
        void nullArgumentsIgnored() {

            service.resolveReturns(null, user(Role.SDP_SA_EXECUTOR));
            service.resolveReturns(5L, null);

            verifyNoInteractions(reworkReturnRepository);
        }
    }

    /* ============================================================
       СПИСОК РАЗДЕЛА
       ============================================================ */

    @Nested
    @DisplayName("Список изделий")
    class Cards {

        @Test
        @DisplayName("Пустой scope — пустой список без запросов")
        void emptyScopeShortCircuits() {

            AppUser master = user(Role.SDP_SA_MASTER);

            when(staffService.unitScope(master)).thenReturn(List.of());

            assertEquals(0, service.findOpenReturns(master).size());
            assertEquals(0L, service.countOpenReturns(master));

            verifyNoInteractions(
                    controlReturnRepository,
                    scheduleRowRepository
            );
        }

        @Test
        @DisplayName("Возвратов нет — карточек нет")
        void noReturnsNoCards() {

            AppUser master = user(Role.SDP_SA_MASTER);

            when(staffService.unitScope(master)).thenReturn(List.of(UNIT_ID));
            when(controlReturnRepository.findAllOpen()).thenReturn(List.of());

            assertEquals(0, service.findOpenReturns(master).size());
        }
    }
}
