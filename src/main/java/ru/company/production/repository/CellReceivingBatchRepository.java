package ru.company.production.repository;

import org.springframework.data.domain.Pageable;
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

    /*
     * Новых заявок на входной контроль — счётчик пункта меню.
     *
     * Заявкой считают партию, по которой карточку не заводили ни разу:
     * список раздела печатает такой партии «Ожидает проверки» и кнопку
     * «Взять в работу», и цифра меню обязана равняться числу этих
     * кнопок. Карточек в работе вместо этого недостаточно: своей
     * незавершённой проверкой контролёр затёр бы чужую новую заявку.
     *
     * Условие confirmedAt is not null — та же граница, что у
     * {@code InputControlService#findReceivedBatches()}: партия ждёт
     * подтверждения номеров сканированием, и до него проверять нечего.
     */
    @Query("""
            select count(batch)
            from CellReceivingBatch batch
            where batch.confirmedAt is not null
              and not exists (
                    select 1
                    from InputControlCard card
                    where card.batch.id = batch.id
              )
            """)
    long countAwaitingControl();

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

    /*
     * Отдельного count-запроса для счётчика «Приёмка с БВК» здесь нет:
     * счётчик не считает партии, которые БВК отбраковал целиком, а
     * сколько ячеек партии отсеяно на брак — зависит от карточки
     * контроля, то есть не выражается полями самой партии. Правило
     * живёт в BvkAcceptanceService.AcceptanceBatchView#fullyRejected(),
     * и счётчик считает тот же список, что печатает экран, иначе цифра
     * меню и число кнопок «Принять на склад» разошлись бы.
     */

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

    /**
     * Номер изменения последней партии данного классификатора.
     *
     * Нужен карточке комплектования: изделие заказа печатают как
     * «5006 — ПВВЗ изм.3», чтобы кладовщик не перепутал изменения
     * одного изделия. Партия берётся самая свежая — именно её значение
     * склад вводил при последнем оприходовании.
     */
    @Query("""
            select batch.changeNumber
            from CellReceivingBatch batch
            where batch.cellClassifier.id = :classifierId
            order by batch.createdAt desc, batch.id desc
            """)
    List<String> findChangeNumbersOfClassifier(
            @Param("classifierId") Long classifierId
    );

    /**
     * Номера изменений сразу по перечню классификаторов — одним запросом.
     *
     * Список комплектования печатает изделие у каждой строки, и запрос
     * на заказ дал бы N+1 на экране с десятками заказов. Строки идут от
     * свежих к старым, поэтому вызывающий оставляет себе первую
     * встреченную по классификатору.
     */
    @Query("""
            select batch.cellClassifier.id as classifierId,
                   batch.changeNumber      as changeNumber
            from CellReceivingBatch batch
            where batch.cellClassifier.id in :classifierIds
            order by batch.createdAt desc, batch.id desc
            """)
    List<ChangeNumberView> findChangeNumbersByClassifiers(
            @Param("classifierIds") java.util.Collection<Long> classifierIds
    );

    /** Пара «классификатор → номер изменения его последней партии». */
    interface ChangeNumberView {

        Long getClassifierId();

        String getChangeNumber();
    }

    /**
     * Строка глобального поиска среди приходов склада.
     *
     * Ищут по номеру приходного ордера, а показать нужно и поставщика,
     * и ячейку, и диапазон зарезервированных номеров: по одному ордеру
     * партия опознаётся не всегда.
     */
    interface ReceivingSearchView {

        Long getId();

        String getIncomingOrderNumber();

        String getSupplierName();

        Integer getClassifierCode();

        String getClassifierName();

        String getSensorClassName();

        Integer getQuantity();

        Integer getFirstSerial();

        Integer getLastSerial();
    }

    /**
     * Приходы по номеру приходного ордера или поставщику.
     *
     * Отдельно от {@code ProductSerialRepository#search}: партия живёт
     * до подтверждения сканированием, а её номера в реестр попадают
     * только после него. Искать только по реестру значило бы не найти
     * приход, который оператор оформил и ещё не закрыл, — то есть ровно
     * тот, который ищут чаще всего.
     */
    @Query("""
            select
                batch.id              as id,
                batch.incomingOrderNumber as incomingOrderNumber,
                batch.supplierName    as supplierName,
                classifier.code       as classifierCode,
                classifier.name       as classifierName,
                sensor.name           as sensorClassName,
                batch.quantity        as quantity,
                batch.firstSerial     as firstSerial,
                batch.lastSerial      as lastSerial
            from CellReceivingBatch batch
            join batch.cellClassifier classifier
            left join classifier.sensorCatalog sensor
            where lower(batch.incomingOrderNumber)
                      like lower(concat('%', :term, '%'))
               or lower(batch.supplierName)
                      like lower(concat('%', :term, '%'))
            order by batch.createdAt desc, batch.id desc
            """)
    List<ReceivingSearchView> search(
            @Param("term") String term,
            Pageable pageable
    );
}
