package ru.company.production.repository;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.company.production.entity.OrderScheduleRow;
import ru.company.production.entity.ProductionOrder;
import ru.company.production.entity.ScheduleRowStatus;
import ru.company.production.entity.SerialProductionOrder;
import ru.company.production.entity.ZnpKind;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

/**
 * Данные раздела «Распределение работ по групповым ЗНП».
 *
 * Раздел строит разрез «заказ → ячейки → их операции», и все три
 * уровня читаются пакетами: страница перечисляет десятки заказов, а
 * запрос на каждую строку дал бы N+1 на экране, который руководитель
 * открывает несколько раз за смену.
 *
 * Граница раздела — подразделение руководителя, и проходит она через
 * строки плана, а не через сам заказ: у группового ЗНП заказчика нет,
 * поэтому единственный признак «эта работа наша» — строка
 * {@code order_schedule_rows} с нужным {@code organization_unit_id}.
 * Заказ без таких строк в раздел не попадает: распределять в нём нечего.
 *
 * Основание строки активно — то же правило, что у всех запросов
 * графика: заказ снимается с работы без удаления строки (V47), и снятый
 * заказ обязан забрать свой план с собой.
 */
@Repository
public interface GroupZnpDistributionRepository
        extends JpaRepository<ProductionOrder, Long> {

    /**
     * Групповые ЗНП, чей план принадлежит подразделениям руководителя.
     *
     * EXISTS по строкам плана, а не join: заказ обязан попасть в
     * выдачу один раз, даже если его маршрут состоит из десяти операций
     * этого цеха. Вид заказа берётся списком из {@link ZnpKind}, а не
     * переписывается перечнем констант, — новый групповой вид попадёт в
     * раздел сам.
     *
     * Дата плана ограничена горизонтом: заказ, все работы которого
     * ушли за срок, мастеру не нужен, а без фильтра список рос бы
     * вместе с историей реестра.
     *
     * Заказы без внесённых ячеек НЕ отбрасываются. Маршрут группового
     * заказа планировщик заводит по заказанному количеству сразу при
     * взятии заказа в работу, поэтому работы у заказа есть до того,
     * как кладовщик внесёт в него хоть одну ячейку. Скрыть такой заказ
     * значило бы оставить цех без возможности распределить первую же
     * операцию («Комплектование подсборок»), без которой ячейки в заказ
     * и не попадут.
     */
    @EntityGraph(attributePaths = {
            "productClassifier",
            "techProcess",
            "priority"
    })
    @Query("""
            select distinct ord
            from ProductionOrder ord
            where ord.active = true
              and ord.znpKind in :groupKinds
              and exists (
                    select 1
                    from OrderScheduleRow row
                    join row.productionOrder base
                    where base.id = ord.id
                      and base.active = true
                      and row.status = :status
                      and row.organizationUnit.id in :unitIds
                      and row.operation.operationType.code
                              not in :excludedTypeCodes
                      and row.operation.executorRole.code
                              not in :supervisorRoleCodes
                      and row.plannedDate between :from and :to
              )
            order by ord.orderDate asc, ord.number asc
            """)
    List<ProductionOrder> findGroupZnpOfUnits(
            @Param("groupKinds") Collection<ZnpKind> groupKinds,
            @Param("status") ScheduleRowStatus status,
            @Param("unitIds") Collection<Long> unitIds,
            @Param("excludedTypeCodes") Collection<String> excludedTypeCodes,
            @Param("supervisorRoleCodes") Collection<String> supervisorRoleCodes,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to
    );

    /** Тот же отбор количеством — счётчик пункта меню. */
    @Query("""
            select count(distinct ord.id)
            from ProductionOrder ord
            where ord.active = true
              and ord.znpKind in :groupKinds
              and exists (
                    select 1
                    from OrderScheduleRow row
                    join row.productionOrder base
                    where base.id = ord.id
                      and base.active = true
                      and row.status = :status
                      and row.organizationUnit.id in :unitIds
                      and row.operation.operationType.code
                              not in :excludedTypeCodes
                      and row.operation.executorRole.code
                              not in :supervisorRoleCodes
                      and row.plannedDate between :from and :to
              )
            """)
    long countGroupZnpOfUnits(
            @Param("groupKinds") Collection<ZnpKind> groupKinds,
            @Param("status") ScheduleRowStatus status,
            @Param("unitIds") Collection<Long> unitIds,
            @Param("excludedTypeCodes") Collection<String> excludedTypeCodes,
            @Param("supervisorRoleCodes") Collection<String> supervisorRoleCodes,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to
    );

    /**
     * Строки плана групповых заказов подразделения — уровень операций.
     *
     * Забираются строки ВСЕХ заказов страницы одним запросом. Дата
     * плана здесь не ограничивается: строки нужны целиком, иначе
     * раскрытая ячейка показала бы половину маршрута и мастер не
     * понял бы, какая работа следующая.
     *
     * Подразделение фильтруется: мастер распределяет свою работу, а не
     * весь маршрут заказа. Чужие операции в раскрытии были бы
     * приглашением назначить то, до чего у него нет права.
     *
     * Руководящие работы из раскрытия убраны тем же правилом, что и из
     * перечня заказов: получение комплектации и операции с руководящей
     * ролью исполнителя не перекладываются на людей, они живут в «Мои
     * операции». Оставить их здесь значило бы показать кнопку там, где
     * она заведомо откажет.
     *
     * Развязаны lazy-связи {@code join fetch}, а не {@code EntityGraph}:
     * доменный тип этого репозитория — заказ, а строку плана возвращает
     * именно этот метод, и JPA-граф строился бы от {@code ProductionOrder},
     * где атрибута {@code executor} нет (ошибка на старте запроса).
     * Все связи — many-to-one, поэтому fetch join не размножает строки
     * и {@code distinct} не нужен.
     */
    @Query("""
            select row
            from OrderScheduleRow row
            left join fetch row.operation operation
            left join fetch operation.operationName operationName
            left join fetch operation.operationType operationType
            left join fetch row.productionOrder productionOrder
            left join fetch row.techProcess techProcess
            left join fetch row.executor executor
            where row.productionOrder.id in :orderIds
              and row.status = :status
              and row.organizationUnit.id in :unitIds
              and productionOrder.active = true
              and operation.operationType.code
                      not in :excludedTypeCodes
              and operation.executorRole.code
                      not in :supervisorRoleCodes
            order by row.productionOrder.id asc,
                     row.instanceIndex asc,
                     row.seq asc,
                     row.plannedDate asc
            """)
    List<OrderScheduleRow> findPlanRowsOfGroupZnp(
            @Param("orderIds") Collection<Long> orderIds,
            @Param("status") ScheduleRowStatus status,
            @Param("unitIds") Collection<Long> unitIds,
            @Param("excludedTypeCodes") Collection<String> excludedTypeCodes,
            @Param("supervisorRoleCodes") Collection<String> supervisorRoleCodes
    );

    /**
     * Ячейки, внесённые в заказы, — уровень состава.
     *
     * Состав группового ЗНП задаёт матрица комплектования (V20): склад
     * вносит в заказ конкретные номера ячеек. Порядок — по моменту
     * внесения, затем по номеру: он же задаёт сопоставление с
     * экземплярами плана (см. {@code SubassemblyKittingService}).
     */
    @Query("""
            select link
            from SerialProductionOrder link
            join fetch link.serial serial
            left join fetch serial.productClassifier classifier
            where link.productionOrder.id in :orderIds
            order by link.productionOrder.id asc,
                     link.createdAt asc,
                     serial.serial asc
            """)
    List<SerialProductionOrder> findCellsOfGroupZnp(
            @Param("orderIds") Collection<Long> orderIds
    );
}