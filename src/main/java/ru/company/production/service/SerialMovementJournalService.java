package ru.company.production.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.company.production.entity.*;
import ru.company.production.repository.ProductSerialRepository;
import ru.company.production.repository.SerialMovementEventRepository;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Единая точка записи в журнал перемещений ячейки.
 *
 * Правило «какое событие и с какими реквизитами» живёт здесь, поэтому
 * точка вызова в сервисе-источнике — одна строка и не разъезжается по
 * модулям. Номер пишут строкой fullNumber (годен и для ячеек реестра,
 * и для номеров «всей партией»); serialId заполняют по возможности.
 *
 * Реквизиты состояния (статус реестра, разновидность, изделие) сервис
 * дописывает сам: иначе каждый вызывающий модуль обязан был бы знать,
 * что паспорт читает состояние НА МОМЕНТ события, а не текущие колонки
 * {@code product_serials}, — и обязательно бы где-то ошибся.
 */
@Service
@RequiredArgsConstructor
public class SerialMovementJournalService {

    private final SerialMovementEventRepository journalRepository;

    /*
     * Нужен, чтобы по номеру достать строку реестра: в журнал попадают и
     * номера вне реестра, и резолв обязан завершаться пустым результатом,
     * а не ошибкой.
     */
    private final ProductSerialRepository serialRepository;

    /**
     * Решение БВК по группе брака, проведённое поштучно.
     *
     * Одно попадание в изолятор — одно событие, даже если номер
     * забракован по двум причинам сразу (V64): число циклов брака считают
     * как COUNT(QUARANTINED), и раздувание состава событиями исказило бы
     * его. Поэтому пишут по решению группы, а все её причины складывают
     * в note.
     *
     * Идентификаторы реестра резолвят одной пачкой на всю группу: партия
     * — это сотни номеров, а запрос на каждую ячейку обернулся бы
     * медленным закрытием карточки.
     *
     * @param decision изолятор брака либо возврат поставщику
     * @param reason   причина группы для паспорта
     */
    @Transactional
    public void recordDefectDecision(List<String> fullNumbers,
                                     InputControlDecision decision,
                                     AppUser actor,
                                     LocalDateTime moment,
                                     InputControlDefectGroup defectGroup,
                                     CellReceivingBatch batch,
                                     String reason) {

        if (fullNumbers == null || fullNumbers.isEmpty()) {
            return;
        }

        SerialMovementType type =
                decision == InputControlDecision.RETURN_TO_SUPPLIER
                        ? SerialMovementType.RETURNED_TO_SUPPLIER
                        : SerialMovementType.QUARANTINED;

        Map<String, Long> knownIds = serialIdsOf(fullNumbers);

        for (String fullNumber : fullNumbers) {
            SerialMovementEvent event = newEvent(
                    fullNumber, knownIds.get(fullNumber), type,
                    moment, actor, null, null, defectGroup);

            applyBatchState(event, batch, reason);
            journalRepository.save(event);
        }
    }

    /**
     * Ячейки прошли входной контроль.
     *
     * Отметку ставят поштучно, а карточка закрывается по партии: без
     * этой строки в паспорте ячейки не было бы видно, что контроль она
     * проходила, — отметка живёт в колонках реестра, которые следующее
     * движение перечёркивает.
     */
    @Transactional
    public void recordInputControlPassed(List<ProductSerial> serials,
                                         InputControlType type,
                                         AppUser actor,
                                         LocalDateTime moment) {
        for (ProductSerial serial : serials) {
            SerialMovementEvent event = newEvent(
                    serial.getFullNumber(), serial.getId(),
                    SerialMovementType.INPUT_CONTROL_PASSED,
                    moment, actor, null, null, null);

            event.setInputControlType(type);
            applySerialState(event, serial);

            journalRepository.save(event);
        }
    }

    /**
     * Ячейки приняты на склад после БВК.
     *
     * Разновидность ставят явно, а не выводят из acceptedAt: событие
     * пишут сразу после отметки приёмки, а её bulk-UPDATE очищает
     * контекст, и в загруженной строке колонка ещё старая. Смысл события
     * — «подсборка стала ячейкой», и паспорт обязан показать это.
     */
    @Transactional
    public void recordAccepted(List<ProductSerial> serials,
                               AppUser actor,
                               LocalDateTime moment) {
        for (ProductSerial serial : serials) {
            SerialMovementEvent event = newEvent(
                    serial.getFullNumber(), serial.getId(),
                    SerialMovementType.ACCEPTED,
                    moment, actor, null, null, null);

            applySerialState(event, serial);
            event.setCellKind(QuarantineCellKind.CELL);

            journalRepository.save(event);
        }
    }

