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
     *
     * Сигналы барьерных подразделений (склады SGD/SCD и контроль SQ)
     * основанием не служат: переделку закрывает повторное проведение
     * работы цеха, а склад и контроль свою строку повторно не проводят
     * — склад свою работу уже закрыл, а сигнал контроля закроется ровно
     * тем проведением, которое он сейчас и удерживает. Учёт такого
     * сигнала запирал бы экземпляр намертво: строка контроля ждёт
     * закрытия собственного сигнала, а сигнал не закроется, пока строка
     * заблокирована. Барьерная строка источника определяется по
     * подразделению; строка без подразделения остаётся основанием —
     * её принадлежность барьеру неизвестна, и снимать блокировку
     * наугад нельзя.
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
                        left join other.organizationUnit otherUnit
                        left join otherUnit.service otherService
                        where ret.resolvedAt is null
                          and (otherService.code is null
                                 or otherService.code not in :barrierServices)
                          and row.customerOrder.id is not null
                          and other.customerOrder.id = row.customerOrder.id
                          and other.itemPosition = row.itemPosition
                          and other.instanceIndex = row.instanceIndex
                    )
                 or exists (
                        select ret.id
                        from ControlReturn ret
                        join ret.scheduleRow other
                        left join other.organizationUnit otherUnit
                        left join otherUnit.service otherService
                        where ret.resolvedAt is null
                          and (otherService.code is null
                                 or otherService.code not in :barrierServices)
                          and row.productionOrder.id is not null
                          and other.productionOrder.id = row.productionOrder.id
                          and other.itemPosition = row.itemPosition
                          and other.instanceIndex = row.instanceIndex
                    )
              )
            """)
    List<Long> findRowIdsOfSignaledInstances(
            @Param("rowIds") java.util.Collection<Long> rowIds,
            @Param("barrierServices") java.util.Collection<String> barrierServices
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
     * Открытые возвраты с контроля по подразделениям цеха.
     *
     * Нужен разделу «Возврат на доработку»: строка возврата принадлежит
     * тому подразделению, чью работу вернули, и цех обязан видеть ровно
     * свои сигналы. Сигналы соседнего цеха того же изделия держат его
     * собственную карточку — решение о переделке каждый цех выносит сам
     * и не вправе гасить чужое ожидание.
     *
     * Строка и её основание подхватываются сразу: open-in-view отключён,
     * и ключ экземпляра карточка читает уже вне транзакции запроса.
     */
    @Query("""
            select ret
            from ControlReturn ret
            join fetch ret.scheduleRow row
            left join fetch row.customerOrder
            left join fetch row.productionOrder
            left join fetch ret.returnedBy returnedBy
            where ret.resolvedAt is null
              and row.organizationUnit.id in :unitIds
            order by ret.returnedAt desc, ret.id desc
            """)
    List<ControlReturn> findOpenForUnits(
            @Param("unitIds") Collection<Long> unitIds
    );

    /**
     * Открытые сигналы БТК по изделиям указанных строк — все сигналы
     * каждого затронутого экземпляра в границах указанных подразделений.
     *
     * Нужен решению цеха: БТК заводит возврат на каждую проведённую
     * строку экземпляра, а начальник цеха отмечает переделку по своему
     * усмотрению. Закрывать сигналы только по снятым строкам означало
     * бы, что сигналы его же строк, оставшихся закрытыми, держали бы
     * карточку изделия в разделе — цех возвращал бы изделие самому
     * себе до бесконечности.
     *
     * Граница по подразделениям обязательна: над изделием работают
     * несколько цехов, и решение одного не гасит сигнал другого —
     * иначе цех, ещё не видевший возврата, потерял бы своё изделие.
     */
    @Query("""
            select ret
            from ControlReturn ret
            join fetch ret.scheduleRow row
            left join fetch row.customerOrder
            left join fetch row.productionOrder
            where ret.resolvedAt is null
              and row.organizationUnit.id in :unitIds
              and (
                    exists (
                        select 1
                        from OrderScheduleRow chosen
                        where chosen.id in :rowIds
                          and row.customerOrder.id is not null
                          and chosen.customerOrder.id = row.customerOrder.id
                          and chosen.itemPosition = row.itemPosition
                          and chosen.instanceIndex = row.instanceIndex
                    )
                 or exists (
                        select 1
                        from OrderScheduleRow chosen
                        where chosen.id in :rowIds
                          and row.productionOrder.id is not null
                          and chosen.productionOrder.id = row.productionOrder.id
                          and chosen.itemPosition = row.itemPosition
                          and chosen.instanceIndex = row.instanceIndex
                    )
              )
            """)
    List<ControlReturn> findOpenOfInstancesOfRows(
            @Param("rowIds") Collection<Long> rowIds,
            @Param("unitIds") Collection<Long> unitIds
    );

    /**
     * Сколько изделий ждёт решения этого цеха — счётчик пункта меню.
     *
     * Нужен счётчику в боковом меню: он считается на каждом экране
     * модуля, и строить ради цифры полные карточки было бы потерей.
     *
     * Основание то же, что у раздела: сигнал обязан принадлежать
     * строке цеха. Сигнал соседнего цеха того же изделия — карточка
     * соседнего цеха, и в счётчик он попадать не должен, иначе цифра
     * не сошлась бы со списком.
     *
     * Считаются изделия, а не строки возвратов: БТК заводит возврат на
     * каждую проведённую строку экземпляра, и по строкам возвратов
     * счётчик показал бы три изделия как девять (по три операции на
     * каждое). Раз JPQL не считает distinct по составному ключу
     * экземпляра, считается по одному представителю изделия — строке
     * цеха с наименьшим id среди его строк: таких строк на изделие
     * ровно одна.
     */
    @Query("""
            select count(row.id)
            from OrderScheduleRow row
            where row.organizationUnit.id in :unitIds
              and (
                    exists (
                        select ret.id
                        from ControlReturn ret
                        where ret.resolvedAt is null
                          and ret.scheduleRow.id = row.id
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
