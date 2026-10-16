package ru.company.production.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import ru.company.production.entity.ControlParam;

import java.util.List;
import java.util.Optional;

public interface ControlParamRepository
        extends JpaRepository<ControlParam, Long> {

    /*
     * Список справочника вместе с вариантами: open-in-view выключен,
     * поэтому варианты подтягивают одним запросом, а не по одной
     * коллекции на каждый параметр.
     *
     * Порядок значений в списке сохраняют как порядок их ввода —
     * технолог перечисляет варианты осмысленно (1 слой, 2 слоя, …),
     * сортировка по тексту этот порядок разрушила бы.
     */
    @Query("""
            select distinct param
            from ControlParam param
            left join fetch param.values value
            order by param.name asc
            """)
    List<ControlParam> findAllDetailed();

    @Query("""
            select param
            from ControlParam param
            left join fetch param.values value
            where param.id = :id
            """)
    Optional<ControlParam> findDetailedById(Long id);

    Optional<ControlParam> findByName(String name);

    Optional<ControlParam> findByNameAndIdNot(String name, Long id);
}
