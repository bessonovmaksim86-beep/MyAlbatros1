package ru.company.production.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.company.production.entity.*;
import ru.company.production.repository.SerialMovementEventRepository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Единая точка записи в журнал перемещений ячейки.
 *
 * Правило «какое событие и с какими реквизитами» живёт здесь, поэтому
 * точка вызова в сервисе-источнике — одна строка и не разъезжается по
 * модулям. Номер пишут строкой fullNumber (годен и для ячеек реестра,
 * и для номеров «всей партией»); serialId заполняют по возможности.
 */
@Service
@RequiredArgsConstructor
public class SerialMovementJournalService {

    private final SerialMovementEventRepository journalRepository;

    /** Ячейка осталась в изоляторе брака (первично или повторно). */
    @Transactional
    public void recordQuarantined(String fullNumber,
                                  Long serialId,
                                  AppUser actor,
                                  LocalDateTime moment) {
        journalRepository.save(newEvent(
                fullNumber, serialId, SerialMovementType.QUARANTINED,
                moment, actor, null, null, null));
    }

    /** Ячейка ушла поставщику. */
    @Transactional
    public void recordReturnedToSupplier(String fullNumber,
                                         Long serialId,
                                         AppUser actor,
                                         LocalDateTime moment) {
        journalRepository.save(newEvent(
                fullNumber, serialId, SerialMovementType.RETURNED_TO_SUPPLIER,
                moment, actor, null, null, null));
    }

    /** Ячейки приняты на склад после БВК. */
    @Transactional
    public void recordAccepted(List<ProductSerial> serials,
                               AppUser actor,
                               LocalDateTime moment) {
        for (ProductSerial serial : serials) {
            journalRepository.save(newEvent(
                    serial.getFullNumber(), serial.getId(),
                    SerialMovementType.ACCEPTED,
                    moment, actor, null, null, null));
        }
    }

    /** Ячейки включены в ремонтный ЗНП (состав — из строк заказа). */
    @Transactional
    public void recordRepairOrdered(RepairOrder order) {
        LocalDateTime moment = LocalDateTime.now();

        for (RepairOrderItem item : order.getItems()) {
            journalRepository.save(newEvent(
                    item.getFullNumber(), null,
                    SerialMovementType.REPAIR_ORDERED,
                    moment, order.getCreatedBy(), order,
                    item.getDefectGroup(), null));
        }
    }

    /** Ремонт принят (пара к REPAIR_ORDERED — срок ремонта). */
    @Transactional
    public void recordRepairDone(RepairOrder order) {
        LocalDateTime moment = LocalDateTime.now();

        for (RepairOrderItem item : order.getItems()) {
            journalRepository.save(newEvent(
                    item.getFullNumber(), null,
                    SerialMovementType.REPAIR_DONE,
                    moment, order.getCreatedBy(), order, null, null));
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
            InputControlDefectGroup defectGroup,
            String note) {

        SerialMovementEvent event = new SerialMovementEvent();
        event.setFullNumber(fullNumber);
        event.setSerialId(serialId);
        event.setEventType(type);
        event.setOccurredAt(moment);
        event.setActor(actor);
        event.setRepairOrder(repairOrder);
        event.setDefectGroup(defectGroup);
        event.setNote(note);

        return event;
    }
}