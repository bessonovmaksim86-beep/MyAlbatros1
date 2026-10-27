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
 * Unit-тесты статусных переходов заказа на производство:
 * <ul>
 *   <li>plan — «Новый» → «В работе»</li>
 *   <li>complete — частичное и полное выполнение</li>
 *   <li>cancel — отмена с каскадом на детей</li>
 *   <li>reopen — возврат отменённого ЗНП в «Новый»</li>
 *   <li>закрытый статус блокирует повторные действия</li>
 *   </ul>
 */
@ExtendWith(MockitoExtension.class)
class ProductionOrderServiceStatusTransitionsTest {

    @Mock private ProductionOrderRepository productionOrderRepository;
    @Mock private ProductionOrderItemRepository productionOrderItemRepository;
    @Mock private ProductSerialRepository productSerialRepository;
    @Mock private ProductSerialOperationRepository productSerialOperationRepository;
    @Mock private CustomerOrderRepository customerOrderRepository;
    @Mock private OperationRepository operationRepository;
    @Mock private ProductClassifierRepository productClassifierRepository;
    @Mock private TechProcessRepository techProcessRepository;
    @Mock private TechProcessClassifierRepository techProcessClassifierRepository;
    @Mock private TechProcessOperationRepository techProcessOperationRepository;
    @Mock private OrderScheduleRowRepository orderScheduleRowRepository;
    @Mock private SerialProductionOrderRepository serialProductionOrderRepository;
    @Mock private SerialKittingService serialKittingService;
    @Mock private SerialIssueService serialIssueService;
    @Mock private SerialMovementJournalService journal;
    @Mock private OrderSchedulingService orderSchedulingService;

    private ProductionOrderService service;

    /*
     * Порядок аргументов повторяет порядок полей ProductionOrderService,
     * который задаёт Lombok @RequiredArgsConstructor: конструктор
     * позиционный, и переставленные репозитории молча дали бы сервису
     * чужие заглушки вместо проверяемых.
     */
    @BeforeEach
    void setUp() {
        service = new ProductionOrderService(
                productionOrderRepository,
                productionOrderItemRepository,
                productSerialRepository,
                productSerialOperationRepository,
                customerOrderRepository,
                operationRepository,
                productClassifierRepository,
                techProcessRepository,
                techProcessClassifierRepository,
                techProcessOperationRepository,
                orderScheduleRowRepository,
                orderSchedulingService,
                serialKittingService,
                serialIssueService,
                journal,
                serialProductionOrderRepository
        );
    }

    /* ======================== ПЛАН / В РАБОТУ ======================== */

    @Nested
    @DisplayName("plan() — перевод в «В работе»")
    class PlanTests {

        @Test
        @DisplayName("plan: «Новый» → «В работе»")
        void planNewToInWork() {
            ProductionOrder order = createOrder(1L, ProductionOrderStatus.NEW);
            order.addItem(createItem(1L, 1L, ProductionItemStatus.NEW));

            when(productionOrderRepository.findDetailedById(1L))
                    .thenReturn(Optional.of(order));
            when(productionOrderRepository.save(any()))
                    .thenAnswer(i -> i.getArgument(0));
            when(productionOrderItemRepository.countByOrderId(1L))
                    .thenReturn(2L);
            when(productionOrderItemRepository.countWithoutZnpByOrderId(1L))
                    .thenReturn(0L);

            service.plan(1L);

            assertEquals(ProductionOrderStatus.IN_WORK, order.getStatus());
            /*
             * Позиции и серийники переводятся bulk-запросом по
             * идентификатору заказа (см. ProductionOrderService#applyStatus),
             * а не перебором загруженной коллекции, поэтому проверяется сам
             * запрос: in-memory статус позиции в тесте остался бы прежним.
             */
            verify(productionOrderItemRepository)
                    .updateStatusByOrderId(1L, ProductionItemStatus.IN_WORK);
            verify(productSerialRepository)
                    .updateStatusByOrderId(1L, ProductSerialStatus.IN_WORK);
        }

