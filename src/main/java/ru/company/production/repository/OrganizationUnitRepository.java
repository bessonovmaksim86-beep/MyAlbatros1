package ru.company.production.repository;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.company.production.entity.OrganizationUnit;
import ru.company.production.entity.ProductionService;

import java.util.List;
import java.util.Optional;

public interface OrganizationUnitRepository
        extends JpaRepository<OrganizationUnit, Long> {

    Optional<OrganizationUnit> findByCodeIgnoreCase(String code);

    /**
     * Подразделение службы по коду: цех руководителя определяется
     * именно так, чтобы одноимённые подразделения разных служб
     * не смешивались.
     */
    Optional<OrganizationUnit> findByServiceIdAndCodeIgnoreCase(
            Long serviceId,
            String code
    );

    /*
     * Действующие подразделения службы по её идентификатору. Граница
     * руководителя службы качества: он отвечает не за одно
     * подразделение, а за всю службу (БТК и БВК), поэтому его список
     * сотрудников строится по всем подразделениям службы разом.
     */
    List<OrganizationUnit> findByServiceIdAndActiveTrueOrderByNameAsc(
            Long serviceId
    );

    boolean existsByCodeIgnoreCase(String code);

    boolean existsByServiceAndNameIgnoreCase(
            ProductionService service,
            String name
    );

    /*
     * Служба нужна странице администраторов для подписи
     * «СДП: Цех сборки», поэтому она подгружается сразу.
     */
    @EntityGraph(attributePaths = "service")
    List<OrganizationUnit> findAllByActiveTrueOrderByNameAsc();


    boolean existsByServiceAndName(
            ProductionService service,
            String name
    );
    @EntityGraph(attributePaths = "service")
    List<OrganizationUnit> findAllByOrderByNameAsc();
}