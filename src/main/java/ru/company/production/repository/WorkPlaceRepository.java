package ru.company.production.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.company.production.entity.WorkPlace;

import java.util.List;
import java.util.Optional;

@Repository
public interface WorkPlaceRepository
        extends JpaRepository<WorkPlace, Long> {

    @Query("""
            select distinct place
            from WorkPlace place
            left join fetch place.workPlaceType
            left join fetch place.executorRole
            left join fetch place.service
            left join fetch place.organizationUnit
            order by place.code
            """)
    List<WorkPlace> findAllDetailed();

    /*
     * Действующие рабочие места цеха — второй список привязок наравне
     * с операциями раздела «Исполнитель».
     */
    @Query("""
            select distinct place
            from WorkPlace place
            left join fetch place.workPlaceType
            left join fetch place.executorRole
            where place.organizationUnit.id = :organizationUnitId
              and place.active = true
            order by place.code
            """)
    List<WorkPlace> findActiveByOrganizationUnitId(
            @Param("organizationUnitId") Long organizationUnitId
    );

    @Query("""
            select place
            from WorkPlace place
            where place.id = :id
            """)
    Optional<WorkPlace> findDetailedById(Long id);

    /* Код задаётся оператором вручную и обязан быть уникальным. */
    boolean existsByCode(String code);

    boolean existsByCodeAndIdNot(String code, Long id);

    List<WorkPlace> findByActiveTrueOrderByCodeAsc();

    long countByActiveTrue();
}
