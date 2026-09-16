package ru.company.production.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.company.production.entity.OperationName;

import java.util.Optional;

public interface OperationNameRepository
        extends JpaRepository<OperationName, Long> {

    Optional<OperationName> findFirstByNameIgnoreCase(String name);
}