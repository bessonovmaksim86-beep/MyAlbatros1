package ru.company.production.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.company.production.entity.SerialMovementEvent;
import ru.company.production.entity.SerialMovementType;

import java.time.LocalDateTime;
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

    boolean existsByFullNumber(String fullNumber);

    /**
     * Номера, упомянутые в журнале, — строки поиска по паспорту.
     *
     * Ищут по журналу, а не по реестру: в изолятор попадают и номера
     * партий прихода, ещё не внесённые в {@code product_serials}, и для
     * них паспорт нужен в первую очередь — именно его движение и
     * потерялось. Отдают distinct: по одному номеру строк несколько, а
     * в выдаче поиска номер обязан стоять один раз.
     *
     * Спецсимволы LIKE вычищает вызывающий сервис: номер — девять цифр,
     * и «%» в вводе означает ошибку ввода, а не намерение.
     */
    @Query("""
            select distinct event.fullNumber
            from SerialMovementEvent event
            where event.fullNumber like concat('%', :term, '%')
            order by event.fullNumber asc
            """)
    List<String> searchFullNumbers(@Param("term") String term,
                                   Pageable pageable);

    /**
     * Хронология номера со всеми реквизитами — данные паспорта.
     *
     * Отличается от {@link #findByFullNumberOrderByOccurredAtAsc}:
     * {@code open-in-view} выключен, а паспорту нужны исполнитель, заказ
     * (ремонтный или производства), изделие, партия и перемещение — без
     * {@code fetch} каждая из этих связей оборвалась бы на загрузке
     * страницы. Порядок — строго по моменту события: паспорт читают
     * сверху вниз как пройденный путь.
     */
    @Query("""
            select event
            from SerialMovementEvent event
            left join fetch event.actor actor
            left join fetch event.repairOrder repairOrder
            left join fetch event.productionOrder productionOrder
            left join fetch event.productClassifier classifier
            left join fetch classifier.sensorCatalog
            left join fetch event.receivingBatch batch
            left join fetch event.quarantineTransfer transfer
            where event.fullNumber = :fullNumber
            order by event.occurredAt asc, event.id asc
            """)
    List<SerialMovementEvent> findHistoryByFullNumber(
            @Param("fullNumber") String fullNumber);

    /* Моменты первого и последнего события — срок жизни номера. */

    @Query("""
            select min(event.occurredAt)
            from SerialMovementEvent event
            where event.fullNumber = :fullNumber
            """)
    LocalDateTime findFirstMoment(@Param("fullNumber") String fullNumber);

    @Query("""
            select max(event.occurredAt)
            from SerialMovementEvent event
            where event.fullNumber = :fullNumber
            """)
    LocalDateTime findLastMoment(@Param("fullNumber") String fullNumber);

    /*
     * ============================================================
     * Раздел «Очистка данных» (администратор)
     *
     * Журнал ссылается на заказ, номер и партию ключами с RESTRICT
     * (V42/V65), поэтому ни заказ, ни номер, ни партия не уйдут, пока
     * строки журнала на них указывают. Отсюда два вида запросов:
     * отвязать (когда событие остаётся нужным в паспорте) и удалить
     * (когда вместе с номером исчезает и то, о чём событие говорило).
     * ============================================================
     */

    long countByProductionOrderId(Long productionOrderId);

    long countByReceivingBatchId(Long receivingBatchId);

    long countBySerialIdIn(java.util.Collection<Long> serialIds);

    /**
     * Снимает ссылку на удаляемый заказ, строки журнала сохраняя.
     *
     * Событие описывает путь номера, а не заказ: «выпущена
     * производством» остаётся правдой и после того, как выпуск удалён,
     * поэтому строку не удаляют, а отвязывают — иначе из паспорта
     * исчезла бы половина пройденного номером пути ради удаления
     * документа, которого больше нет.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update SerialMovementEvent event
            set event.productionOrder = null
            where event.productionOrder.id = :productionOrderId
            """)
    int detachProductionOrder(
            @Param("productionOrderId") Long productionOrderId
    );

    /**
     * Удаляет события удаляемых номеров.
     *
     * Здесь отвязать нельзя: {@code chk_sme_has_identifier} требует,
     * чтобы у строки оставался хотя бы идентификатор, и строка о
     * номере, которого больше нет в реестре и который вернули в свободный
     * ряд, показала бы в паспорте движение несуществующего изделия.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            delete from SerialMovementEvent event
            where event.serialId in :serialIds
            """)
    int deleteBySerialIdIn(
            @Param("serialIds") java.util.Collection<Long> serialIds
    );

    /** Снимает ссылку на откатываемую партию, события сохраняя. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update SerialMovementEvent event
            set event.receivingBatch = null
            where event.receivingBatch.id = :receivingBatchId
            """)
    int detachReceivingBatch(
            @Param("receivingBatchId") Long receivingBatchId
    );

    /** Удаляет события прихода откатываемой партии. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            delete from SerialMovementEvent event
            where event.receivingBatch.id = :receivingBatchId
            """)
    int deleteByReceivingBatchId(
            @Param("receivingBatchId") Long receivingBatchId
    );
}