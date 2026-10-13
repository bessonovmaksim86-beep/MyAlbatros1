package ru.company.production.repository;

import org.springframework.data.domain.Pageable;
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

    /*
     * Операции цеха с ролью «Исполнитель» — тот же раздел, что и в
     * справочнике операций: руководителю цеха доступны только те
     * операции, которые исполнитель этого цеха и выполняет.
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
            where operation.organizationUnit.id = :organizationUnitId
              and operation.executorRole.code = :executorRoleCode
            order by operation.operationName.name asc
            """)
    List<Operation> findUnitExecutorOperations(
            @Param("organizationUnitId") Long organizationUnitId,
            @Param("executorRoleCode") String executorRoleCode
    );

    /*
     * Операции подразделения, которыми получают
     * комплектацию (тип KIT_RECEIPT).
     *
     * Они отнесены к цеху или отделу напрямую:
     * в отличие от цеховых работ они не привязаны к
     * роли «Исполнитель», поэтому запрос выше их не
     * возвращает, и закрепить такую операцию за
     * сотрудником было нельзя. Тип задаётся кодом
     * (миграция V2), чтобы не зависеть от идентификаторов.
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
            where operation.organizationUnit.id = :organizationUnitId
              and operation.operationType.code = :typeCode
            order by operation.operationName.name asc
            """)
    List<Operation> findUnitKitReceiptOperations(
            @Param("organizationUnitId") Long organizationUnitId,
            @Param("typeCode") String typeCode
    );

    /*
     * Все операции подразделения — список раздела руководителя
     * подразделения.
     *
     * От цеховой выборки отличается тем, что не фильтрует по роли
     * исполнителя: руководитель склада или бюро работает со своими
     * операциями приёма, выдачи и перемещения, которые роль
     * «Исполнитель» не носят, и в цеховой выборке их не видно.
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
            where operation.organizationUnit.id = :organizationUnitId
            order by operation.operationName.name asc
            """)
    List<Operation> findAllByOrganizationUnitId(
            @Param("organizationUnitId") Long organizationUnitId
    );

    /*
     * Тот же список, но по нескольким подразделениям — граница
     * руководителя службы качества: он отвечает за БТК и БВК разом, и
     * запрос одного подразделения показал бы ему только одно из них.
     * Пустой список в JPQL — синтаксическая ошибка, поэтому вызывающая
     * сторона обязана проверить границу до обращения.
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
            where operation.organizationUnit.id in :organizationUnitIds
            order by operation.organizationUnit.name asc,
                     operation.operationName.name asc
            """)
    List<Operation> findAllByOrganizationUnitIds(
            @Param("organizationUnitIds") List<Long> organizationUnitIds
    );

    /**
     * Операции по списку подразделений с ролью исполнителя — граница
     * службы качества для закрепления работ.
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
            where operation.organizationUnit.id in :organizationUnitIds
              and operation.executorRole.code = :executorRoleCode
            order by operation.organizationUnit.name asc,
                     operation.operationName.name asc
            """)
    List<Operation> findUnitExecutorOperationsByUnitIds(
            @Param("organizationUnitIds") List<Long> organizationUnitIds,
            @Param("executorRoleCode") String executorRoleCode
    );

    /**
     * Операции получения комплектации по списку подразделений.
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
            where operation.organizationUnit.id in :organizationUnitIds
              and operation.operationType.code = :typeCode
            order by operation.organizationUnit.name asc,
                     operation.operationName.name asc
            """)
    List<Operation> findUnitKitReceiptOperationsByUnitIds(
            @Param("organizationUnitIds") List<Long> organizationUnitIds,
            @Param("typeCode") String typeCode
    );

    /**
     * Количество операций подразделения — счётчик бокового меню.
     * Отдельный запрос нужен потому, что для счётчика в списке строк
     * нет смысла, а выбирать их целиком на каждую страницу модуля
     * означало бы читать справочник без надобности.
     */
    long countByOrganizationUnitId(Long organizationUnitId);

    /**
     * Количество операций по списку подразделений — счётчик меню для
     * границы службы качества.
     */
    @Query("""
            select count(operation)
            from Operation operation
            where operation.organizationUnit.id in :organizationUnitIds
            """)
    long countByOrganizationUnitIds(
            @Param("organizationUnitIds") List<Long> organizationUnitIds
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

    /*
     * Глобальный поиск операций.
     *
     * Ищем по имени операции и типу: именно по этим полям
     * пользователь ориентируется в списке операций.
     */
    @Query("""
            select operation.id as id,
                   operationName.name as operationName,
                   operationType.code as operationTypeCode,
                   operationType.name as operationTypeName
            from Operation operation
            join operation.operationName operationName
            join operation.operationType operationType
            where lower(operationName.name)
                        like lower(concat('%', :term, '%'))
               or lower(operationType.name)
                        like lower(concat('%', :term, '%'))
            order by operationName.name
            """)
    List<OperationSearchView> search(
            @Param("term") String term,
            Pageable pageable
    );
}
