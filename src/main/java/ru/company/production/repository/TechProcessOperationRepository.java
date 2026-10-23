package ru.company.production.repository;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.company.production.entity.TechProcessOperation;

import java.util.Collection;
import java.util.List;

/**
 * Узлы маршрутов техпроцессов — операции вместе с их зависимостями.
 *
 * Репозиторий нужен стороне исполнения: строка графика
 * (order_schedule_rows) хранит пару «техпроцесс + операция», но не
 * ссылку на узел маршрута, поэтому зависимости операции читаются
 * здесь — и сразу пачкой на весь список, а не по одной на строку.
 */
@Repository
public interface TechProcessOperationRepository
        extends JpaRepository<TechProcessOperation, Long> {

    /**
     * Узлы маршрутов с предшественниками и справочными операциями.
     *
     * Выборка по техпроцессам, а не по парам: предшественников нужно
     * сравнить со всеми строками того же заказа, и частичный набор узлов
     * дал бы ложную картину — операцию разблокировал бы узел, который
     * просто не попал в выборку.
     */
    @EntityGraph(attributePaths = {
            "predecessors",
            "predecessors.operation",
            "predecessors.operation.operationName",
            "operation",
            "operation.operationName"
    })
    @Query("""
            select node
            from TechProcessOperation node
            where node.techProcess.id in :processIds
            """)
    List<TechProcessOperation> findAllWithPredecessorsForProcesses(
            @Param("processIds") Collection<Long> processIds
    );

    /**
     * Комплектовочные операции базового техпроцесса, назначенного
     * классификатору серийника.
     *
     * Нужны при создании ЗНП для конкретного серийника: список операций
     * берётся из маршрута изделия-датчика, а не из выпуска. Учитывается
     * только базовый активный маршрут, только операции типа KITTING,
     * порядок — по позиции в маршруте.
     */
    @EntityGraph(attributePaths = {
            "operation",
            "operation.operationName",
            "operation.operationType"
    })
    @Query("""
            select node
            from TechProcessOperation node
            join node.techProcess techProcess
            join TechProcessClassifier link
                 on link.techProcess.id = techProcess.id
            join node.operation operation
            join operation.operationType operationType
            where link.classifier.id = :classifierId
              and link.basic = true
              and techProcess.active = true
              and operationType.code = :typeCode
            order by node.position asc
            """)
    List<TechProcessOperation> findBasicProcessKittingByClassifier(
            @Param("classifierId") Long classifierId,
            @Param("typeCode") String typeCode
    );

    /**
     * Комплектовочные операции указанного подразделения в перечне
     * маршрутов.
     *
     * Нужен комплектованию ремонтных ЗНП: заказ ведётся по своему
     * техпроцессу, и проводить ячейку надо по той операции этого
     * маршрута, которая принадлежит бюро входного контроля, — а не по
     * первой попавшейся комплектовочной операции склада.
     *
     * Выборка по перечню техпроцессов, а не по одному: меню «Моих
     * операций» рисует сразу несколько заказов, и запрос на каждый
     * заказ дал бы столько же обращений к базе, сколько заказов в
     * списке.
     *
     * Подразделение и тип задаются параметрами, а не вшиты в запрос:
     * коды справочников знает сервис (они заведены миграциями V15 и
     * V30), а репозиторий остаётся переиспользуемым для прочих
     * подразделений.
     */
    @EntityGraph(attributePaths = {
            "operation",
            "operation.operationName",
            "operation.operationType",
            "operation.organizationUnit"
    })
    @Query("""
            select node
            from TechProcessOperation node
            join node.techProcess techProcess
            join node.operation operation
            join operation.operationType operationType
            join operation.organizationUnit unit
            where techProcess.id in :processIds
              and operationType.code = :typeCode
              and unit.code = :unitCode
            order by techProcess.id asc, node.position asc
            """)
    List<TechProcessOperation> findUnitKittingNodesForProcesses(
            @Param("processIds") Collection<Long> processIds,
            @Param("typeCode") String typeCode,
            @Param("unitCode") String unitCode
    );

    /**
     * Узлы маршрутов указанного подразделения по перечню типов операций.
     *
     * Отличие от {@link #findUnitKittingNodesForProcesses}: тип один, а
     * не несколько. Нужен приёмке ячеек по заказу-комплектующему: склад
     * принимает подсборку и по «Приёмке на склад» (V54), и по
     * «Комплектованию подсборок» (V55), и отбор раздела обязан
     * совпадать с отбором операции — иначе сервис не нашёл бы операцию
     * у заказа, который список уже показал.
     *
     * Порядок — по маршруту, затем по позиции узла: приёмка берётся
     * первой по маршруту, и без сортировки выбор зависел бы от того,
     * в каком порядке строки вернула база.
     */
    @EntityGraph(attributePaths = {
            "operation",
            "operation.operationName",
            "operation.operationType",
            "operation.organizationUnit"
    })
    @Query("""
            select node
            from TechProcessOperation node
            join node.techProcess techProcess
            join node.operation operation
            join operation.operationType operationType
            join operation.organizationUnit unit
            where techProcess.id in :processIds
              and operationType.code in :typeCodes
              and unit.code = :unitCode
            order by techProcess.id asc, node.position asc
            """)
    List<TechProcessOperation> findUnitNodesForProcesses(
            @Param("processIds") Collection<Long> processIds,
            @Param("typeCodes") Collection<String> typeCodes,
            @Param("unitCode") String unitCode
    );

    /**
     * Операции подразделений в составе действующих маршрутов —
     * пробы для отчёта «Неприсвоенные операции».
     *
     * Строка — пара «маршрут — операция»: одна операция входит в
     * несколько маршрутов, и сервис склеивает их в одну строку
     * отчёта. Учитываются только активные маршруты.
     */
    @Query("""
            select
                operation.id            as operationId,
                operationName.name      as operationName,
                operationType.name      as operationTypeName,
                unit.name               as unitName,
                process.code            as processCode,
                classifier.code         as classifierCode,
                classifier.name         as classifierName,
                sensor.name             as sensorName,
                classifier.productType  as productType
            from TechProcessOperation node
            join node.techProcess process
            join node.operation operation
            join operation.operationName operationName
            join operation.operationType operationType
            join operation.organizationUnit unit
            join process.productClassifier classifier
            left join classifier.sensorCatalog sensor
            where process.active = true
              and unit.id in :unitIds
            order by operationName.name asc, process.code asc
            """)
    List<RouteOperationProbe> findUnitRouteOperations(
            @Param("unitIds") List<Long> unitIds
    );

}
