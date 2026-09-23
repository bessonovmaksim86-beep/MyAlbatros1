package ru.company.production.repository;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.company.production.entity.WorkshopStaffBinding;

import java.util.List;
import java.util.Optional;

@Repository
public interface WorkshopStaffBindingRepository
        extends JpaRepository<WorkshopStaffBinding, Long> {

    /**
     * Привязки нескольких сотрудников одним запросом: список цеха
     * строится без обращения к ленивым коллекциям вне сессии
     * (open-in-view отключён).
     */
    @EntityGraph(attributePaths = {
            "employee",
            "operation",
            "operation.operationName",
            "operation.operationType",
            "operation.executorRole",
            "workPlace",
            "workPlace.workPlaceType",
            "workPlace.executorRole"
    })
    @Query("""
            select binding
            from WorkshopStaffBinding binding
            where binding.employee.id in :employeeIds
            order by binding.bindingType asc,
                     binding.id asc
            """)
    List<WorkshopStaffBinding> findAllByEmployeeIds(
            @Param("employeeIds") List<Long> employeeIds
    );

    @EntityGraph(attributePaths = {
            "operation",
            "operation.operationName",
            "operation.operationType",
            "operation.executorRole",
            "workPlace",
            "workPlace.workPlaceType",
            "workPlace.executorRole"
    })
    Optional<WorkshopStaffBinding> findWithTargetsById(Long id);

    boolean existsByEmployeeIdAndOperationId(Long employeeId, Long operationId);

    boolean existsByEmployeeIdAndWorkPlaceId(Long employeeId, Long workPlaceId);
}