    /** Ячейки оприходованы складом по приходному ордеру. */
    @Transactional
    public void recordReceived(List<ProductSerial> serials,
                               CellReceivingBatch batch,
                               AppUser actor,
                               LocalDateTime moment) {
        for (ProductSerial serial : serials) {
            SerialMovementEvent event = newEvent(
                    serial.getFullNumber(), serial.getId(),
                    SerialMovementType.RECEIVED,
                    moment, actor, null, null, null);

            applySerialState(event, serial);
            event.setReceivingBatch(batch);
            event.setCellKind(QuarantineCellKind.ofAccepted(false));

            journalRepository.save(event);
        }
    }

    /** Номер выдан выпуском производства. */
    @Transactional
    public void recordIssued(List<ProductSerial> serials,
                             ProductionOrder order,
                             AppUser actor,
                             LocalDateTime moment) {
        for (ProductSerial serial : serials) {
            SerialMovementEvent event = newEvent(
                    serial.getFullNumber(), serial.getId(),
                    SerialMovementType.ISSUED,
                    moment, actor, null, null, null);

            applySerialState(event, serial);
            event.setProductionOrder(order);
            event.setCellKind(QuarantineCellKind.CELL);

            journalRepository.save(event);
        }
    }

    /**
     * Выпуск, выдавший номера, отменён: номера освобождены.
     *
     * Без строки паспорт показал бы выпуск, которого больше нет, а
     * освобождение номера осталось бы необъяснённым.
     */
    @Transactional
    public void recordReleaseCancelled(List<ProductSerial> serials,
                                       ProductionOrder order,
                                       AppUser actor,
                                       LocalDateTime moment) {
        for (ProductSerial serial : serials) {
            SerialMovementEvent event = newEvent(
                    serial.getFullNumber(), serial.getId(),
                    SerialMovementType.RELEASE_CANCELLED,
                    moment, actor, null, null, null);

            applySerialState(event, serial);
            event.setProductionOrder(order);
            event.setSerialStatus(ProductSerialStatus.NEW);

            journalRepository.save(event);
        }
    }

    /**
     * Ячейки перемещены из изолятора брака на склад СПФ.
     *
     * Записывается контролёром БВК при перемещении: по журналу видно,
     * что ячейка покинула изолятор не в ремонт и не к поставщику, а
     * ушла на приёмку складу полуфабрикатов.
     *
     * Проводка несёт разновидность, изделие, партию и причину, которые
     * контролёр видел в изоляторе, и паспорт обязан показать именно их,
     * а не то, чем ячейка стала после приёмки складом.
     */
    @Transactional
    public void recordTransferredToWarehouse(List<QuarantineTransfer> transfers,
                                             AppUser actor,
                                             LocalDateTime moment) {
        for (QuarantineTransfer transfer : transfers) {
            SerialMovementEvent event = newEvent(
                    transfer.getFullNumber(),
                    serialIdOf(transfer.getFullNumber()),
                    SerialMovementType.TRANSFERRED_TO_WAREHOUSE,
                    moment, actor, null, null, transfer.getDefectGroup());

            event.setCellKind(transfer.getCellKind());
            event.setProductClassifier(transfer.getProductClassifier());
            event.setReceivingBatch(transfer.getReceivingBatch());
            event.setQuarantineTransfer(transfer);
            event.setNote(truncate(transfer.getReason(), 500));

            journalRepository.save(event);
        }
    }

    /**
     * Склад вернул ячейки в изолятор брака.
     *
     * Строку перемещения удаляют, и без этой отметки возврат выглядел бы
     * как исчезновение ячейки из изолятора: по журналу нельзя было бы
     * понять, что ячейка никуда не уходила.
     */
    @Transactional
    public void recordReturnedToQuarantine(List<QuarantineTransfer> transfers,
                                           AppUser actor,
                                           LocalDateTime moment) {
        for (QuarantineTransfer transfer : transfers) {
            SerialMovementEvent event = newEvent(
                    transfer.getFullNumber(),
                    serialIdOf(transfer.getFullNumber()),
                    SerialMovementType.RETURNED_TO_QUARANTINE,
                    moment, actor, null, null, transfer.getDefectGroup());

            event.setCellKind(transfer.getCellKind());
            event.setProductClassifier(transfer.getProductClassifier());
            event.setReceivingBatch(transfer.getReceivingBatch());
            event.setNote(truncate(transfer.getReason(), 500));

            journalRepository.save(event);
        }
    }