        @Test
        @DisplayName("plan: «В работе» → не меняет статус")
        void planAlreadyInWork() {
            ProductionOrder order = createOrder(1L, ProductionOrderStatus.IN_WORK);

            when(productionOrderRepository.findDetailedById(1L))
                    .thenReturn(Optional.of(order));
            when(productionOrderRepository.save(any()))
                    .thenAnswer(i -> i.getArgument(0));
            when(productionOrderItemRepository.countByOrderId(1L))
                    .thenReturn(0L);

            assertDoesNotThrow(() -> service.plan(1L));
            assertEquals(ProductionOrderStatus.IN_WORK, order.getStatus());
        }

        @Test
        @DisplayName("plan: «Выполнен» → исключение")
        void planDoneThrows() {
            ProductionOrder order = createOrder(1L, ProductionOrderStatus.DONE);

            when(productionOrderRepository.findDetailedById(1L))
                    .thenReturn(Optional.of(order));

            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> service.plan(1L));
            assertTrue(ex.getMessage().contains("уже закрыт"));
        }

        @Test
        @DisplayName("plan: «Отменён» → исключение")
        void planCancelledThrows() {
            ProductionOrder order = createOrder(1L, ProductionOrderStatus.CANCELLED);

            when(productionOrderRepository.findDetailedById(1L))
                    .thenReturn(Optional.of(order));

            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> service.plan(1L));
            assertTrue(ex.getMessage().contains("уже закрыт"));
        }

        @Test
        @DisplayName("plan: не все ЗНП → исключение")
        void planMissingZnpThrows() {
            ProductionOrder order = createOrder(1L, ProductionOrderStatus.NEW);

            when(productionOrderRepository.findDetailedById(1L))
                    .thenReturn(Optional.of(order));
            /*
             * save не стабится: отказ из-за неполных ЗНП обязан вернуться
             * до любых изменений статуса, и вызов save здесь означал бы,
             * что страж сработал слишком поздно.
             */
            when(productionOrderItemRepository.countByOrderId(1L))
                    .thenReturn(3L);
            when(productionOrderItemRepository.countWithoutZnpByOrderId(1L))
                    .thenReturn(1L);

            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> service.plan(1L));
            assertTrue(ex.getMessage().contains("ЗНП"));
        }
    }

    /* ======================== ВЫПОЛНЕНИЕ ======================== */

    @Nested
    @DisplayName("complete() — перевод в «Выполнен»")
    class CompleteTests {

        @Test
        @DisplayName("complete: «В работе» → «Выполнен»")
        void completeInWork() {
            ProductionOrder order = createOrder(1L, ProductionOrderStatus.IN_WORK);
            ProductionOrderItem item = createItem(1L, 1L, ProductionItemStatus.IN_WORK);
            order.addItem(item);

            when(productionOrderRepository.findDetailedById(1L))
                    .thenReturn(Optional.of(order));
            when(productionOrderRepository.save(any()))
                    .thenAnswer(i -> i.getArgument(0));
            when(productionOrderItemRepository.countByOrderId(1L))
                    .thenReturn(1L);

            service.complete(1L);

            assertEquals(ProductionOrderStatus.DONE, order.getStatus());
            // Статус позиции — тоже bulk-запрос, см. planNewToInWork.
            verify(productionOrderItemRepository)
                    .updateStatusByOrderId(1L, ProductionItemStatus.DONE);
        }

        @Test
        @DisplayName("complete: «Частично выполнен» → «Выполнен»")
        void completePartiallyDone() {
            ProductionOrder order = createOrder(1L, ProductionOrderStatus.PARTIALLY_DONE);
            ProductionOrderItem item = createItem(1L, 1L, ProductionItemStatus.IN_WORK);
            order.addItem(item);

            when(productionOrderRepository.findDetailedById(1L))
                    .thenReturn(Optional.of(order));
            when(productionOrderRepository.save(any()))
                    .thenAnswer(i -> i.getArgument(0));
            when(productionOrderItemRepository.countByOrderId(1L))
                    .thenReturn(1L);

            service.complete(1L);

            assertEquals(ProductionOrderStatus.DONE, order.getStatus());
        }

        @Test
        @DisplayName("complete: «Новый» → «Выполнен»")
        void completeNew() {
            ProductionOrder order = createOrder(1L, ProductionOrderStatus.NEW);
            ProductionOrderItem item = createItem(1L, 1L, ProductionItemStatus.NEW);
            order.addItem(item);

            when(productionOrderRepository.findDetailedById(1L))
                    .thenReturn(Optional.of(order));
            when(productionOrderRepository.save(any()))
                    .thenAnswer(i -> i.getArgument(0));
            when(productionOrderItemRepository.countByOrderId(1L))
                    .thenReturn(1L);

            service.complete(1L);

            assertEquals(ProductionOrderStatus.DONE, order.getStatus());
        }

        @Test
        @DisplayName("complete: «Отменён» → исключение")
        void completeCancelledThrows() {
            ProductionOrder order = createOrder(1L, ProductionOrderStatus.CANCELLED);

            when(productionOrderRepository.findDetailedById(1L))
                    .thenReturn(Optional.of(order));

            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> service.complete(1L));
            assertTrue(ex.getMessage().contains("Отменённый"));
        }

        @Test
        @DisplayName("complete: «Выполнен» → «Выполнен» (no-op)")
        void completeAlreadyDone() {
            ProductionOrder order = createOrder(1L, ProductionOrderStatus.DONE);
            ProductionOrderItem item = createItem(1L, 1L, ProductionItemStatus.DONE);
            order.addItem(item);

            when(productionOrderRepository.findDetailedById(1L))
                    .thenReturn(Optional.of(order));
            when(productionOrderRepository.save(any()))
                    .thenAnswer(i -> i.getArgument(0));
            when(productionOrderItemRepository.countByOrderId(1L))
                    .thenReturn(1L);

            service.complete(1L);
            assertEquals(ProductionOrderStatus.DONE, order.getStatus());
        }

        @Test
        @DisplayName("complete: без изделий → исключение")
        void completeNoItemsThrows() {
            ProductionOrder order = createOrder(1L, ProductionOrderStatus.IN_WORK);

            when(productionOrderRepository.findDetailedById(1L))
                    .thenReturn(Optional.of(order));
            // См. planMissingZnpThrows: отказ «закрывать нечего» — до save.
            when(productionOrderItemRepository.countByOrderId(1L))
                    .thenReturn(0L);

            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> service.complete(1L));
            assertTrue(ex.getMessage().contains("нет изделий"));
        }
    }

    /* ======================== ОТМЕНА ======================== */

    @Nested
    @DisplayName("cancel() — отмена заказа")
    class CancelTests {

        @Test
        @DisplayName("cancel: «Новый» → «Отменён»")
        void cancelNew() {
            ProductionOrder order = createOrder(1L, ProductionOrderStatus.NEW);

            when(productionOrderRepository.findDetailedById(1L))
                    .thenReturn(Optional.of(order));
            when(productionOrderRepository.save(any()))
                    .thenAnswer(i -> i.getArgument(0));

            service.cancel(1L);

            assertEquals(ProductionOrderStatus.CANCELLED, order.getStatus());
        }

        @Test
        @DisplayName("cancel: «В работе» → «Отменён»")
        void cancelInWork() {
            ProductionOrder order = createOrder(1L, ProductionOrderStatus.IN_WORK);

            when(productionOrderRepository.findDetailedById(1L))
                    .thenReturn(Optional.of(order));
            when(productionOrderRepository.save(any()))
                    .thenAnswer(i -> i.getArgument(0));

            service.cancel(1L);

            assertEquals(ProductionOrderStatus.CANCELLED, order.getStatus());
        }

        @Test
        @DisplayName("cancel: «Отменён» → исключение")
        void cancelAlreadyCancelled() {
            ProductionOrder order = createOrder(1L, ProductionOrderStatus.CANCELLED);

            when(productionOrderRepository.findDetailedById(1L))
                    .thenReturn(Optional.of(order));

            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> service.cancel(1L));
            assertTrue(ex.getMessage().contains("уже отменён"));
        }

        @Test
        @DisplayName("cancel: «Выполнен» → исключение")
        void cancelDoneThrows() {
            ProductionOrder order = createOrder(1L, ProductionOrderStatus.DONE);

            when(productionOrderRepository.findDetailedById(1L))
                    .thenReturn(Optional.of(order));

            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> service.cancel(1L));
            assertTrue(ex.getMessage().contains("Выполненный"));
        }
    }

    /* ======================== ВОЗВРАТ ======================== */

    @Nested
    @DisplayName("reopen() — возврат отменённого ЗНП")
    class ReopenTests {

        @Test
        @DisplayName("reopen: «Отменён» → «Новый» (ЗНП)")
        void reopenCancelledComponent() {
            ProductionOrder order = createOrder(1L, ProductionOrderStatus.CANCELLED);
            order.setParentProductionOrder(createOrder(2L, ProductionOrderStatus.IN_WORK));

            when(productionOrderRepository.findDetailedById(1L))
                    .thenReturn(Optional.of(order));
            when(productionOrderRepository.save(any()))
                    .thenAnswer(i -> i.getArgument(0));

            service.reopen(1L);

            assertEquals(ProductionOrderStatus.NEW, order.getStatus());
        }

        @Test
        @DisplayName("reopen: выпуск → исключение")
        void reopenRootThrows() {
            ProductionOrder order = createOrder(1L, ProductionOrderStatus.CANCELLED);
            // isRoot() = true (parent = null, znpKind = KITTING)

            when(productionOrderRepository.findDetailedById(1L))
                    .thenReturn(Optional.of(order));

            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> service.reopen(1L));
            assertTrue(ex.getMessage().contains("только заказ-комплектующий"));
        }

        @Test
        @DisplayName("reopen: не отменён → исключение")
        void reopenNotCancelledThrows() {
            ProductionOrder order = createOrder(1L, ProductionOrderStatus.IN_WORK);
            order.setParentProductionOrder(createOrder(2L, ProductionOrderStatus.IN_WORK));

            when(productionOrderRepository.findDetailedById(1L))
                    .thenReturn(Optional.of(order));

            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> service.reopen(1L));
            assertTrue(ex.getMessage().contains("не отменён"));
        }
    }

    /* ======================== КАСКАД ======================== */

    @Nested
    @DisplayName("Каскадный перевод статусов")
    class CascadeTests {

        @Test
        @DisplayName("plan: каскад «В работе» на детей")
        void planCascadeToChildren() {
            ProductionOrder parent = createOrder(1L, ProductionOrderStatus.NEW);
            ProductionOrder child = createOrder(2L, ProductionOrderStatus.NEW, parent);

            when(productionOrderRepository.findDetailedById(1L))
                    .thenReturn(Optional.of(parent));
            when(productionOrderRepository.findActiveChildren(1L))
                    .thenReturn(List.of(child));
            when(productionOrderRepository.findActiveChildren(2L))
                    .thenReturn(List.of());
            when(productionOrderRepository.save(any()))
                    .thenAnswer(i -> i.getArgument(0));
            when(productionOrderItemRepository.countByOrderId(1L))
                    .thenReturn(0L);
            when(productionOrderItemRepository.countWithoutZnpByOrderId(1L))
                    .thenReturn(0L);

            service.plan(1L);

            assertEquals(ProductionOrderStatus.IN_WORK, parent.getStatus());
            assertEquals(ProductionOrderStatus.IN_WORK, child.getStatus());
        }

        @Test
        @DisplayName("cancel: каскад «Отменён» на детей")
        void cancelCascadeToChildren() {
            ProductionOrder parent = createOrder(1L, ProductionOrderStatus.IN_WORK);
            ProductionOrder child = createOrder(2L, ProductionOrderStatus.IN_WORK, parent);

            when(productionOrderRepository.findDetailedById(1L))
                    .thenReturn(Optional.of(parent));
            when(productionOrderRepository.findActiveChildren(1L))
                    .thenReturn(List.of(child));
            when(productionOrderRepository.findActiveChildren(2L))
                    .thenReturn(List.of());
            when(productionOrderRepository.save(any()))
                    .thenAnswer(i -> i.getArgument(0));

            service.cancel(1L);

            assertEquals(ProductionOrderStatus.CANCELLED, parent.getStatus());
            assertEquals(ProductionOrderStatus.CANCELLED, child.getStatus());
        }

        @Test
        @DisplayName("plan: не каскадит на закрытых детей")
        void planSkipClosedChildren() {
            ProductionOrder parent = createOrder(1L, ProductionOrderStatus.NEW);
            ProductionOrder closedChild = createOrder(2L, ProductionOrderStatus.DONE, parent);

            when(productionOrderRepository.findDetailedById(1L))
                    .thenReturn(Optional.of(parent));
            when(productionOrderRepository.findActiveChildren(1L))
                    .thenReturn(List.of(closedChild));
            /*
             * findActiveChildren(2L) не стабится: закрытый заказ не
             * обходится ни проверкой плана, ни каскадом, лишний стаб только
             * закрыл бы это поведение.
             */
            when(productionOrderRepository.save(any()))
                    .thenAnswer(i -> i.getArgument(0));
            when(productionOrderItemRepository.countByOrderId(1L))
                    .thenReturn(0L);
            when(productionOrderItemRepository.countWithoutZnpByOrderId(1L))
                    .thenReturn(0L);

            service.plan(1L);

            assertEquals(ProductionOrderStatus.IN_WORK, parent.getStatus());
            // Закрытый ребёнок не должен быть переведён
            assertEquals(ProductionOrderStatus.DONE, closedChild.getStatus());
            verify(productionOrderRepository, never()).save(closedChild);
        }
    }

    /* ======================== ЧАСТИЧНО ВЫПОЛНЕН ======================== */

    @Nested
    @DisplayName("Статус «Частично выполнен»")
    class PartiallyDoneTests {

        @Test
        @DisplayName("Статус PARTIALLY_DONE существует и имеет отображаемое имя")
        void partiallyDoneExists() {
            ProductionOrderStatus status = ProductionOrderStatus.PARTIALLY_DONE;
            assertNotNull(status.getDisplayName());
            assertEquals("Частично выполнен", status.getDisplayName());
        }

        @Test
        @DisplayName("PARTIALLY_DONE → complete → DONE")
        void partiallyDoneToDone() {
            ProductionOrder order = createOrder(1L, ProductionOrderStatus.PARTIALLY_DONE);
            ProductionOrderItem item = createItem(1L, 1L, ProductionItemStatus.IN_WORK);
            order.addItem(item);

            when(productionOrderRepository.findDetailedById(1L))
                    .thenReturn(Optional.of(order));
            when(productionOrderRepository.save(any()))
                    .thenAnswer(i -> i.getArgument(0));
            when(productionOrderItemRepository.countByOrderId(1L))
                    .thenReturn(1L);

            service.complete(1L);

            assertEquals(ProductionOrderStatus.DONE, order.getStatus());
        }
    }

    /* ======================== ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ ======================== */

    private ProductionOrder createOrder(Long id, ProductionOrderStatus status) {
        return createOrder(id, status, null);
    }

    private ProductionOrder createOrder(Long id, ProductionOrderStatus status,
                                        ProductionOrder parent) {
        ProductionOrder order = new ProductionOrder();
        order.setId(id);
        order.setStatus(status);
        order.setNumber("ЗНП-" + id);
        order.setActive(true);
        order.setZnpKind(ZnpKind.KITTING);
        order.setParentProductionOrder(parent);
        order.setItems(new ArrayList<>());
        return order;
    }

    private ProductionOrderItem createItem(Long id, Long orderId,
                                           ProductionItemStatus status) {
        ProductionOrderItem item = new ProductionOrderItem();
        item.setId(id);
        item.setStatus(status);
        item.setPosition(1);
        return item;
    }
}
