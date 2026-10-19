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

}
