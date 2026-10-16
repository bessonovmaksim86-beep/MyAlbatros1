package ru.company.production.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.company.production.entity.TechProcessOperationControlParam;

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
}
