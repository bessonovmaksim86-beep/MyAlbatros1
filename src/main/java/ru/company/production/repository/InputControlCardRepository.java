package ru.company.production.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.company.production.entity.InputControlCard;
import ru.company.production.entity.InputControlStatus;

import java.util.List;
import java.util.Optional;

@Repository
public interface InputControlCardRepository
        extends JpaRepository<InputControlCard, Long> {

    /**
     * Карточки вместе с партией, её классификатором и контролёром.
     *
     * open-in-view выключен, а журнал и список партий выводят
     * наименования ячейки и ФИО, поэтому связи читаются тем же
     * запросом. Порядок — новые сверху: контролёр работает с тем, что
     * пришло последним.
     */
    @Query("""
            select card
            from InputControlCard card
            left join fetch card.batch batch
            left join fetch batch.cellClassifier classifier
            left join fetch classifier.sensorCatalog
            left join fetch batch.createdBy author
            left join fetch card.checkedBy checker
            order by card.startedAt desc, card.id desc
            """)
    List<InputControlCard> findAllDetailed();

    @Query("""
            select card
            from InputControlCard card
            left join fetch card.batch batch
            left join fetch batch.cellClassifier classifier
            left join fetch classifier.sensorCatalog
            left join fetch batch.createdBy author
            left join fetch card.checkedBy checker
            where card.id = :id
            """)
    Optional<InputControlCard> findDetailedById(@Param("id") Long id);

    /**
     * Открытая карточка партии — та, что «взята в работу».
     *
     * На партию может быть несколько карточек (проверку закрыли,
     * позже открыли новую), но незакрытая возможна только одна.
     */
    @Query("""
            select card
            from InputControlCard card
            left join fetch card.batch batch
            left join fetch batch.cellClassifier classifier
            left join fetch classifier.sensorCatalog
            left join fetch card.checkedBy checker
            where card.batch.id = :batchId
              and card.status = :status
            order by card.startedAt desc, card.id desc
            """)
    List<InputControlCard> findDetailedByBatchIdAndStatus(
            @Param("batchId") Long batchId,
            @Param("status") InputControlStatus status
    );

    /** Открытая карточка партии либо {@code null}, если партию не брали. */
    default Optional<InputControlCard> findOpenByBatchId(Long batchId) {
        return findDetailedByBatchIdAndStatus(
                batchId,
                InputControlStatus.IN_WORK
        ).stream()
                .findFirst();
    }

    /**
     * Закрытые карточки сразу многих партий.
     *
     * Склад собирает список партий, вернувшихся от БВК, разом: запрос
     * на каждую партию означал бы столько же обращений к базе, сколько
     * партий в журнале. Партия, классификатор и контролёр подтягиваются
     * {@code fetch} — {@code open-in-view} выключен, а экрану приёмки
     * нужны реквизиты проверки прямо в строке. Открытую карточку отдают
     * отдельным запросом: партия на проверке на склад не возвращается.
     */
    @Query("""
            select card
            from InputControlCard card
            left join fetch card.batch batch
            left join fetch batch.cellClassifier classifier
            left join fetch classifier.sensorCatalog
            left join fetch card.checkedBy checker
            where card.batch.id in :batchIds
              and card.status =
                  ru.company.production.entity.InputControlStatus.COMPLETED
            order by card.batch.id asc, card.completedAt desc, card.id desc
            """)
    List<InputControlCard> findCompletedByBatchIds(
            @Param("batchIds") java.util.Collection<Long> batchIds
    );

    /**
     * Открытые карточки сразу многих партий — те же соображения, что у
     * {@link #findCompletedByBatchIds}.
     */
    @Query("""
            select card
            from InputControlCard card
            where card.batch.id in :batchIds
              and card.status =
                  ru.company.production.entity.InputControlStatus.IN_WORK
            order by card.batch.id asc, card.startedAt desc, card.id desc
            """)
    List<InputControlCard> findOpenByBatchIds(
            @Param("batchIds") java.util.Collection<Long> batchIds
    );

    /** История проверок партии: закрытые карточки, новые сверху. */
    @Query("""
            select card
            from InputControlCard card
            left join fetch card.checkedBy checker
            where card.batch.id = :batchId
              and card.status = :status
            order by card.completedAt desc, card.id desc
            """)
    List<InputControlCard> findDetailedHistoryByBatchId(
            @Param("batchId") Long batchId,
            @Param("status") InputControlStatus status
    );

    /** Партий, взятых в работу: счётчик в боковом меню. */
    long countByStatus(InputControlStatus status);
}
