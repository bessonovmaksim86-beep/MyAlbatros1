package ru.company.production.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.company.production.dto.ControlParamValueForm;
import ru.company.production.entity.AppUser;
import ru.company.production.entity.ControlParam;
import ru.company.production.entity.ControlParamType;
import ru.company.production.entity.Operation;
import ru.company.production.entity.OperationControlParam;
import ru.company.production.entity.OrderScheduleRow;
import ru.company.production.entity.ProductClassifier;
import ru.company.production.entity.ProductSerial;
import ru.company.production.entity.SerialProductionOrder;
import ru.company.production.entity.TechProcess;
import ru.company.production.entity.TechProcessOperationControlParam;
import ru.company.production.repository.OperationControlParamRepository;
import ru.company.production.repository.OrderScheduleRowRepository;
import ru.company.production.repository.ProductionOrderItemRepository;
import ru.company.production.repository.ProductSerialRepository;
import ru.company.production.repository.SerialProductionOrderRepository;
import ru.company.production.repository.TechProcessOperationControlParamRepository;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Проведение операции с параметром «про ячейку» (режим CELL).
 *
 * В этом режиме исполнитель ничего не вносит — поля ввода шаблон не
 * рисует, значение берётся из экземпляра. Поэтому несовпадение
 * классификатора не имеет права срывать проведение необязательного
 * параметра: у исполнителя просто нет доступного действия, а работа
 * осталась бы закрытой наглухо. Обязательный параметр такое
 * несовпадение по-прежнему держит.
 */
@ExtendWith(MockitoExtension.class)
class OperationControlParamServiceCellModeTest {

    @Mock private OrderScheduleRowRepository scheduleRowRepo;
    @Mock private TechProcessOperationControlParamRepository tpOcpRepo;
    @Mock private OperationControlParamRepository ocpRepo;
    @Mock private SerialProductionOrderRepository serialLinkRepo;
    @Mock private ProductionOrderItemRepository productionItemRepo;
    @Mock private ProductSerialRepository productSerialRepo;
    @Mock private WorkshopDistributionService distributionService;

    private OperationControlParamService service;

    /*
     * Порядок аргументов повторяет порядок полей сервиса, который
     * задаёт Lombok @RequiredArgsConstructor: конструктор позиционный.
     */
    @BeforeEach
    void setUp() {
        service = new OperationControlParamService(
                scheduleRowRepo,
                tpOcpRepo,
                ocpRepo,
                serialLinkRepo,
                productionItemRepo,
                productSerialRepo,
                distributionService
        );
    }

    @Test
    @DisplayName("Несовпадение ячейки не блокирует необязательный параметр")
    void optionalCellParamIgnoresClassifierMismatch() {
        stubRow();
        TechProcessOperationControlParam link = cellLink(false);
        when(tpOcpRepo.findForOperationNode(anyLong(), anyLong()))
                .thenReturn(List.of(link));

        AppUser performer = new AppUser();

        assertDoesNotThrow(() -> service.completeWithControlParams(
                664L, performer, List.of(submittedForm(link))));

        verify(distributionService).completeRow(664L, performer);

        /*
         * Факт записывается с обеими ссылками: несоответствие видно
         * в журнале, а не молча проигнорировано.
         */
        OperationControlParam fact = capturedFact();
        assertEquals(1567L, fact.getCellClassifier().getId());
        assertEquals(1192L, fact.getActualClassifier().getId());
    }

    @Test
    @DisplayName("Несовпадение ячейки блокирует обязательный параметр")
    void mandatoryCellParamRejectsClassifierMismatch() {
        stubRow();
        TechProcessOperationControlParam link = cellLink(true);
        when(tpOcpRepo.findForOperationNode(anyLong(), anyLong()))
                .thenReturn(List.of(link));

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> service.completeWithControlParams(
                        664L, new AppUser(), List.of(submittedForm(link))));

        assertEquals(true, error.getMessage().contains("Ячейка №"));
        verify(distributionService, never()).completeRow(anyLong(), any());
        verify(ocpRepo, never()).save(any());
    }

    @Test
    @DisplayName("Необязательный параметр без номера ячейки проводится без факта")
    void optionalCellParamWithoutSerialIsSkipped() {
        OrderScheduleRow row = row();
        when(scheduleRowRepo.findById(664L)).thenReturn(Optional.of(row));
        when(serialLinkRepo.findByProductionOrderId(31L)).thenReturn(List.of());

        TechProcessOperationControlParam link = cellLink(false);
        when(tpOcpRepo.findForOperationNode(anyLong(), anyLong()))
                .thenReturn(List.of(link));

        AppUser performer = new AppUser();

        assertDoesNotThrow(() -> service.completeWithControlParams(
                664L, performer, List.of(submittedForm(link))));

        verify(distributionService).completeRow(664L, performer);
        verify(ocpRepo, never()).save(any());
    }

    /*
     * Строка графика «Гравировка корпуса» серийного экземпляра:
     * экземпляр берётся из матрицы заказа по instanceIndex.
     */
    private void stubRow() {
        when(scheduleRowRepo.findById(664L)).thenReturn(Optional.of(row()));

        SerialProductionOrder matrixLink = new SerialProductionOrder();
        matrixLink.setSerial(serial());

        when(serialLinkRepo.findByProductionOrderId(31L))
                .thenReturn(List.of(matrixLink));
    }

    private OrderScheduleRow row() {
        TechProcess process = new TechProcess();
        process.setId(1L);

        Operation operation = new Operation();
        operation.setId(1L);

        ru.company.production.entity.ProductionOrder order =
                new ru.company.production.entity.ProductionOrder();
        order.setId(31L);

        OrderScheduleRow row = new OrderScheduleRow();
        row.setId(664L);
        row.setTechProcess(process);
        row.setOperation(operation);
        row.setProductionOrder(order);
        row.setInstanceIndex(0);

        return row;
    }

    /*
     * Экземпляр 119200067 — датчик 1192, тогда как параметр требует
     * ячейку 7777 («ЯПР1»): тот же набор, что дал отказ на форме.
     */
    private ProductSerial serial() {
        ProductClassifier sensor = new ProductClassifier();
        sensor.setId(1192L);
        sensor.setName("Датчик");

        ProductSerial serial = new ProductSerial();
        serial.setId(120L);
        serial.setProductClassifier(sensor);

        return serial;
    }

    private TechProcessOperationControlParam cellLink(boolean mandatory) {
        ControlParam param = new ControlParam();
        param.setId(2L);
        param.setName("Ячейка №");
        param.setType(ControlParamType.TEXT_INPUT);

        ProductClassifier required = new ProductClassifier();
        required.setId(1567L);
        required.setName("ЯПР1");

        TechProcessOperationControlParam link =
                new TechProcessOperationControlParam();
        link.setId(18L);
        link.setControlParam(param);
        link.setCellClassifier(required);
        link.setMandatory(mandatory);

        return link;
    }

    private ControlParamValueForm submittedForm(
            TechProcessOperationControlParam link
    ) {
        ControlParamValueForm form = new ControlParamValueForm();
        form.setParamId(link.getId());

        return form;
    }

    private OperationControlParam capturedFact() {
        ArgumentCaptor<OperationControlParam> captor =
                ArgumentCaptor.forClass(OperationControlParam.class);

        verify(ocpRepo).save(captor.capture());

        return captor.getValue();
    }
}
