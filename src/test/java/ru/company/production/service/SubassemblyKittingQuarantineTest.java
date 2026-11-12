package ru.company.production.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.company.production.dto.QuarantineFilter;
import ru.company.production.entity.AppUser;
import ru.company.production.entity.Operation;
import ru.company.production.entity.OperationName;
import ru.company.production.entity.OrganizationUnit;
import ru.company.production.entity.ProductClassifier;
import ru.company.production.entity.ProductSerial;
import ru.company.production.entity.ProductionOrder;
import ru.company.production.repository.CellReceivingBatchRepository;
import ru.company.production.repository.OrderScheduleRowRepository;
import ru.company.production.repository.ProductSerialOperationRepository;
import ru.company.production.repository.ProductSerialRepository;
import ru.company.production.repository.ProductionOrderRepository;
import ru.company.production.repository.SerialProductionOrderRepository;
import ru.company.production.repository.TechProcessOperationRepository;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Комплектование подсборок против изолятора брака.
 *
 * <p>Позиция, которую бюро сняло с полки как брак, не имеет права
 * попасть в заказ: вывод из изолятора даётся только подтверждённой
 * приёмкой перемещения складом, а не вводом номера. Граница была
 * незамкнутой — реестр наличия изолятор вычитал, а раздел комплектования
 * предлагал те же номера как свободные, и кладовщик вкладывал в ЗНП
 * забракованное изделие.
 *
 * <p>Проверяются оба конца раздела: перечень на экране не должен
 * показывать позицию изолятора, а проведение — принимать её ручным
 * вводом, который перечнем не ограничен. Отдельно проверяется, что
 * вычтенной оказывается только позиция, реально оставшаяся в изоляторе:
 * принятую складом ячейку вносить обязаны.
 */
@ExtendWith(MockitoExtension.class)
class SubassemblyKittingQuarantineTest {

    @Mock private ProductionOrderRepository productionOrderRepository;
    @Mock private CellReceivingBatchRepository batchRepository;
    @Mock private ProductSerialRepository serialRepository;
    @Mock private SerialProductionOrderRepository linkRepository;
    @Mock private TechProcessOperationRepository techProcessOpRepository;
    @Mock private SerialMovementJournalService journal;
    @Mock private InputControlService inputControlService;
    @Mock private ProductSerialOperationRepository serialOperationRepository;
    @Mock private QuarantineTransferService quarantineTransferService;
    @Mock private UnitHandoverService handoverService;
    @Mock private OrderScheduleRowRepository scheduleRowRepository;

    private SubassemblyKittingService service;

    @BeforeEach
    void setUp() {
        service = new SubassemblyKittingService(
                productionOrderRepository,
                batchRepository,
                serialRepository,
                linkRepository,
                techProcessOpRepository,
                journal,
                inputControlService,
                serialOperationRepository,
                quarantineTransferService,
                handoverService,
                scheduleRowRepository
        );
    }

    /* ============================================================
       ДАННЫЕ
       ============================================================ */

    private static final long ORDER_ID = 41L;
    private static final long CLASSIFIER_ID = 5L;
    private static final String ISOLATED = "510200052";
    private static final String ON_STOCK = "510200050";

    private static final LocalDateTime MOMENT =
            LocalDateTime.of(2026, 11, 12, 10, 0);

    private static ProductClassifier classifier() {
        ProductClassifier classifier = new ProductClassifier();
        classifier.setId(CLASSIFIER_ID);
        classifier.setCode(5102);
        classifier.setName("ЯПР20М");
        return classifier;
    }

    /** Позиция склада: полный номер собирается из кода и серии. */
    private static ProductSerial cell(long id, String fullNumber,
                                      boolean accepted, boolean ready) {
        ProductSerial serial = new ProductSerial();
        serial.setId(id);
        serial.setSerial(Integer.parseInt(fullNumber.substring(4)));
        serial.setProductClassifier(classifier());
        serial.setAcceptedAt(accepted ? MOMENT : null);
        serial.setCellReadyAt(ready ? MOMENT : null);
        return serial;
    }