    /** Ячейки включены в ремонтный ЗНП (состав — из строк заказа). */
    @Transactional
    public void recordRepairOrdered(RepairOrder order) {
        LocalDateTime moment = LocalDateTime.now();

        for (RepairOrderItem item : order.getItems()) {
            SerialMovementEvent event = newEvent(
                    item.getFullNumber(), serialIdOf(item.getFullNumber()),
                    SerialMovementType.REPAIR_ORDERED,
                    moment, order.getCreatedBy(), order, null,
                    item.getDefectGroup());

            applyItemState(event, item);
            journalRepository.save(event);
        }
    }

    /** Ремонт принят (пара к REPAIR_ORDERED — срок ремонта). */
    @Transactional
    public void recordRepairDone(RepairOrder order) {
        LocalDateTime moment = LocalDateTime.now();

        for (RepairOrderItem item : order.getItems()) {
            SerialMovementEvent event = newEvent(
                    item.getFullNumber(), serialIdOf(item.getFullNumber()),
                    SerialMovementType.REPAIR_DONE,
                    moment, order.getCreatedBy(), order, null, null);

            applyItemState(event, item);
            journalRepository.save(event);
        }
    }

    /**
     * Ремонтный ЗНП отменён: ячейки вернулись в изолятор.
     *
     * Пара к REPAIR_ORDERED. Без неё номер навсегда числился бы «включён
     * в ЗНП», и по журналу нельзя было бы отличить отремонтированную
     * ячейку от той, чей заказ сняли.
     */
    @Transactional
    public void recordRepairCancelled(RepairOrder order) {
        LocalDateTime moment = LocalDateTime.now();

        for (RepairOrderItem item : order.getItems()) {
            SerialMovementEvent event = newEvent(
                    item.getFullNumber(), serialIdOf(item.getFullNumber()),
                    SerialMovementType.REPAIR_CANCELLED,
                    moment, order.getCreatedBy(), order, null,
                    item.getDefectGroup());

            applyItemState(event, item);
            journalRepository.save(event);
        }
    }

    /**
     * Ячейки взяты из изолятора в ремонтный заказ-комплектующий.
     *
     * Заказ кладётся в свободную колонку production_order_id (V42): у
     * события уже есть ремонтный заказ службы качества, и смешивать их
     * в одной колонке нельзя — «заказ на ремонт» и «заказ-комплектующий
     * на производство» разные документы, а по журналу нужно различать,
     * кто именно забрал ячейку.
     *
     * Момент передаётся параметром, а не берётся здесь: проводка
     * состава и журнала обязана нести одну и ту же дату, иначе в
     * карточке ячейки комплектование и её строка в заказе
     * разошлись бы по времени.
     */
    @Transactional
    public void recordKittedToRepairZnp(List<RepairKittingItem> items,
                                        ProductionOrder order,
                                        AppUser actor,
                                        LocalDateTime moment) {
        for (RepairKittingItem item : items) {
            SerialMovementEvent event = newEvent(
                    item.getFullNumber(), serialIdOf(item.getFullNumber()),
                    SerialMovementType.KITTED_TO_REPAIR_ZNP,
                    moment, actor, null, null, null);

            event.setProductionOrder(order);

            journalRepository.save(event);
        }
    }

    /**
     * Ячейки внесены в ЗНП (склад полуфабрикатов).
     *
     * Обратное к ACCEPTED: ячейка уходит со склада в производство.
     * Заказ-комплектующий (V20) кладётся в колонку production_order_id,
     * чтобы по журналу можно было понять, в какой именно заказ ушла
     * ячейка.
     */
    @Transactional
    public void recordInProduction(List<ProductSerial> serials,
                                   ProductionOrder order,
                                   AppUser actor) {
        for (ProductSerial serial : serials) {
            SerialMovementEvent event = newEvent(
                    serial.getFullNumber(), serial.getId(),
                    SerialMovementType.IN_PRODUCTION,
                    LocalDateTime.now(), actor, null, null, null);

            event.setProductionOrder(order);
            applySerialState(event, serial);

            journalRepository.save(event);
        }
    }

    /** Хронология номера (карточка/отчёт). */
    @Transactional(readOnly = true)
    public List<SerialMovementEvent> historyOf(String fullNumber) {
        return journalRepository.findByFullNumberOrderByOccurredAtAsc(fullNumber);
    }

