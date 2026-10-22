package ru.company.production.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.company.production.entity.InputControlCardCell;
import ru.company.production.entity.InputControlCellType;

import java.util.List;

@Repository
public interface InputControlCardCellRepository
        extends JpaRepository<InputControlCardCell, Long> {

    /**
     * Номера карточки по виду списка.
     *
     * Список нужен и для показа, и для проверки: при сохранении
     * старые строки удаляются и пишутся внесённые заново — так
     * перечни остаются ровно теми, что оператор видит на экране, без
     * сверки «что изменилось».
     */
    @Query("""
            select cell
            from InputControlCardCell cell
            where cell.card.id = :cardId
              and cell.cellType = :cellType
            order by cell.id asc
            """)
    List<InputControlCardCell> findByCardIdAndType(
            @Param("cardId") Long cardId,
            @Param("cellType") InputControlCellType cellType
    );

    /** Пакетная очистка списка перед перезаписью. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            delete from InputControlCardCell cell
            where cell.card.id = :cardId
              and cell.cellType = :cellType
            """)
    int deleteByCardIdAndType(
            @Param("cardId") Long cardId,
            @Param("cellType") InputControlCellType cellType
    );

    /**
     * Забракованные номера вместе с их группами, в порядке групп.
     *
     * Группа подтягивается {@code fetch}: карточка разкладывает номера
     * по группам, а {@code open-in-view} выключен — ленивая ссылка
     * прервала бы рендеринг середины перечня.
     */
    @Query("""
            select cell
            from InputControlCardCell cell
            join fetch cell.group grp
            where cell.card.id = :cardId
              and cell.cellType = ru.company.production.entity.InputControlCellType.DEFECT
            order by grp.sortOrder asc, grp.id asc, cell.id asc
            """)
    List<InputControlCardCell> findDefectsWithGroupByCardId(
            @Param("cardId") Long cardId
    );

    /**
     * Забракованные номера сразу многих групп.
     *
     * Реестр возвратов собирают пакетом: один запрос на весь список
     * дешевле, чем запрос на каждую возвратную группу, иначе
     * отрисовка реестра зависела бы от числа партий. Группа
     * подтягивается {@code fetch} — по её id строки раскладывают по
     * группам, а {@code open-in-view} выключен.
     */
    @Query("""
            select cell
            from InputControlCardCell cell
            join fetch cell.group grp
            where grp.id in :groupIds
              and cell.cellType =
                  ru.company.production.entity.InputControlCellType.DEFECT
            order by grp.id asc, cell.id asc
            """)
    List<InputControlCardCell> findDefectsWithGroupByIds(
            @Param("groupIds") java.util.Collection<Long> groupIds
    );

    /**
     * Забракованные номера сразу многих карточек.
     *
     * Экран приёмки на склад строит перечень исключений по всему списку
     * партий: запрос на каждую партию превратил бы список в N обращений
     * к базе. Группа здесь не нужна — складу важно только то, какие
     * номера на склад не возвращают.
     */
    @Query("""
            select cell
            from InputControlCardCell cell
            join fetch cell.card card
            where card.id in :cardIds
              and cell.cellType =
                  ru.company.production.entity.InputControlCellType.DEFECT
            order by cell.card.id asc, cell.id asc
            """)
    List<InputControlCardCell> findDefectsByCardIds(
            @Param("cardIds") java.util.Collection<Long> cardIds
    );

    /**
     * Удаляет перечень ячеек сразу всех карточек партии.
     *
     * Нужен разделу «Очистка данных»: ключ card_id ссылается на
     * карточку с RESTRICT (V29), поэтому строки обязаны уйти раньше
     * самих карточек — каскада в схеме нет.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            delete from InputControlCardCell cell
            where cell.card.id in :cardIds
            """)
    int deleteAllByCardIds(
            @Param("cardIds") java.util.Collection<Long> cardIds
    );
}
