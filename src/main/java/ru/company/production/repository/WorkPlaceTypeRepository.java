package ru.company.production.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.company.production.entity.WorkPlaceType;

import java.util.List;

public interface WorkPlaceTypeRepository
        extends JpaRepository<WorkPlaceType, Long> {

    List<WorkPlaceType> findByActiveTrueOrderByNameAsc();
}
