package ru.company.production.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.company.production.entity.WorkPlace;

import java.util.List;
import java.util.Optional;

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

    /*
     * Действующие рабочие места по списку подразделений — граница
     * службы качества, которая отвечает за БТК и БВК разом.
     *
     * Без distinct: сортировка идёт по наименованию подразделения, а
     * MySQL с ONLY_FULL_GROUP_BY требует, чтобы колонка ORDER BY была
     * в списке выборки. Все связи здесь — ManyToOne, поэтому join fetch
     * строки не размножает и дедупликация запросу не нужна.
     */
    @Query("""
            select place
            from WorkPlace place
            left join fetch place.workPlaceType
            left join fetch place.executorRole
            left join fetch place.organizationUnit
            where place.organizationUnit.id in :organizationUnitIds
              and place.active = true
            order by place.organizationUnit.name asc, place.code asc
            """)
    List<WorkPlace> findActiveByOrganizationUnitIds(
            @Param("organizationUnitIds") List<Long> organizationUnitIds
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

    /*
     * Глобальный поиск рабочих мест.
     *
     * Ищем по коду «РМ…», наименованию и типу: это основные
     * поля, по которым оператор ориентируется.
     */
    @Query("""
            select place.id as id,
                   place.code as code,
                   place.name as name,
                   placeType.code as placeTypeCode,
                   placeType.name as placeTypeName
            from WorkPlace place
            join place.workPlaceType placeType
            where place.active = true
              and (
                    lower(place.code)
                        like lower(concat('%', :term, '%'))
                 or lower(place.name)
                        like lower(concat('%', :term, '%'))
                 or lower(placeType.name)
                        like lower(concat('%', :term, '%'))
              )
            order by place.code
            """)
    List<WorkPlaceSearchView> search(
            @Param("term") String term,
            Pageable pageable
    );
}