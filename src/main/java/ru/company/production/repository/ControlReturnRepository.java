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
     * Строки, по которым возврат заводился когда-либо.
     *
     * Открытых возвратов для этого мало: переделку закрывают повторным
     * проведением, и строка становится закрытой ровно в тот момент,
     * когда позиция снова попадает на контроль. Различить первую
     * переделку и повторную можно только по факту возврата, оставшемуся
     * в истории — строки возвратов не удаляются.
     */
    @Query("""
            select distinct ret.scheduleRow.id
            from ControlReturn ret
            where ret.scheduleRow.id in :rowIds
            """)
    List<Long> findRowIdsWithReturnHistory(
            @Param("rowIds") Collection<Long> rowIds
    );

    /**
     * Строки, чей экземпляр awaits решения цеха — открытый сигнал БТК
     * есть хотя бы по одной его строке.
     *
     * Нужен блокировке проведения: БТК возвращает изделие целиком, а не
     * одну операцию, поэтому сигнал, заведённый на строку монтажа,
     * касается и строки контроля того же изделия. Проверять каждую
     * строку отдельным запросом значило бы десятки обращений к базе на
     * один экран списка работ.
     *
     * Условие переписывает принадлежность возвратом экземпляру: строка
     * возврата принадлежит бюро, а не цеху, поэтому блокируются ровно
     * строки того же изделия, а не того же подразделения.
     */
    @Query("""
            select row.id
            from OrderScheduleRow row
            where row.id in :rowIds
              and (
                    exists (
                        select ret.id
                        from ControlReturn ret
                        join ret.scheduleRow other
                        where ret.resolvedAt is null
                          and row.customerOrder.id is not null
                          and other.customerOrder.id = row.customerOrder.id
                          and other.itemPosition = row.itemPosition
                          and other.instanceIndex = row.instanceIndex
                    )
                 or exists (
                        select ret.id
                        from ControlReturn ret
                        join ret.scheduleRow other
                        where ret.resolvedAt is null
                          and row.productionOrder.id is not null
                          and other.productionOrder.id = row.productionOrder.id
                          and other.itemPosition = row.itemPosition
                          and other.instanceIndex = row.instanceIndex
                    )
              )
            """)
    List<Long> findRowIdsOfSignaledInstances(
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
     * Сколько изделий ждёт решения цеха — счётчик пункта меню.
     *
     * Нужен счётчику в боковом меню: он считается на каждом экране
     * модуля, и строить ради цифры полные карточки было бы потерей.
     *
     * Условие переписывает принадлежность экземпляра подразделению:
     * строка возврата принадлежит бюро, а не цеху, поэтому возврат
     * попадает в счётчик ровно тогда, когда у того же экземпляра есть
     * строка плана этого цеха.
     *
     * Считаются изделия, а не строки возвратов: БТК заводит возврат на
     * каждую проведённую строку экземпляра, и по строкам возвратов
     * счётчик показал бы три изделия как девять (по три операции на
     * каждое). Раз JPQL не считает distinct по составному ключу
     * экземпляра, считается по одному представителю изделия — строке
     * цеха с наименьшим id среди его строк: таких строк на изделие
     * ровно одна, и она попадает в условие тогда же, когда и всё
     * изделие.
     */
    @Query("""
            select count(row.id)
            from OrderScheduleRow row
            where row.organizationUnit.id in :unitIds
              and (
                    exists (
                        select ret.id
                        from ControlReturn ret
                        join ret.scheduleRow other
                        where ret.resolvedAt is null
                          and row.customerOrder.id is not null
                          and other.customerOrder.id = row.customerOrder.id
                          and other.itemPosition = row.itemPosition
                          and other.instanceIndex = row.instanceIndex
                    )
                 or exists (
                        select ret.id
                        from ControlReturn ret
                        join ret.scheduleRow other
                        where ret.resolvedAt is null
                          and row.productionOrder.id is not null
                          and other.productionOrder.id = row.productionOrder.id
                          and other.itemPosition = row.itemPosition
                          and other.instanceIndex = row.instanceIndex
                    )
              )
              and not exists (
                    select earlier.id
                    from OrderScheduleRow earlier
                    where earlier.id < row.id
                      and earlier.organizationUnit.id in :unitIds
                      and (
                            (
                                row.customerOrder.id is not null
                                and earlier.customerOrder.id = row.customerOrder.id
                                and earlier.itemPosition = row.itemPosition
                                and earlier.instanceIndex = row.instanceIndex
                            )
                         or (
                                row.productionOrder.id is not null
                                and earlier.productionOrder.id = row.productionOrder.id
                                and earlier.itemPosition = row.itemPosition
                                and earlier.instanceIndex = row.instanceIndex
                            )
                      )
              )
            """)
    long countOpenForUnits(@Param("unitIds") java.util.Collection<Long> unitIds);
}
