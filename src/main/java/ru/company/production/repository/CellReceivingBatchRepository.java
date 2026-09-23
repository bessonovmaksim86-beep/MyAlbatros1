package ru.company.production.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.company.production.entity.CellReceivingBatch;

import java.util.List;
import java.util.Optional;

@Repository
public interface CellReceivingBatchRepository
        extends JpaRepository<CellReceivingBatch, Long> {

    /**
     * Партии вместе с классификатором и автором: список и карточка
     * выводят наименование ячейки и ФИО кладовщика, а open-in-view
     * выключен — ленивая ссылка вне транзакции не читается.
     */
    @Query("""
            select batch
            from CellReceivingBatch batch
            left join fetch batch.cellClassifier classifier
            left join fetch classifier.sensorCatalog
            left join fetch batch.createdBy author
            left join fetch batch.acceptedBy receiver
            order by batch.createdAt desc, batch.id desc
            """)
    List<CellReceivingBatch> findAllDetailed();

    @Query("""
            select batch
            from CellReceivingBatch batch
            left join fetch batch.cellClassifier classifier
            left join fetch classifier.sensorCatalog
            left join fetch batch.createdBy author
            left join fetch batch.acceptedBy receiver
            where batch.id = :id
            """)
    Optional<CellReceivingBatch> findDetailedById(@Param("id") Long id);

    /**
     * Незакрытые приходы: номера под них зарезервированы, но в реестр
     * они ещё не попали. Именно эти партии оператору нужно довести до
     * сканирования — счётчик стоит в боковом меню.
     */
    long countByConfirmedAtIsNull();

    /**
     * Партии, вернувшиеся от БВК: приход закрыт, проверка входного
     * контроля завершена, открытой карточки нет.
     *
     * Открытая карточка означает, что БВК ещё работает с партией, —
     * на склад возвращать нечего. Непринятые партии идут первыми:
     * экран приёмки открывают именно ради них.
     */
    @Query("""
            select batch
            from CellReceivingBatch batch
            left join fetch batch.cellClassifier classifier
            left join fetch classifier.sensorCatalog
            left join fetch batch.createdBy author
            left join fetch batch.acceptedBy receiver
            where batch.confirmedAt is not null
              and exists (
                  select 1
                  from InputControlCard card
                  where card.batch.id = batch.id
                    and card.status =
                        ru.company.production.entity.InputControlStatus.COMPLETED
              )
              and not exists (
                  select 1
                  from InputControlCard card
                  where card.batch.id = batch.id
                    and card.status =
                        ru.company.production.entity.InputControlStatus.IN_WORK
              )
            order by
                case when batch.acceptedAt is null then 0 else 1 end asc,
                batch.createdAt desc,
                batch.id desc
            """)
    List<CellReceivingBatch> findBvkCheckedBatches();

    /**
     * Партий, ожидающих приёмки с БВК: приход закрыт, но склад партию
     * ещё не принимал.
     *
     * Учитывают только партии с завершённой проверкой входного
     * контроля: до её итога неизвестно, какие ячейки отсеяны на брак,
     * и принимать на склад пока нечего.
     */
    @Query("""
            select count(batch.id)
            from CellReceivingBatch batch
            where batch.confirmedAt is not null
              and batch.acceptedAt is null
              and exists (
                  select 1
                  from InputControlCard card
                  where card.batch.id = batch.id
                    and card.status =
                        ru.company.production.entity.InputControlStatus.COMPLETED
              )
              and not exists (
                  select 1
                  from InputControlCard card
                  where card.batch.id = batch.id
                    and card.status =
                        ru.company.production.entity.InputControlStatus.IN_WORK
              )
            """)
    long countAwaitingAcceptance();

    /**
     * Последний зарезервированный номер области нумерации.
     *
     * Ряд партии продолжается от того же предела, что и реестр: если
     * предыдущий приход ещё не подтверждён сканированием, его диапазон
     * в product_serials не виден, и без этого запроса следующая партия
     * получила бы уже занятые номера.
     */
    @Query("""
            select max(batch.lastSerial)
            from CellReceivingBatch batch
            where batch.cellClassifier.id = :classifierId
            """)
    Integer findMaxReservedSerial(
            @Param("classifierId") Long classifierId
    );

    /**
     * Сводка приходов по написанию поставщика.
     *
     * Группировка по нижнему регистру и без обрамляющих пробелов:
     * поставщик в партии — свободная строка, и «Компонент» с пробелом
     * на конце — тот же поставщик, что «Компонент».
     */
    @Query("""
            select
                lower(trim(batch.supplierName)) as supplierKey,
                min(batch.supplierName)         as displayName,
                count(batch.id)                 as batchCount,
                sum(batch.quantity)             as cellCount,
                sum(
                    case
                        when batch.confirmedAt is null then 0
                        else batch.quantity
                    end
                )                               as receivedCells,
                sum(
                    case
                        when batch.confirmedAt is null then 1
                        else 0
                    end
                )                               as pendingBatches,
                max(batch.createdAt)            as lastReceivedAt
            from CellReceivingBatch batch
            group by lower(trim(batch.supplierName))
            """)
    List<SupplierStatProjection> findSupplierStats();
}
