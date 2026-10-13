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
                    item.getFullNumber(), null,
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