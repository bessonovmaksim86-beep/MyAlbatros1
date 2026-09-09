package ru.company.production.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Service;
import ru.company.production.entity.OrganizationUnit;
import ru.company.production.entity.ProductionService;

import java.util.List;

public interface OrganizationUnitRepository
        extends JpaRepository<OrganizationUnit, Long> {

    boolean existsByCodeIgnoreCase(String code);

    List<OrganizationUnit> findAllByActiveTrueOrderByNameAsc();

    List<OrganizationUnit>
    findAllByServiceIdAndActiveTrueOrderByNameAsc(Long serviceId);
    boolean existsByServiceAndName(ProductionService service, String name);
}