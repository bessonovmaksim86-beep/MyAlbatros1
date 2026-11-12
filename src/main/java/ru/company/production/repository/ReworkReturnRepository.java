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
     * Строки указанного списка, чей экземпляр уже принят цехом на
     * переделку — открытый возврат есть хотя бы по одной его строке.
     *
     * Нужен блокировке проведения: цех снимает проведение только с
     * отмеченных строк, а изделие переделывается целиком, и прочие его
     * работы обязаны ждать, пока переделка не закрыта. Отвечает
     * «Мои операции» и серверная проверка проведения.
     *
     * Условие читает принадлежность экземпляру, а не подразделению:
     * строка возврата принадлежит цеху, а блокировать надо весь маршрут
     * изделия, включая чужие подразделения того же маршрута.
     */
    @Query("""
            select row.id
            from OrderScheduleRow row
            where row.id in :rowIds
              and (
                    exists (
                        select ret.id
                        from ReworkReturn ret
                        join ret.scheduleRow other
                        where ret.resolvedAt is null
                          and row.customerOrder.id is not null
                          and other.customerOrder.id = row.customerOrder.id
                          and other.itemPosition = row.itemPosition
                          and other.instanceIndex = row.instanceIndex
                    )
                 or exists (
                        select ret.id
                        from ReworkReturn ret
                        join ret.scheduleRow other
                        where ret.resolvedAt is null
                          and row.productionOrder.id is not null
                          and other.productionOrder.id = row.productionOrder.id
                          and other.itemPosition = row.itemPosition
                          and other.instanceIndex = row.instanceIndex
                    )
              )
            """)
    List<Long> findRowIdsOfReworkInstances(
            @Param("rowIds") java.util.Collection<Long> rowIds
    );

    /**
     * Строки указанного списка, по которым сам цех завёл открытый
     * возврат — то есть ровно те операции, которые решено переделывать.
     *
     * Они составляют исключение из блокировки: повторное проведение
     * такой строки и есть закрытие переделки, и заблокировать её
     * означало бы запереть цикл намертво.
     */
    @Query("""
            select distinct ret.scheduleRow.id
            from ReworkReturn ret
            where ret.scheduleRow.id in :rowIds
              and ret.resolvedAt is null
            """)
    List<Long> findRowIdsWithOpenReturn(
            @Param("rowIds") java.util.Collection<Long> rowIds
    );

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
