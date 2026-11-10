package ru.company.production.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.company.production.entity.ControlReturn;

import java.util.Collection;
import java.util.List;

/**
 * Репозиторий возвратов с контроля БТК.
 *
 * Главное чтение — открытые возвраты по строкам графика: по ним
 * «Мои операции» подсвечивают переделку и показывают причину прямо в
 * панели экземпляра. Список читается пакетом на всю страницу списка,
 * иначе каждая строка таблицы вытягивала бы свой запрос.
 */
@Repository
public interface ControlReturnRepository
        extends JpaRepository<ControlReturn, Long> {

    /**
     * Открытые возвраты указанных строк графика.
     *
     * Возврат закрыт, когда {@code resolved_at} заполнен проведением
     * переделки; незакрытая строка обязана подсвечиваться, пока её не
     * провели повторно.
     */
    @Query("""
            select ret
            from ControlReturn ret
            left join fetch ret.returnedBy returnedBy
            where ret.scheduleRow.id in :rowIds
              and ret.resolvedAt is null
            order by ret.returnedAt asc, ret.id asc
            """)
    List<ControlReturn> findOpenByRowIds(
            @Param("rowIds") Collection<Long> rowIds
    );

    /**
     * История возвратов одной строки графика — сколько раз работу
     * возвращали и как закрывали.
     */
    @Query("""
            select ret
            from ControlReturn ret
            left join fetch ret.returnedBy returnedBy
            left join fetch ret.resolvedBy resolvedBy
            where ret.scheduleRow.id = :rowId
            order by ret.returnedAt desc, ret.id desc
            """)
    List<ControlReturn> findAllByRowId(@Param("rowId") Long rowId);
}
