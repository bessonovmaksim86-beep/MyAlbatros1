package ru.company.production.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.company.production.entity.ProductSerialOperation;
import ru.company.production.entity.ProductSerialOperationStatus;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * План и факт цеховых операций по конкретным изделиям.
 *
 * Запросы построены так, чтобы матрица «изделие × операция» собиралась
 * одним обращением к базе: строки отдаются упорядоченными по изделию и
 * порядку операции, а сворачивание в колонки делает сервис.
 */
@Repository
public interface ProductSerialOperationRepository
        extends JpaRepository<ProductSerialOperation, Long> {

    /**
     * Все попытки операций изделия, упорядоченные для матрицы.
     *
     * Порядок: изделие → операция → попытка, поэтому переделки идут
     * сразу за первой попыткой и сервису не нужно сортировать в памяти.
     */
    @Query("""
            select op
            from ProductSerialOperation op
            left join fetch op.operation operation
            left join fetch operation.operationName operationName
            left join fetch op.executor executor
            left join fetch op.productionOrder ord
            where op.serial.id = :serialId
            order by op.seq, operationName.name, op.attempt
            """)
    List<ProductSerialOperation> findBySerialId(
            @Param("serialId") Long serialId
    );

    /**
     * Актуальные операции группы изделий — матрица одного заказа.
     *
     * Фильтр идёт по op.productionOrder, то есть по заказу, В РАМКАХ
     * КОТОРОГО операция выполнена (колонка production_order_id, V20), а
     * не по serial.issuedProductionOrder. Разница принципиальна:
     * issuedProductionOrder — заказ, выдавший серийник (ЗП), тогда как
     * матрицу строят по заказ-комплектующему (ЗНП), который входит в
     * изделие. Один ЗНП комплектует группу датчиков разных выпусков, а
     * один датчик укомплектован несколькими ЗНП, поэтому по заказу
     * выдачи номера матрица заказала бы чужие операции и потеряла свои.
     *
     * Актуальной считается попытка, после которой по этой же операции
     * нет следующей: переделанная попытка (REWORKED) из выдачи
     * исключается, видна только та, что в работе сейчас.
     */
    @Query("""
            select op
            from ProductSerialOperation op
            left join fetch op.serial serial
            left join fetch serial.productClassifier classifier
            left join fetch classifier.sensorCatalog
            left join fetch op.operation operation
            left join fetch operation.operationName operationName
            left join fetch op.executor executor
            where op.productionOrder.id = :productionOrderId
              and op.status <> :reworkedStatus
            order by serial.productType, serial.serial, op.seq
            """)
    List<ProductSerialOperation> findCurrentByProductionOrderId(
            @Param("productionOrderId") Long productionOrderId,
            @Param("reworkedStatus")
                    ProductSerialOperationStatus reworkedStatus
    );

    /**
     * Последняя попытка конкретной операции у изделия.
     *
     * По ней сервис решает, какой attempt записывать следующим при
     * возврате на доработку.
     */
    /*
     * Производный метод вместо @Query с limit: сортировка и отсечение
     * первой строки описаны именем, поэтому не зависят от того,
     * поддерживает ли диалект LIMIT в HQL.
     */
    Optional<ProductSerialOperation>
            findFirstBySerialIdAndOperationIdOrderByAttemptDesc(
                    Long serialId,
                    Long operationId
            );

    /**
     * Все попытки операции у изделия — для колонки истории переделок.
     */
    List<ProductSerialOperation>
            findBySerialIdAndOperationIdOrderByAttemptAsc(
                    Long serialId,
                    Long operationId
            );

    /**
     * Просроченные операции: план вышел, факта нет.
     *
     * today передаётся параметром, а не берётся из базы: запрос должен
     * оставаться воспроизводимым в тестах и не зависеть от часового
     * пояса сервера.
     */
    @Query("""
            select op
            from ProductSerialOperation op
            left join fetch op.serial serial
            left join fetch serial.productClassifier classifier
            left join fetch classifier.sensorCatalog
            left join fetch op.operation operation
            left join fetch operation.operationName operationName
            left join fetch op.executor executor
            where op.plannedDate is not null
              and op.actualDate is null
              and op.plannedDate < :today
              and op.status <> :reworkedStatus
            order by op.plannedDate, serial.productType, serial.serial
            """)
    List<ProductSerialOperation> findOverdue(
            @Param("today") LocalDate today,
            @Param("reworkedStatus")
                    ProductSerialOperationStatus reworkedStatus
    );

    /**
     * Операции, висящие на исполнителе, — его загрузка.
     */
    @Query("""
            select op
            from ProductSerialOperation op
            left join fetch op.serial serial
            left join fetch serial.productClassifier classifier
            left join fetch classifier.sensorCatalog
            left join fetch op.operation operation
            left join fetch operation.operationName operationName
            where op.executor.id = :executorId
              and op.actualDate is null
              and op.status <> :reworkedStatus
            order by op.plannedDate, serial.productType, serial.serial
            """)
    List<ProductSerialOperation> findOpenByExecutor(
            @Param("executorId") Long executorId,
            @Param("reworkedStatus")
                    ProductSerialOperationStatus reworkedStatus
    );

    /**
     * Операции, не закрытые по изделию: «что осталось сделать».
     */
    @Query("""
            select count(op)
            from ProductSerialOperation op
            where op.serial.id = :serialId
              and op.actualDate is null
              and op.status <> :reworkedStatus
            """)
    long countOpenBySerialId(
            @Param("serialId") Long serialId,
            @Param("reworkedStatus")
                    ProductSerialOperationStatus reworkedStatus
    );

    /**
     * Готовность изделия в процентах: доля закрытых попыток.
     *
     * Считаются только актуальные попытки — переделанные не должны
     * занижать процент.
     */
    @Query("""
            select count(op)
            from ProductSerialOperation op
            where op.serial.id = :serialId
              and op.status = :doneStatus
            """)
    long countDoneBySerialId(
            @Param("serialId") Long serialId,
            @Param("doneStatus") ProductSerialOperationStatus doneStatus
    );

    /**
     * Есть ли у изделия операции вообще — отличает «всё сделано»
     * от «маршрут не заведён».
     */
    boolean existsBySerial_Id(Long serialId);

    /**
     * Есть ли у изделия операции в рамках конкретного заказа.
     *
     * Нужен при отвязке заказ-комплектующего от датчика: снимать связь
     * нельзя, пока на неё ссылается история выполнения — иначе записи
     * остались бы на заказе, который к изделию больше не относится.
     */
    boolean existsBySerial_IdAndProductionOrder_Id(
            Long serialId,
            Long productionOrderId
    );

    /**
     * Есть ли по заказу проведения фактом ({@code actual_date}).
     *
     * Дата факта в рамках заказа — событие, а не намерение, и в
     * текущем маршруте её ставит склад: приёмка заказа-комплектующего
     * закрывает комплектовочную операцию принятых ячеек именно этим
     * заказом (см. ZnpAcceptanceService#closeKittingOperations). Поэтому
     * наличие такой строки и означает «по заказу проведены операции на
     * складе», а их отсутствие — что заказ содержит один план и
     * снимается с работы без потери истории.
     *
     * Переделанная попытка (REWORKED) тоже несёт дату факта: работа по
     * ней выполнялась, событие было — в отказе она участвует наравне с
     * закрытой.
     */
    @Query("""
            select count(op) > 0
            from ProductSerialOperation op
            where op.productionOrder.id = :productionOrderId
              and op.actualDate is not null
            """)
    boolean existsPerformedByProductionOrderId(
            @Param("productionOrderId") Long productionOrderId
    );

    /**
     * Есть ли факт по перечню изделий — независимо от того, каким
     * заказом операция поставлена.
     *
     * Нужен удалению выпуска (ЗП): сам выпуск складских операций не
     * проводит, но его датчики укомплектованы чужими ЗНП, и приёмка
     * такого ЗНП оставила факт на изделии выпуска. Освободить номер с
     * пройденной складом операцией — значит потерять прослеживаемость,
     * поэтому выпуск проверяют и по своим изделиям тоже.
     */
    @Query("""
            select count(op) > 0
            from ProductSerialOperation op
            where op.serial.id in :serialIds
              and op.actualDate is not null
            """)
    boolean existsPerformedBySerialIdIn(
            @Param("serialIds") java.util.Collection<Long> serialIds
    );

    /**
     * Снимает ЗАПЛАНИРОВАННЫЕ операции в рамках заказа при его удалении.
     *
     * Снятый с работы заказ не вправе оставлять в цеху и на складе
     * работу, которой не бывать: строка без факта — намерение, и
     * повисшая она осталась бы в матрице изделия и в загрузке
     * исполнителя. Проведённая строка — факт, она заказ переживает;
     * условие {@code actual_date is null} стоит и здесь, чтобы запрос
     * нельзя было применить к заказу с фактами в обход проверки сервиса.
     *
     * Только own-заказ: операции чужих ЗНП, вошедшие в те же изделия,
     * к удаляемому заказу не относятся (колонка production_order_id,
     * V20 — «в рамках какого заказа выполнена операция»).
     */
    @Modifying(
            flushAutomatically = true,
            clearAutomatically = true
    )
    @Query("""
            delete from ProductSerialOperation op
            where op.productionOrder.id = :productionOrderId
              and op.actualDate is null
            """)
    int deletePlannedByProductionOrderId(
            @Param("productionOrderId") Long productionOrderId
    );

    /**
     * Снимает статус «В работе» с незакрытых попыток при отмене заказа:
     * пакетный UPDATE вместо сохранения сотен сущностей по одной.
     */
    @Query("""
            select op
            from ProductSerialOperation op
            where op.productionOrder.id = :productionOrderId
              and op.actualDate is null
            """)
    List<ProductSerialOperation> findUnclosedByProductionOrderId(
            @Param("productionOrderId") Long productionOrderId
    );

    /*
     * ============================================================
     * Снятие плана цеховых операций при удалении заказа покупателя
     * ============================================================
     */

    /**
     * Удаляет ЗАПЛАНИРОВАННЫЕ операции изделий заказа покупателя.
     *
     * Заказ, снятый с работы, не должен оставлять в цехе работу, которой
     * не бывать: строка без факта — намерение, а не событие, и висеть
     * после удаления заказа она не вправе (исполнитель видел бы работу,
     * у которой нет основания).
     *
     * УДАЛЯЕТСЯ ТОЛЬКО ПЛАН (actual_date is null). Проведённая строка —
     * факт работы, он переживает заказ ровно так же, как строит отказ
     * весь заказ при попытке удаления (см.
     * CustomerOrderRepository#existsPerformedSerialOperation); условие
     * стоит и здесь, чтобы запрос нельзя было применить к заказу с
     * фактами в обход сервиса.
     *
     * Заказ относится к операции двумя путями, и проверяются оба — те
     * же две ветви, что у проверки перед удалением:
     *
     *   * операция поставлена в рамках заказа на производство, у
     *     которого это заказ покупателя основание (production_order_id,
     *     V20 — «в рамках какого заказа выполнена операция», а НЕ
     *     serial.issuedProductionOrder: один ЗНП комплектует датчики
     *     разных выпусков);
     *   * изделие зарезервировано позицией самого заказа покупателя
     *     (product_serials.customer_order_item_id, V45) — такие номера
     *     живут до всякого выпуска.
     *
     * Подзапросы ведут на ProductionOrder и ProductSerial, а не на свою
     * таблицу: MySQL отклоняет DELETE с подзапросом на удаляемую таблицу
     * (ERROR 1093), а по чужой таблице запрос проходит. Навигация идёт
     * по колонкам foreign key (op.productionOrder.id, op.serial.id), без
     * join — INNER join внутри OR отбросил бы строки, у которых
     * заполнена только одна из ветвей.
     *
     * Вызывать ДО освобождения резерва номеров: unlinkReleased...
     * обнуляет customer_order_item_id, и вторая ветвь перестала бы
     * что-либо находить.
     */
    @Modifying(
            flushAutomatically = true,
            clearAutomatically = true
    )
    @Query("""
            delete from ProductSerialOperation op
            where op.actualDate is null
              and (
                    op.productionOrder.id in (
                        select ord.id
                        from ProductionOrder ord
                        where ord.customerOrder.id = :customerOrderId
                    )
                 or op.serial.id in (
                        select serial.id
                        from ProductSerial serial
                        where serial.customerOrderItem
                                  .customerOrder.id = :customerOrderId
                    )
              )
            """)
    int deletePlannedByCustomerOrderId(
            @Param("customerOrderId") Long customerOrderId
    );
}