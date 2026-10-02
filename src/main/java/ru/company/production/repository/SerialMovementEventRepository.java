package ru.company.production.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.company.production.entity.SerialMovementEvent;
import ru.company.production.entity.SerialMovementType;

import java.util.List;

/**
 * Журнал перемещений ячейки вокруг неизменного номера.
 *
 * Главный запрос — хронология одного номера (по нему строят и число
 * циклов брака, и сроки ремонта). Счётчик изоляций по номеру отдаёт
 * {@code countByFullNumberAndEventType} — это и есть «сколько раз
 * ячейка падала в брак».
 */
public interface SerialMovementEventRepository
        extends JpaRepository<SerialMovementEvent, Long> {

    List<SerialMovementEvent> findByFullNumberOrderByOccurredAtAsc(
            String fullNumber);

    List<SerialMovementEvent> findByRepairOrderIdOrderByOccurredAtAsc(
            Long repairOrderId);

    long countByFullNumberAndEventType(String fullNumber,
                                       SerialMovementType type);
}