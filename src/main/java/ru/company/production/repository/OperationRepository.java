package ru.company.production.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.company.production.entity.Operation;

public interface OperationRepository extends JpaRepository<Operation, Long> {

    boolean existsByCodeIgnoreCase(String code);
}