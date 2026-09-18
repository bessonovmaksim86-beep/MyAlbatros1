package ru.company.production.repository;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.company.production.entity.Operation;

import java.util.List;
import java.util.Optional;

public interface OperationRepository
        extends JpaRepository<Operation, Long> {

    @EntityGraph(attributePaths = {
            "operationName",
            "operationType",
            "service",
            "organizationUnit",
            "executorRole"
    })
    List<Operation> findAllByOrderByOperationName_NameAsc();

    /*
     * Комплектовочные операции — единственный допустимый выбор при
     * создании заказа на производство. Тип задаётся кодом, чтобы
     * не зависеть от идентификаторов, которые различаются
     * в разных базах (миграция V15 вставляет KITTING по коду).
     */
    @EntityGraph(attributePaths = {
            "operationName",
            "operationType",
            "service",
            "organizationUnit",
            "executorRole"
    })
    @Query("""
            select operation
            from Operation operation
            join operation.operationType operationType
            where operationType.code = :typeCode
            order by operation.operationName.name asc
            """)
    List<Operation> findByOperationTypeCode(
            @Param("typeCode") String typeCode
    );

    @EntityGraph(attributePaths = {
            "operationName",
            "operationType",
            "service",
            "organizationUnit",
            "executorRole"
    })
    @Query("""
            select operation
            from Operation operation
            where operation.id = :id
            """)
    Optional<Operation> findDetailedById(
            @Param("id") Long id
    );

    @Query("""
            select count(operation)
            from Operation operation
            where lower(operation.operationName.name)
                    = lower(:operationName)
              and operation.operationType.id = :operationTypeId
              and operation.service.id = :serviceId
              and (
                    (
                        :organizationUnitId is null
                        and operation.organizationUnit is null
                    )
                    or
                    (
                        :organizationUnitId is not null
                        and operation.organizationUnit.id
                            = :organizationUnitId
                    )
              )
              and operation.executorRole.id = :executorRoleId
            """)
    long countDuplicates(
            @Param("operationName")
            String operationName,

            @Param("operationTypeId")
            Long operationTypeId,

            @Param("serviceId")
            Long serviceId,

            @Param("organizationUnitId")
            Long organizationUnitId,

            @Param("executorRoleId")
            Long executorRoleId
    );

    @Query("""
            select count(operation)
            from Operation operation
            where lower(operation.operationName.name)
                    = lower(:operationName)
              and operation.operationType.id = :operationTypeId
              and operation.service.id = :serviceId
              and (
                    (
                        :organizationUnitId is null
                        and operation.organizationUnit is null
                    )
                    or
                    (
                        :organizationUnitId is not null
                        and operation.organizationUnit.id
                            = :organizationUnitId
                    )
              )
              and operation.executorRole.id = :executorRoleId
              and operation.id <> :operationId
            """)
    long countDuplicatesForUpdate(
            @Param("operationName")
            String operationName,

            @Param("operationTypeId")
            Long operationTypeId,

            @Param("serviceId")
            Long serviceId,

            @Param("organizationUnitId")
            Long organizationUnitId,

            @Param("executorRoleId")
            Long executorRoleId,

            @Param("operationId")
            Long operationId
    );
}