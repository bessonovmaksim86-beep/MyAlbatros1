package ru.company.production.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.company.production.entity.ProductionService;

import java.util.List;
import java.util.Optional;

public interface ProductionServiceRepository
        extends JpaRepository<ProductionService, Long> {

    Optional<ProductionService> findByCodeIgnoreCase(String code);

    List<ProductionService> findAllByActiveTrueOrderByCodeAsc();

    List<ProductionService> findAllByActiveTrueOrderByNameAsc();
}