    /** Заказ-комплектующий с операцией приёмки склада полуфабрикатов. */
    private static ProductionOrder order() {
        OrganizationUnit unit = new OrganizationUnit();
        unit.setCode("SEMI_FINISHED_PRODUCT_WAREHOUSE");

        OperationName name = new OperationName();
        name.setName("Приёмка на склад");

        Operation operation = new Operation();
        operation.setId(9L);
        operation.setOperationName(name);
        operation.setOrganizationUnit(unit);

        ProductionOrder order = new ProductionOrder();
        order.setId(ORDER_ID);
        order.setNumber("ЗНП-1");
        order.setProductClassifier(classifier());
        order.setParentProductionOrder(new ProductionOrder());
        order.setProductionOperation(operation);
        return order;
    }

    private static AppUser executor() {
        AppUser user = new AppUser();
        user.setId(3L);
        user.setUsername("spf-01");
        return user;
    }

    /** Строка перечня изолятора: карточка БВК не нужна для номера. */
    private static QuarantineCellView quarantineRow(String number) {
        return new QuarantineCellView(null, number);
    }

    /* ============================================================
       ПРОВЕДЕНИЕ: РУЧНОЙ ВВОД НОМЕРА
       ============================================================ */

    @Nested
    @DisplayName("Внесение в заказ")
    class Attach {

        @Test
        @DisplayName("Позицию из изолятора брака не проводит")
        void rejectsIsolatedCell() {

            when(productionOrderRepository.findDetailedById(ORDER_ID))
                    .thenReturn(Optional.of(order()));
            when(quarantineTransferService.findActiveQuarantineNumbers())
                    .thenReturn(new LinkedHashSet<>(List.of(ISOLATED)));

            SubassemblyKittingService.KittingResult result =
                    service.attachCells(
                            ORDER_ID, ISOLATED, executor());

            assertFalse(result.completed());
            assertTrue(result.message().contains("изоляторе брака"),
                    result.message());

            verify(linkRepository, never()).save(any());
        }

        @Test
        @DisplayName("Тот же отказ — в режиме изготовления ячеек (V72)")
        void rejectsIsolatedCellForSubassemblyMode() {

            ProductionOrder order = order();
            order.setKittingType(
                    ru.company.production.entity.KittingType.SUBASSEMBLY);

            when(productionOrderRepository.findDetailedById(ORDER_ID))
                    .thenReturn(Optional.of(order));
            when(quarantineTransferService.findActiveQuarantineNumbers())
                    .thenReturn(new LinkedHashSet<>(List.of(ISOLATED)));

            SubassemblyKittingService.KittingResult result =
                    service.attachCells(
                            ORDER_ID, ISOLATED, executor());

            assertFalse(result.completed());
            assertTrue(result.message().contains("изоляторе брака"),
                    result.message());

            verify(linkRepository, never()).save(any());
            verify(serialRepository, never()).markAcceptedByIds(
                    any(), any(), any());
        }

        @Test
        @DisplayName("Позицию, принятую складом обратно, проводит")
        void acceptsCellReturnedToStock() {

            when(productionOrderRepository.findDetailedById(ORDER_ID))
                    .thenReturn(Optional.of(order()));
            when(quarantineTransferService.findActiveQuarantineNumbers())
                    .thenReturn(new LinkedHashSet<>(List.of(ISOLATED)));
            when(serialRepository.findFreeOnWarehouse())
                    .thenReturn(List.of(
                            cell(1L, ON_STOCK, true, true),
                            cell(2L, ISOLATED, true, true)));
            when(linkRepository.countByProductionOrder_Id(ORDER_ID))
                    .thenReturn(0L);
            when(linkRepository.existsBySerial_IdAndProductionOrder_Id(
                    anyLong(), anyLong())).thenReturn(false);
            when(serialOperationRepository
                    .findFirstBySerialIdAndOperationIdOrderByAttemptDesc(
                            anyLong(), anyLong()))
                    .thenReturn(Optional.empty());

            SubassemblyKittingService.KittingResult result =
                    service.attachCells(
                            ORDER_ID, ON_STOCK, executor());

            assertTrue(result.completed(), result.message());
            verify(linkRepository).save(any());
        }
    }

    /* ============================================================
       ПЕРЕЧЕНЬ НА ЭКРАНЕ
       ============================================================ */

    @Nested
    @DisplayName("Свободный перечень склада")
    class StockList {

