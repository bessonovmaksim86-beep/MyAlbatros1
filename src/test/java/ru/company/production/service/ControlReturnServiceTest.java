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
import ru.company.production.entity.OrderScheduleRow;
import ru.company.production.entity.OrganizationUnit;
import ru.company.production.entity.Role;
import ru.company.production.repository.ControlReturnRepository;
import ru.company.production.repository.OrderScheduleRowRepository;
import ru.company.production.repository.ProductSerialRepository;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Возврат работы с контроля БТК — снятие проведения и запись переделки.
 *
 * <p>Смысл теста в том, что возврат меняет закрытую работу графика.
 * Ошибка здесь незаметна в интерфейсе: подсветка переделки появляется,
 * а работа под ней остаётся проведённой, и цех её не переделывает.
 * Поэтому проверяются оба факта сразу — снятая подпись проведения и
 * заведенный возврат на каждую снятую строку.
 *
 * <p>Отказы важны не меньше: отказ обязан не оставить полуснятого
 * плана, поэтому проверки стоят до записей и проверяются через
 * отсутствие обращений к репозиторию возвратов.
 *
 * <p>Действие «в изолятор брака» здесь не проверяется: оно ведёт заявку
 * отбраковки и проверено в тесте {@link PendingDefectService}, а его
 * единственная специфика — выбор номера изделия — не меняется.
 */
@ExtendWith(MockitoExtension.class)
class ControlReturnServiceTest {

    @Mock private ControlReturnRepository controlReturnRepository;
    @Mock private OrderScheduleRowRepository scheduleRowRepository;
    @Mock private ProductSerialRepository serialRepository;
    @Mock private WorkshopDistributionSerialService serialService;
    @Mock private WorkshopStaffService staffService;
    @Mock private PendingDefectService pendingDefectService;

    private ControlReturnService service;

    @BeforeEach
    void setUp() {
        service = new ControlReturnService(
                controlReturnRepository,
                scheduleRowRepository,
                serialRepository,
                serialService,
                staffService,
                pendingDefectService
        );
    }

    /* ============================================================
       ПОСТРОЕНИЕ ДАННЫХ
       ============================================================ */

    private static final long UNIT_ID = 7L;
    private static final long ORDER_ID = 31L;

    private static AppUser user(Role role) {
        AppUser appUser = new AppUser();
        appUser.setId(1L);
        appUser.setUsername("btk-01");
        appUser.setFullName("Иванов И. И.");
        appUser.setRole(role);
        return appUser;
    }

    /**
     * Проведённая строка плана выпуска одного изделия: заказ покупателя,
     * позиция и номер экземпляра задают экземпляр целиком.
     */
    private static OrderScheduleRow completedRow(long id,
                                                 CustomerOrder order,
                                                 OrganizationUnit unit) {
        OrderScheduleRow row = new OrderScheduleRow();
        row.setId(id);
        row.setCustomerOrder(order);
        row.setItemPosition(1);
        row.setInstanceIndex(0);
        row.setOrganizationUnit(unit);
        row.setCompletedAt(LocalDateTime.of(2026, 11, 2, 10, 0));
        row.setCompletedBy(user(Role.BTK_EXECUTOR));
        return row;
    }

    /* ============================================================
       ОТПРАВКА НА ДОРАБОТКУ
       ============================================================ */

    @Nested
    @DisplayName("Отправить на доработку")
    class Rework {

