package ru.company.production.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.company.production.entity.Operation;
import ru.company.production.entity.OperationName;
import ru.company.production.entity.OperationType;
import ru.company.production.entity.OrderScheduleRow;
import ru.company.production.entity.ProductClassifier;
import ru.company.production.entity.ProductSerial;
import ru.company.production.entity.ProductType;
import ru.company.production.entity.ProductionOrder;
import ru.company.production.repository.ClassifierInclusionRepository;
import ru.company.production.repository.OrganizationUnitRepository;
import ru.company.production.repository.OrderScheduleRowRepository;
import ru.company.production.repository.ProductClassifierRepository;
import ru.company.production.repository.ProductSerialRepository;
import ru.company.production.repository.ProductionOrderItemRepository;
import ru.company.production.repository.SerialProductionOrderRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Привязка ячейки к ЗНП против изолятора брака.
 *
 * <p>Раздел привязки принимает номер в поле, а не выбор из перечня,
 * поэтому отказ обязан стоять на проведении: экран склада позицию
 * изолятора не показывает, но ввод с клавиатуры раньше проходил.
 *
 * <p>Проверяется отказ, стоящий до записи в матрицу комплектования, —
 * полупроведённого состава раздел оставить не вправе. Отметка приёмки
 * на позиции при этом сохранена: в изоляторе брак именно потому, что
 * бюро сняло принятую ячейку с полки, а не потому, что её не принимали.
 */
@ExtendWith(MockitoExtension.class)
class WarehouseKittingQuarantineTest {

    @Mock private OrganizationUnitRepository organizationUnitRepository;
    @Mock private WorkshopDistributionService distributionService;
    @Mock private WorkshopDistributionSerialService serialService;
    @Mock private SerialProductionOrderRepository serialProductionOrderRepository;
    @Mock private OrderScheduleRowRepository scheduleRowRepository;
    @Mock private ProductSerialRepository serialRepository;
    @Mock private ProductClassifierRepository classifierRepository;
    @Mock private ClassifierInclusionRepository inclusionRepository;
    @Mock private ProductionOrderItemRepository itemRepository;
    @Mock private SerialMovementJournalService journal;
    @Mock private UnitHandoverService handoverService;
    @Mock private QuarantineTransferService quarantineTransferService;

    private WarehouseKittingService service;

    @BeforeEach
    void setUp() {
        service = new WarehouseKittingService(
                organizationUnitRepository,
                distributionService,
                serialService,
                serialProductionOrderRepository,
                scheduleRowRepository,
                serialRepository,
                classifierRepository,
                inclusionRepository,
                itemRepository,
                journal,
                handoverService,
                quarantineTransferService
        );
    }

    /* ============================================================
       ДАННЫЕ
       ============================================================ */

    private static final long ROW_ID = 61L;
    private static final String ISOLATED = "510200052";
    private static final LocalDateTime MOMENT =
            LocalDateTime.of(2026, 11, 12, 10, 0);

    /** Строка плана группового ЗНП с комплектовочной операцией склада. */
    private static OrderScheduleRow scheduleRow(ProductionOrder order) {
        OperationType type = new OperationType();
        type.setCode("KIT_RECEIPT");

        OperationName name = new OperationName();
        name.setName("Комплектование");

        Operation operation = new Operation();
        operation.setId(9L);
        operation.setOperationType(type);
        operation.setOperationName(name);

        OrderScheduleRow row = new OrderScheduleRow();
        row.setId(ROW_ID);
        row.setOperation(operation);
        row.setProductionOrder(order);
        return row;
    }

    private static ProductionOrder order() {
        ProductionOrder order = new ProductionOrder();
        order.setId(41L);
        order.setNumber("ЗНП-9");
        return order;
    }

    /** Ячейка, принятая складом и отмеченная готовой. */
    private static ProductSerial acceptedCell() {
        ProductClassifier classifier = new ProductClassifier();
        classifier.setId(5L);
        classifier.setCode(5102);
        classifier.setName("ЯПР20М");

        ProductSerial serial = new ProductSerial();
        serial.setId(2L);
        serial.setProductType(ProductType.CELL);
        serial.setProductClassifier(classifier);
        serial.setAcceptedAt(MOMENT);
        serial.setCellReadyAt(MOMENT);
        return serial;
    }

    private static ru.company.production.entity.AppUser executor() {
        ru.company.production.entity.AppUser user =
                new ru.company.production.entity.AppUser();
        user.setId(3L);
        user.setUsername("spf-01");
        return user;
    }

    /* ============================================================
       ОТКАЗ: ПОЗИЦИЯ В ИЗОЛЯТОРЕ БРАКА
       ============================================================ */

    @Test
    @DisplayName("Ячейку из изолятора брака в ЗНП не привязывает")
    void rejectsCellFromQuarantine() {

        when(scheduleRowRepository.findRowForAttachById(ROW_ID))
                .thenReturn(Optional.of(scheduleRow(order())));
        when(serialRepository.findDetailsByFullNumbers(List.of(ISOLATED)))
                .thenReturn(List.of(acceptedCell()));
        when(quarantineTransferService.findActiveQuarantineNumbers())
                .thenReturn(Set.of(ISOLATED));

        WarehouseKittingService.KittingResult result =
                service.attachCellToZnp(
                        ROW_ID, ISOLATED, executor());

        assertFalse(result.completed());
        assertTrue(result.message().contains("изоляторе брака"),
                result.message());

        verify(serialProductionOrderRepository, never()).save(any());
    }

    @Test
    @DisplayName("Тот же номер вне изолятора привязывается")
    void attachesSameNumberWhenNotQuarantined() {

        when(scheduleRowRepository.findRowForAttachById(ROW_ID))
                .thenReturn(Optional.of(scheduleRow(order())));
        when(serialRepository.findDetailsByFullNumbers(List.of(ISOLATED)))
                .thenReturn(List.of(acceptedCell()));
        when(quarantineTransferService.findActiveQuarantineNumbers())
                .thenReturn(Set.of());
        when(serialProductionOrderRepository.existsActiveLinkBySerialId(2L))
                .thenReturn(false);
        when(itemRepository.findCarrierClassifierIdsOfZnp(41L))
                .thenReturn(List.of());
        when(scheduleRowRepository.countRowsOfProductionOrderAndOperation(
                any(), any())).thenReturn(0L);

        WarehouseKittingService.KittingResult result =
                service.attachCellToZnp(
                        ROW_ID, ISOLATED, executor());

        assertTrue(result.completed(), result.message());
        verify(serialProductionOrderRepository).save(any());
    }
}
