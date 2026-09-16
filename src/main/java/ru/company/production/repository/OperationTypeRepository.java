package ru.company.production.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.company.production.entity.OperationType;

import java.util.List;
public interface OperationTypeRepository extends JpaRepository<OperationType,Long>
{
        List<OperationType> findAllByActiveTrueOrderByNameAsc();}
