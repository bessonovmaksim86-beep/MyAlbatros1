package ru.company.production.service;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.company.production.dto.InputControlResult;
import ru.company.production.entity.AppUser;
import ru.company.production.entity.Operation;
import ru.company.production.entity.OperationName;
import ru.company.production.entity.ProductSerial;
import ru.company.production.entity.ProductSerialOperation;
import ru.company.production.entity.ProductSerialOperationStatus;
import ru.company.production.entity.ProductionOrder;
import ru.company.production.entity.SerialProductionOrder;
import ru.company.production.repository.ProductSerialOperationRepository;
import ru.company.production.repository.ProductSerialRepository;
import ru.company.production.repository.ProductionOrderRepository;
import ru.company.production.repository.SerialProductionOrderRepository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Приёмка на склад ячеек, вошедших в заказ-комплектующий (ЗНП).
 *
 * Раздел стоит после «Приёмки с БВК»: туда ячейка приходит подсборкой
 * и проходит входной контроль, а сюда попадает уже ячейкой, собранной
 * в состав конкретного ЗНП. Склад принимает не партию оприходования, а
 * заказ-комплектующий: единица приёмки — все непринятые ячейки состава
 * одного серийного ЗНП разом.
 *
 * Приёмка делает ровно два дела, и оба — события склада, а не
 * производства:
 *
 *   * отмечает принятые ячейки (product_serials.accepted_* — тот же
 *       механизм, что у приёмки после БВК, см. {@link BvkAcceptanceService});
 *   * закрывает комплектовочную операцию этих ячеек в рамках заказа
 *       (product_serial_operations.status → DONE с фактом), потому что
 *       для склада приёмка и есть подтверждение: ячейку укомплектовали
 *       и она на месте.
 *
 * Заказ выбирается по признаку серийного ЗНП (родитель + комплектовочная
 * операция заданы): групповые и ремонтные виды (V37/V38) приёмки ячеек
 * не имеют — у них нет ни выпуска-родителя, ни комплектовочной операции
 * (см. {@link ru.company.production.entity.ZnpKind#isSerialBased()}).
 *
 * Приёмку проводят один раз: повторное проведение отклоняется, а не
 * перетирает прежнюю отметку — иначе журнал перемещений заполнился бы
 * дублями ACCEPTED на одну ячейку.
 */
@Service
@RequiredArgsConstructor
public class ZnpAcceptanceService {

    private final ProductionOrderRepository productionOrderRepository;
    private final SerialProductionOrderRepository linkRepository;
    private final ProductSerialRepository serialRepository;
    private final ProductSerialOperationRepository serialOperationRepository;
    private final SerialMovementJournalService journal;

    /**
     * Серийные ЗНП с непринятыми ячейками — список раздела.
     *
     * Число непринятых ячеек в строке не считается: для этого на экране
     * приёмки есть детальный перечень одного заказа, а пакетная разбивка
     * по всем заказам страницы потребовала бы отдельной проекции, которой
     * здесь не на что опереться.
     */
    @Transactional(readOnly = true)
    public List<ProductionOrder> findPendingOrders() {
        return linkRepository.findPendingCellZnp();
    }

    /** Заказов, ожидающих приёмки ячеек: счётчик пункта меню. */
    @Transactional(readOnly = true)
    public long countPending() {
        return linkRepository.countPendingCellZnp();
    }

    /**
     * Данные экрана приёмки: заказ, его непринятые ячейки и подпись
     * комплектовочной операции.
     *
     * Заказ читается детальным запросом (родитель, изделие, операция —
     * связи ленивые), непринятые ячейки — отдельным запросом с fetch
     * классификатора, чтобы строка списка печатала полный номер.
     */
    @Transactional(readOnly = true)
    public ZnpAcceptanceView loadView(Long orderId) {

        ProductionOrder order = requireSerialZnp(orderId);

        List<SerialProductionOrder> cells =
                linkRepository.findPendingCellsByOrderId(orderId);

        return new ZnpAcceptanceView(
                order,
                cells,
                operationName(order)
        );
    }

    /**
     * Проводит приёмку: непринятые ячейки состава заказа отмечают
     * принятыми на склад, их комплектовочная операция закрывается фактом.
     *
     * Всё или ничего: принимают все непринятые ячейки заказа разом, и
     * если принять нечего (заказ уже принят целиком или ячеек нет),
     * сервис отказывает человеческим сообщением, а не проводит пустую
     * приёмку.
     */
    @Transactional
    public InputControlResult accept(Long orderId, AppUser receiver) {

        ProductionOrder order = requireSerialZnp(orderId);

        List<SerialProductionOrder> pending =
                linkRepository.findPendingCellsByOrderId(orderId);

        if (pending.isEmpty()) {
            return InputControlResult.rejected(
                    "В заказе «" + order.getNumber()
                            + "» нет непринятых ячеек — приёмку уже провели "
                            + "или в его составе ещё нет ячеек"
            );
        }

        LocalDateTime moment = LocalDateTime.now();

        List<ProductSerial> serials = pending.stream()
                .map(SerialProductionOrder::getSerial)
                .toList();

        List<Long> ids = serials.stream()
                .map(ProductSerial::getId)
                .toList();

        serialRepository.markAcceptedByIds(ids, moment, receiver);
        journal.recordAccepted(serials, receiver, moment);
        closeKittingOperations(orderId, Set.copyOf(ids), receiver, moment);

        return InputControlResult.accepted(
                "Принято на склад: " + ids.size() + " яч. по заказу «"
                        + order.getNumber() + "»"
        );
    }

    /**
     * Закрывает комплектовочную операцию принятых ячеек в рамках заказа.
     *
     * Берёт актуальные попытки операций именно этого ЗНП (по колонке
     * production_order_id, V20) и закрывает те, что относятся к принятым
     * ячейкам и ещё не закрыты фактом. Строку не выдумывает: операция
     * появляется при комплектовании, приёмка лишь подтверждает её
     * выполнение складом, поэтому незакрытых строк может и не быть —
     * тогда закрывать нечего, и это норма, а не ошибка.
     */
    private void closeKittingOperations(
            Long orderId,
            Set<Long> acceptedSerialIds,
            AppUser receiver,
            LocalDateTime moment) {

        LocalDate today = moment.toLocalDate();

        List<ProductSerialOperation> open =
                serialOperationRepository.findCurrentByProductionOrderId(
                        orderId,
                        ProductSerialOperationStatus.REWORKED
                ).stream()
                .filter(op -> op.getActualDate() == null)
                .filter(op -> op.getSerial() != null
                        && acceptedSerialIds.contains(op.getSerial().getId()))
                .toList();

        for (ProductSerialOperation operation : open) {
            operation.setStatus(ProductSerialOperationStatus.DONE);
            operation.setActualDate(today);
            operation.setPerformedBy(receiver);
        }

        if (!open.isEmpty()) {
            serialOperationRepository.saveAll(open);
        }
    }

    /**
     * Заказ обязан быть серийным комплектующим: только у него есть
     * выпускающий родитель и комплектовочная операция, а значит — есть
     * что принимать и чем закрывать приёмку.
     */
    private ProductionOrder requireSerialZnp(Long orderId) {

        ProductionOrder order = productionOrderRepository
                .findDetailedById(orderId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Заказ на производство не найден"
                ));

        if (!order.isComponent() || !order.isSerialBasedKind()) {
            throw new IllegalStateException(
                    "Приёмка ячеек проходит по серийному заказу-комплектующему, "
                            + "а заказ «" + order.getNumber() + "» им не является"
            );
        }

        return order;
    }

    /** Наименование комплектовочной операции заказа — текст вместо lazy. */
    private static String operationName(ProductionOrder order) {

        Operation operation = order.getProductionOperation();

        if (operation == null) {
            return "—";
        }

        OperationName name = operation.getOperationName();

        return name == null || name.getName() == null
                ? "—"
                : name.getName();
    }

    /**
     * Экран приёмки: заказ, его непринятые ячейки и подпись операции.
     *
     * {@code operationName} отдаётся строкой, а не через связь заказа:
     * open-in-view выключен, и обращение шаблона к наименованию операции
     * упало бы на ленивой связи.
     */
    public record ZnpAcceptanceView(
            ProductionOrder order,
            List<SerialProductionOrder> cells,
            String operationName
    ) {

        public List<String> cellNumbers() {
            return cells.stream()
                    .map(link -> link.getSerial().getFullNumber())
                    .collect(Collectors.toList());
        }
    }
}