        @Test
        @DisplayName("Возврат — сигнал: проведение остаётся на месте")
        void keepsCompletionUntouched() {

            CustomerOrder order = new CustomerOrder();
            order.setId(ORDER_ID);

            OrganizationUnit unit = new OrganizationUnit();
            unit.setId(UNIT_ID);

            /*
             * Отмечена одна строка, а проведено изделие по двум строкам
             * плана: сигнал обязан дойти до каждого исполнителя изделия,
             * иначе переделку увидит только контролёр.
             */
            OrderScheduleRow marked = completedRow(100L, order, unit);
            OrderScheduleRow sibling = completedRow(101L, order, unit);

            AppUser actor = user(Role.BTK_MASTER);

            when(staffService.unitScope(actor)).thenReturn(List.of(UNIT_ID));
            when(scheduleRowRepository.findAllById(List.of(100L)))
                    .thenReturn(List.of(marked));
            when(scheduleRowRepository.findCompletedRowsOfInstance(
                    ORDER_ID, 1, 0))
                    .thenReturn(List.of(marked, sibling));

            String message = service.sendToRework(
                    List.of(100L), "Не пропаян разъём X2", actor);

            /*
             * Проведение не снимается: решение о том, какие операции
             * переделывать, принимает начальник цеха в своём разделе,
             * и снятие делает он — ровно с отмеченных строк.
             */
            assertNotNull(marked.getCompletedAt());
            assertNotNull(marked.getCompletedBy());
            assertNotNull(sibling.getCompletedAt());
            assertNotNull(sibling.getCompletedBy());

            verify(scheduleRowRepository, never()).save(any());

            assertEquals("Возвращено на доработку: 1", message);
        }

        @Test
        @DisplayName("Заводит возврат на каждую проведённую строку")
        void writesReturnPerCompletedRow() {

            CustomerOrder order = new CustomerOrder();
            order.setId(ORDER_ID);

            OrganizationUnit unit = new OrganizationUnit();
            unit.setId(UNIT_ID);

            OrderScheduleRow marked = completedRow(100L, order, unit);
            OrderScheduleRow sibling = completedRow(101L, order, unit);

            AppUser actor = user(Role.BTK_EXECUTOR);

            when(staffService.unitScope(actor)).thenReturn(List.of(UNIT_ID));
            when(scheduleRowRepository.findAllById(List.of(100L)))
                    .thenReturn(List.of(marked));
            when(scheduleRowRepository.findCompletedRowsOfInstance(
                    ORDER_ID, 1, 0))
                    .thenReturn(List.of(marked, sibling));

            service.sendToRework(List.of(100L), "  Перепутать маркировку  ", actor);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<ControlReturn>> captor =
                    ArgumentCaptor.forClass(List.class);

            verify(controlReturnRepository).saveAll(captor.capture());

            List<ControlReturn> returns = captor.getValue();

            assertEquals(2, returns.size());

            /*
             * Причина сохраняется без обрамляющих пробелов: цех читает
             * её из списка, и ведущие пробелы ломают сравнение причин.
             */
            assertEquals("Перепутать маркировку", returns.get(0).getReason());
            assertEquals("Контроль БТК", returns.get(0).getControlOperation());
            assertEquals(actor, returns.get(0).getReturnedBy());
            assertNotNull(returns.get(0).getReturnedAt());
            assertNull(returns.get(0).getResolvedAt());

            assertEquals(100L, returns.get(0).getScheduleRow().getId());
            assertEquals(101L, returns.get(1).getScheduleRow().getId());
        }

        @Test
        @DisplayName("Пустая причина — отказ без записей")
        void blankReasonRejectedBeforeWrites() {

            AppUser actor = user(Role.BTK_MASTER);

            assertThrows(IllegalArgumentException.class,
                    () -> service.sendToRework(List.of(100L), "   ", actor));

            verifyNoInteractions(
                    controlReturnRepository,
                    scheduleRowRepository,
                    staffService
            );
        }

        @Test
        @DisplayName("Причина длиннее поля — отказ")
        void longReasonRejected() {

            AppUser actor = user(Role.BTK_MASTER);

            assertThrows(IllegalArgumentException.class,
                    () -> service.sendToRework(
                            List.of(100L), "а".repeat(501), actor));

            verifyNoInteractions(controlReturnRepository, scheduleRowRepository);
        }

        @Test
        @DisplayName("Не БТК — отказ")
        void otherBureauRejected() {

            AppUser actor = user(Role.BVK_EXECUTOR);

            assertThrows(IllegalStateException.class,
                    () -> service.sendToRework(List.of(100L), "причина", actor));

            verifyNoInteractions(controlReturnRepository, scheduleRowRepository);
        }

        @Test
        @DisplayName("Отметок нет — отказ")
        void emptySelectionRejected() {

            AppUser actor = user(Role.BTK_MASTER);

            assertThrows(IllegalArgumentException.class,
                    () -> service.sendToRework(List.of(), "причина", actor));

            verifyNoInteractions(controlReturnRepository, scheduleRowRepository);
        }

