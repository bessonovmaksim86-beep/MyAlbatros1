package ru.company.production.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.company.production.entity.InputControlDefectGroup;

import java.util.List;

@Repository
public interface InputControlDefectGroupRepository
        extends JpaRepository<InputControlDefectGroup, Long> {

    /**
     * Группы брака карточки по порядку.
     *
     * Порядок задаёт контролёр: дефекты перечисляют сверху вниз, и в
     * закрытой карточке перечень обязан читаться в том же виде.
     */
    @Query("""
            select grp
            from InputControlDefectGroup grp
            where grp.card.id = :cardId
            order by grp.sortOrder asc, grp.id asc
            """)
    List<InputControlDefectGroup> findByCardIdOrdered(
            @Param("cardId") Long cardId
    );

    /** Пакетная очистка групп перед перезаписью карточки. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            delete from InputControlDefectGroup grp
            where grp.card.id = :cardId
            """)
    int deleteByCardId(@Param("cardId") Long cardId);

    /**
     * Группы брака сразу многих карточек.
     *
     * Нужен складу: чтобы понять, какие ячейки партии на склад не
     * вернутся, состав читают и по отдельным номерам, и по отметке
     * «вся партия» — без неё группу восстановили бы только по одной
     * карточке.
     */
    @Query("""
            select grp
            from InputControlDefectGroup grp
            where grp.card.id in :cardIds
            order by grp.card.id asc, grp.sortOrder asc, grp.id asc
            """)
    List<InputControlDefectGroup> findByCardIds(
            @Param("cardIds") java.util.Collection<Long> cardIds
    );

    /**
     * Группы брака, закрытые возвратом поставщику.
     *
     * Реестр возвратов собирают по группам, а не по карточкам: партию
     * бракуют частями, и возвратом уходит только та часть, по которой
     * принято решение {@code RETURN_TO_SUPPLIER}. Карточка, партия и
     * контролёр подтягиваются {@code fetch} — {@code open-in-view}
     * выключен, а реестру нужны реквизиты рекламации (поставщик,
     * ордер, диапазон) и подпись контролёра. Берут только закрытые
     * карточки: открытая проверка ещё не вынесла решения.
     */
    @Query("""
            select grp
            from InputControlDefectGroup grp
            join fetch grp.card card
            join fetch card.batch batch
            left join fetch batch.cellClassifier classifier
            left join fetch classifier.sensorCatalog
            left join fetch card.checkedBy checker
            where grp.decision =
                  ru.company.production.entity.InputControlDecision.RETURN_TO_SUPPLIER
              and card.status =
                  ru.company.production.entity.InputControlStatus.COMPLETED
            order by card.completedAt desc, card.id desc,
                     grp.sortOrder asc, grp.id asc
            """)
    List<InputControlDefectGroup> findReturnGroupsDetailed();

    /**
     * Число партий, отправленных на возврат: счётчик в боковом меню.
     *
     * Считают карточки, а не группы: на партию возвратных групп может
     * быть несколько, а в меню счётчик отвечает на вопрос «сколько
     * партий ждать к возврату».
     */
    @Query("""
            select count(distinct grp.card.id)
            from InputControlDefectGroup grp
            where grp.decision =
                  ru.company.production.entity.InputControlDecision.RETURN_TO_SUPPLIER
              and grp.card.status =
                  ru.company.production.entity.InputControlStatus.COMPLETED
            """)
    long countReturnCards();
}