        @Test
        @DisplayName("Позицию изолятора брака не предлагает")
        void hidesIsolatedCell() {

            when(productionOrderRepository.findDetailedById(ORDER_ID))
                    .thenReturn(Optional.of(order()));
            when(serialRepository.findFreeOnWarehouse())
                    .thenReturn(List.of(
                            cell(1L, ON_STOCK, true, true),
                            cell(2L, ISOLATED, true, true)));
            when(quarantineTransferService.findActiveQuarantineNumbers())
                    .thenReturn(new LinkedHashSet<>(List.of(ISOLATED)));
            when(linkRepository.findByProductionOrderId(ORDER_ID))
                    .thenReturn(List.of());
            when(inputControlService.findQuarantineCells(
                    any(QuarantineFilter.class)))
                    .thenReturn(List.of(quarantineRow(ISOLATED)));

            SubassemblyKittingService.SubassemblyKittingView view =
                    service.loadView(ORDER_ID);

            List<String> offered = view.cells().stream()
                    .map(row -> row.fullNumber())
                    .toList();

            assertTrue(offered.contains(ON_STOCK), offered.toString());
            assertFalse(offered.contains(ISOLATED),
                    "позиция из изолятора брака не должна быть доступна");

            /* Справочный блок изолятора остаётся: кладовщик видит, где
               недостающие подсборки, а не ищет их вслепую. */
            assertTrue(view.quarantine().stream()
                    .anyMatch(row -> ISOLATED.equals(row.getFullNumber())));
        }

        @Test
        @DisplayName("Непринятую подсборку из изолятора не предлагает (V72)")
        void hidesIsolatedSubassembly() {

            ProductionOrder order = order();
            order.setKittingType(
                    ru.company.production.entity.KittingType.SUBASSEMBLY);

            when(productionOrderRepository.findDetailedById(ORDER_ID))
                    .thenReturn(Optional.of(order));
            when(serialRepository.findUnacceptedCells())
                    .thenReturn(List.of(
                            cell(1L, ON_STOCK, false, false),
                            cell(2L, ISOLATED, false, false)));
            when(quarantineTransferService.findActiveQuarantineNumbers())
                    .thenReturn(new LinkedHashSet<>(List.of(ISOLATED)));
            when(linkRepository.findByProductionOrderId(ORDER_ID))
                    .thenReturn(List.of());
            when(inputControlService.findQuarantineCells(
                    any(QuarantineFilter.class)))
                    .thenReturn(List.of(quarantineRow(ISOLATED)));

            SubassemblyKittingService.SubassemblyKittingView view =
                    service.loadView(ORDER_ID);

            List<String> offered = view.cells().stream()
                    .map(row -> row.fullNumber())
                    .toList();

            assertFalse(offered.contains(ISOLATED), offered.toString());
        }
    }

    /* ============================================================
       СПРАВОЧНЫЙ БЛОК ИЗОЛЯТОРА ОСТАЁТСЯ НА ЭКРАНЕ
       ============================================================ */

    @Nested
    @DisplayName("Блок изолятора на экране")
    class QuarantineBlock {

        @Test
        @DisplayName("Позицию показывают справочно, даже вняв перечню склада")
        void showsIsolatedCellAsReference() {

            when(productionOrderRepository.findDetailedById(ORDER_ID))
                    .thenReturn(Optional.of(order()));
            when(serialRepository.findFreeOnWarehouse())
                    .thenReturn(List.of(cell(1L, ON_STOCK, true, true)));
            when(quarantineTransferService.findActiveQuarantineNumbers())
                    .thenReturn(new LinkedHashSet<>(List.of(ISOLATED)));
            when(linkRepository.findByProductionOrderId(ORDER_ID))
                    .thenReturn(List.of());
            when(inputControlService.findQuarantineCells(
                    any(QuarantineFilter.class)))
                    .thenReturn(List.of(quarantineRow(ISOLATED)));

            SubassemblyKittingService.SubassemblyKittingView view =
                    service.loadView(ORDER_ID);

            /* Кладовщик обязан видеть, где недостающие подсборки, а не
               искать их вслепую: вычтенный из склада номер остаётся в
               справочном блоке. */
            assertTrue(view.quarantine().stream()
                    .anyMatch(row -> ISOLATED.equals(row.getFullNumber())));
            assertTrue(view.cells().stream()
                    .noneMatch(row -> ISOLATED.equals(row.fullNumber())));
        }
    }
}
