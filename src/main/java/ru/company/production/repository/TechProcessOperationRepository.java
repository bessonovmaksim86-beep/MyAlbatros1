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

}
