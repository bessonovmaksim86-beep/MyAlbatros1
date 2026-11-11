package ru.company.production.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.company.production.entity.ReworkReturn;

import java.util.Collection;
import java.util.List;

/**
 * Репозиторий возвратов на доработку.
 *
 * Главное чтение — открытые возвраты по строкам графика: по ним раздел
 * «Возврат на доработку» подсвечивает уже снятые для переделки работы.
 * Список читается пакетом на всю страницу, иначе каждая строка таблицы
 * вытягивала бы свой запрос.
 */
@Repository
public interface ReworkReturnRepository extends JpaRepository<ReworkReturn, Long> {

    /**
     * Открытые возвраты указанных строк графика.
     *
     * Возврат закрыт, когда {@code resolved_at} заполнен повторным
     * проведением; незакрытая строка обязана подсвечиваться, пока её
     * не провели снова.
     */
    @Query("""
            select ret
            from ReworkReturn ret
            left join fetch ret.returnedBy returnedBy
            where ret.scheduleRow.id in :rowIds
              and ret.resolvedAt is null
            order by ret.returnedAt asc, ret.id asc
            """)
    List<ReworkReturn> findOpenByRowIds(@Param("rowIds") Collection<Long> rowIds);

    /**
     * История возвратов одной строки — сколько раз работу возвращали
     * на доработку и как закрывали. Нужна аналитике брака.
     */
    @Query("""
            select ret
            from ReworkReturn ret
            left join fetch ret.returnedBy returnedBy
            left join fetch ret.resolvedBy resolvedBy
            where ret.scheduleRow.id = :rowId
            order by ret.returnedAt desc, ret.id desc
            """)
    List<ReworkReturn> findAllByRowId(@Param("rowId") Long rowId);
}
