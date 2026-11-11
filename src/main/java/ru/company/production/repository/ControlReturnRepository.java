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

    /**
     * Все открытые возвраты с контроля.
     *
     * Нужен разделу «Возврат на доработку»: строка возврата принадлежит
     * бюро, а не цеху, поэтому цех выбирает изделия не по подразделению
     * возврата, а по принадлежности экземпляра — какие строки этого
     * изделия выполняет его цех.
     *
     * Строка и её основание подхватываются сразу: open-in-view отключён,
     * и ключ экземпляра карточка читает уже вне транзакции запроса.
     */
    @Query("""
            select ret
            from ControlReturn ret
            left join fetch ret.scheduleRow row
            left join fetch row.customerOrder
            left join fetch row.productionOrder
            left join fetch ret.returnedBy returnedBy
            where ret.resolvedAt is null
            order by ret.returnedAt desc, ret.id desc
            """)
    List<ControlReturn> findAllOpen();

    /**
     * Сколько открытых возвратов относится к изделиям, которые его
     * цех вообще выполняет.
     *
     * Нужен счётчику в боковом меню: он считается на каждом экране
     * модуля, и строить ради цифры полные карточки было бы потерей.
     *
     * Условие переписывает принадлежность экземпляра подразделению:
     * строка возврата принадлежит бюро, а не цеху, поэтому в счётчик
     * возврат попадает ровно тогда, когда у того же экземпляра есть
     * строка плана этого цеха.
     */
    @Query("""
            select count(ret)
            from ControlReturn ret
            where ret.resolvedAt is null
              and (
                    exists (
                        select other
                        from OrderScheduleRow other
                        where ret.scheduleRow.customerOrder.id is not null
                          and other.customerOrder.id = ret.scheduleRow.customerOrder.id
                          and other.itemPosition = ret.scheduleRow.itemPosition
                          and other.instanceIndex = ret.scheduleRow.instanceIndex
                          and other.organizationUnit.id in :unitIds
                    )
                 or exists (
                        select other
                        from OrderScheduleRow other
                        where ret.scheduleRow.productionOrder.id is not null
                          and other.productionOrder.id = ret.scheduleRow.productionOrder.id
                          and other.itemPosition = ret.scheduleRow.itemPosition
                          and other.instanceIndex = ret.scheduleRow.instanceIndex
                          and other.organizationUnit.id in :unitIds
                    )
              )
            """)
    long countOpenForUnits(@Param("unitIds") java.util.Collection<Long> unitIds);
}
