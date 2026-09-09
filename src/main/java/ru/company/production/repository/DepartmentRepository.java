package ru.company.production.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.company.production.entity.Department;

import java.util.List;

public interface DepartmentRepository extends JpaRepository<Department, Long> {

    List<Department> findByActiveTrueOrderByNameAsc();
}