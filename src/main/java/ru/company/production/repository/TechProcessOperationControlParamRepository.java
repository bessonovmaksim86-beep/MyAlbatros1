package ru.company.production.repository;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.company.production.entity.TechProcessOperationControlParam;

import java.util.Collection;
import java.util.List;

@Repository
public interface TechProcessOperationControlParamRepository
        extends JpaRepository<TechProcessOperationControlParam, Long> {

    /**
     * Есть ли у параметра строки маршрута со ссылкой на вариант.
     *
     * Нужен смене типа параметра на TEXT_INPUT: варианты при этом
     * удаляют, а строка маршрута, ссылающаяся на удалённый вариант,
     * оборвала бы внешний ключ. Текстовая строка маршрута
     * (control_param_value_id IS NULL) варианту не принадлежит и
     * смене типа не мешает.
     */
    @Query("""
            select count(link) > 0
            from TechProcessOperationControlParam link
            where link.controlParam.id = :controlParamId
              and link.controlParamValue is not null
            """)
    boolean existsByControlParamId(
            @Param("controlParamId") Long controlParamId
    );

    /**
     * Контролируемые параметры одной операции маршрута.
     *
     * Нужны форме проведения: исполнитель обязан отметить параметры,
     * заданные технологом именно в этом узле маршрута. Вид параметра,
     * его варианты и требуемый классификатор ячейки подтягиваются
     * fetch-join'ом — open-in-view выключен, а шаблон читает и
     * наименование вида, и список вариантов.
     */
    @EntityGraph(attributePaths = {
            "controlParam",
            "controlParam.values",
            "controlParamValue",
            "cellClassifier"
    })
    @Query("""
            select link
            from TechProcessOperationControlParam link
            join link.techProcessOperation node
            where node.techProcess.id = :processId
              and node.operation.id = :operationId
            order by link.id asc
            """)
    List<TechProcessOperationControlParam> findForOperationNode(
            @Param("processId") Long processId,
            @Param("operationId") Long operationId
    );

    /**
     * Узлы маршрута, у которых есть хотя бы один контролируемый параметр.
     *
     * Нужен списку закреплённых работ: массовое проведение закрывает
     * только экземпляры без отметок, а обязательный параметр нельзя
     * закрыть без значения. Возвращаются пары «техпроцесс · операция»
     * одним запросом на весь список — по одной проверке на строку
     * график подбор превратил бы раскрытие панели в десятки запросов.
     */
    @Query("""
            select distinct node.techProcess.id, node.operation.id
            from TechProcessOperationControlParam link
            join link.techProcessOperation node
            where node.techProcess.id in :processIds
              and node.operation.id in :operationIds
            """)
    List<Object[]> findParametrisedNodes(
            @Param("processIds") Collection<Long> processIds,
            @Param("operationIds") Collection<Long> operationIds
    );

}