    /** Сколько раз номер падал в изолятор брака — «циклы брака». */
    @Transactional(readOnly = true)
    public long quarantineCycles(String fullNumber) {
        return journalRepository.countByFullNumberAndEventType(
                fullNumber, SerialMovementType.QUARANTINED);
    }

    private static SerialMovementEvent newEvent(
            String fullNumber,
            Long serialId,
            SerialMovementType type,
            LocalDateTime moment,
            AppUser actor,
            RepairOrder repairOrder,
            ProductionOrder productionOrder,
            InputControlDefectGroup defectGroup) {

        SerialMovementEvent event = new SerialMovementEvent();
        event.setFullNumber(fullNumber);
        event.setSerialId(serialId);
        event.setEventType(type);
        event.setOccurredAt(moment);
        event.setActor(actor);
        event.setRepairOrder(repairOrder);
        event.setProductionOrder(productionOrder);
        event.setDefectGroup(defectGroup);

        return event;
    }

    /**
     * Состояние ячейки на момент события — по строке реестра.
     *
     * Статус и изделие копируют, а не читают по ссылке в паспорте:
     * {@code product_serials} перечёркивает следующее движение.
     * Разновидность выводят по приёмке складом (см.
     * {@link QuarantineCellKind}): непринятая партия — подсборка.
     */
    private static void applySerialState(SerialMovementEvent event,
                                         ProductSerial serial) {

        event.setSerialStatus(serial.getStatus());
        event.setProductClassifier(serial.getProductClassifier());
        event.setReceivingBatch(serial.getReceivingBatch());
        event.setCellKind(QuarantineCellKind.ofAccepted(serial.isAccepted()));
    }

    /**
     * Состояние ячейки по партии прихода — для номеров вне реестра.
     *
     * Строки реестра может не быть: в изолятор попадают номера «всей
     * партией» ещё до оприходования, и событие обязан сохранить и для
     * них. Статус реестра тогда остаётся пустым — его просто нет.
     */
    private static void applyBatchState(SerialMovementEvent event,
                                        CellReceivingBatch batch,
                                        String reason) {

        if (batch != null) {
            event.setProductClassifier(batch.getCellClassifier());
            event.setReceivingBatch(batch);
            event.setCellKind(
                    QuarantineCellKind.ofAccepted(batch.getAcceptedAt() != null));
        }

        event.setNote(truncate(reason, 500));
    }

    /**
     * Состояние ячейки по строке ремонтного заказа.
     *
     * Строка ЗНП держит изделие и разновидность, скопированные на момент
     * оформления заказа, — они и есть состояние ячейки на дату события.
     */
    private void applyItemState(SerialMovementEvent event,
                                RepairOrderItem item) {

        event.setProductClassifier(item.getProductClassifier());
        event.setCellKind(item.getCellKind());

        CellReceivingBatch batch = batchOf(item.getDefectGroup());

        if (batch != null) {
            event.setReceivingBatch(batch);
        }
    }

    /**
     * Строка реестра по полному номеру, если номер в него внесён.
     *
     * Отсутствие строки — обычный случай (номера «всей партией»), а не
     * ошибка, поэтому событие сохраняет и заполняет идентификатор по
     * возможности.
     */
    private Long serialIdOf(String fullNumber) {
        return serialRepository.findDetailsByFullNumbers(List.of(fullNumber))
                .stream()
                .map(ProductSerial::getId)
                .findFirst()
                .orElse(null);
    }

    /**
     * Идентификаторы реестра пачкой: номер → id строки.
     *
     * Номера вне реестра в отклик не попадают вовсе, и вызывающий код
     * пишет событие с пустым serialId — обычный случай для «всей партии»
     * из прихода, ещё не оприходованной поштучно.
     */
    private Map<String, Long> serialIdsOf(List<String> fullNumbers) {

        Map<String, Long> ids = new LinkedHashMap<>();

        serialRepository.findDetailsByFullNumbers(fullNumbers)
                .forEach(serial -> ids.put(serial.getFullNumber(), serial.getId()));

        return ids;
    }

    /** Партия группы брака: связь ленивая, читается внутри транзакции. */
    private static CellReceivingBatch batchOf(InputControlDefectGroup group) {
        if (group == null || group.getCard() == null) {
            return null;
        }

        return group.getCard().getBatch();
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }

        String trimmed = value.trim();

        return trimmed.length() <= maxLength
                ? trimmed
                : trimmed.substring(0, maxLength);
    }
}
