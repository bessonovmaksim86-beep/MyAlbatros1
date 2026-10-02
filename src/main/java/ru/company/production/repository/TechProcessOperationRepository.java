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
}
