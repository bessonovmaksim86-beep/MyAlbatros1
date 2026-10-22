package ru.company.production.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.company.production.entity.InputControlCardReason;

import java.util.List;

@Repository
public interface InputControlCardReasonRepository
        extends JpaRepository<InputControlCardReason, Long> {

    /**
     * Цепочки причин всех групп, ступень за ступенью.
     *
     * Причина без группы не описывает ни одного исхода, поэтому группа
     * подтягивается сразу: закрытая карточка выводит каждую цепочку
     * под её решением.
     */
    @Query("""
            select chosen
            from InputControlCardReason chosen
            left join fetch chosen.reason reason
            left join fetch reason.parent parent
            join fetch chosen.group grp
            where chosen.card.id = :cardId
            order by grp.sortOrder asc, grp.id asc,
                     chosen.levelNumber asc, chosen.id asc
            """)
    List<InputControlCardReason> findDetailsByCardId(
            @Param("cardId") Long cardId
    );

    /** Перезапись цепочки при сохранении карточки. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            delete from InputControlCardReason
            where card.id = :cardId
            """)
    int deleteByCardId(@Param("cardId") Long cardId);

    /**
     * Цепочки причин сразу многих групп — пакетом для реестра.
     *
     * Реестр возвратов обязан показать основание рекламации по каждой
     * возвратной группе; собирать причины запросом на группу нельзя —
     * список партий превратился бы в десятки обращений к базе.
     */
    @Query("""
            select chosen
            from InputControlCardReason chosen
            left join fetch chosen.reason reason
            left join fetch reason.parent parent
            join fetch chosen.group grp
            where grp.id in :groupIds
            order by grp.id asc, chosen.levelNumber asc, chosen.id asc
            """)
    List<InputControlCardReason> findChainByGroupIds(
            @Param("groupIds") java.util.Collection<Long> groupIds
    );

    /** Снимает цепочки причин со всех карточек удаляемой партии. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            delete from InputControlCardReason chosen
            where chosen.card.id in :cardIds
            """)
    int deleteAllByCardIds(
            @Param("cardIds") java.util.Collection<Long> cardIds
    );
}
