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

    /**
     * Исполнители, способные выполнить операцию — мощность этапа
     * планирования.
     *
     * Сотрудник способен, если он закреплен за самой операцией
     * (привязка OPERATION) либо за одним из рабочих мест, на которых
     * операция допускается (привязка WORK_PLACE). Именно это множество
     * даёт знаменатель загрузки: люди × 8 часов.
     *
     * Список workPlaceIds передаётся из маршрутной операции
     * (TechProcessOperation.workPlaces); если он пуст — учитываются
     * только прямые привязки к операции.
     */
    @Query("""
            select distinct binding.employee.id
            from WorkshopStaffBinding binding
            where binding.employee.active = true
              and (
                    binding.operation.id = :operationId
                 or (:hasPlaces = true
                     and binding.workPlace.id in :workPlaceIds)
              )
            """)
    List<Long> findCapableEmployeeIds(
            @Param("operationId") Long operationId,
            @Param("workPlaceIds") List<Long> workPlaceIds,
            @Param("hasPlaces") boolean hasPlaces
    );

    /**
     * Идентификаторы операций, закреплённых за действующими
     * сотрудниками подразделения — множество «есть исполнитель» для
     * отчёта о неприсвоенных операциях.
     *
     * Неработающий сотрудник операцию не закрывает: привязка
     * уволенного остаётся в справочнике, но выполнить работу ему
     * некому, поэтому операция без живого исполнителя должна
     * остаться в отчёте. Граница по подразделениям нужна, потому что
     * руководитель отвечает за закрепление у своих людей.
     */
    @Query("""
            select distinct binding.operation.id
            from WorkshopStaffBinding binding
            where binding.bindingType = ru.company.production.entity.StaffBindingType.OPERATION
              and binding.operation.id is not null
              and binding.employee.active = true
              and binding.employee.organizationUnit.id in :unitIds
            """)
    List<Long> findAssignedOperationIds(
            @Param("unitIds") List<Long> unitIds
    );
}