        @Test
        @DisplayName("Чужое подразделение — отказ")
        void foreignUnitRejected() {

            CustomerOrder order = new CustomerOrder();
            order.setId(ORDER_ID);

            OrganizationUnit foreign = new OrganizationUnit();
            foreign.setId(9L);

            OrderScheduleRow row = completedRow(100L, order, foreign);

            AppUser actor = user(Role.BTK_MASTER);

            when(staffService.unitScope(actor)).thenReturn(List.of(UNIT_ID));
            when(scheduleRowRepository.findAllById(List.of(100L)))
                    .thenReturn(List.of(row));

            assertThrows(IllegalStateException.class,
                    () -> service.sendToRework(List.of(100L), "причина", actor));

            verifyNoInteractions(controlReturnRepository);
        }

        @Test
        @DisplayName("Строка исчезла из плана — отказ")
        void missingRowRejected() {

            AppUser actor = user(Role.BTK_MASTER);

            /*
             * Граница подразделения здесь не задаётся: сверка количества
             * строк стоит до неё, и несуществующая строка отсекается
             * раньше, чем доходит до сравнения с подразделением.
             */
            when(scheduleRowRepository.findAllById(List.of(100L, 101L)))
                    .thenReturn(List.of());

            assertThrows(IllegalArgumentException.class,
                    () -> service.sendToRework(
                            List.of(100L, 101L), "причина", actor));

            verifyNoInteractions(controlReturnRepository, staffService);
        }

        @Test
        @DisplayName("Нечего снимать — отказ, возврат не заводится")
        void nothingCompletedRejected() {

            CustomerOrder order = new CustomerOrder();
            order.setId(ORDER_ID);

            OrganizationUnit unit = new OrganizationUnit();
            unit.setId(UNIT_ID);

            OrderScheduleRow row = completedRow(100L, order, unit);

            AppUser actor = user(Role.BTK_MASTER);

            when(staffService.unitScope(actor)).thenReturn(List.of(UNIT_ID));
            when(scheduleRowRepository.findAllById(List.of(100L)))
                    .thenReturn(List.of(row));
            when(scheduleRowRepository.findCompletedRowsOfInstance(
                    ORDER_ID, 1, 0))
                    .thenReturn(List.of());

            assertThrows(IllegalStateException.class,
                    () -> service.sendToRework(List.of(100L), "причина", actor));

            verifyNoInteractions(controlReturnRepository);
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

            ControlReturn open = new ControlReturn();
            open.setId(88L);
            open.setScheduleRow(row);
            open.setReason("Не пропаян разъём X2");

            AppUser performer = user(Role.BTK_EXECUTOR);

            when(controlReturnRepository.findOpenByRowIds(List.of(5L)))
                    .thenReturn(List.of(open));

            service.resolveReturns(5L, performer);

            assertNotNull(open.getResolvedAt());
            assertEquals(performer, open.getResolvedBy());

            verify(controlReturnRepository).saveAll(List.of(open));
        }

        @Test
        @DisplayName("Возвратов нет — записей нет")
        void nothingToClose() {

            when(controlReturnRepository.findOpenByRowIds(List.of(5L)))
                    .thenReturn(List.of());

            service.resolveReturns(5L, user(Role.BTK_EXECUTOR));

            verify(controlReturnRepository, never()).saveAll(any());
        }

        @Test
        @DisplayName("Нет строки или исполнителя — молча ничего")
        void nullArgumentsIgnored() {

            service.resolveReturns(null, user(Role.BTK_EXECUTOR));
            service.resolveReturns(5L, null);

            verifyNoInteractions(controlReturnRepository);
        }
    }

    /* ============================================================
       ПРАВО НА ДЕЙСТВИЕ
       ============================================================ */

    @Test
    @DisplayName("Возвращать вправе обе роли БТК")
    void controlRolesAnswered() {

        assertEquals(true, service.isControlStaff(user(Role.BTK_MASTER)));
        assertEquals(true, service.isControlStaff(user(Role.BTK_EXECUTOR)));
        assertEquals(false, service.isControlStaff(user(Role.BVK_EXECUTOR)));
        assertEquals(false, service.isControlStaff(null));
    }
}
