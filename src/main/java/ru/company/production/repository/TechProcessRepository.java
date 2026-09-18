package ru.company.production.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.company.production.entity.TechProcess;

import java.util.List;
import java.util.Optional;

@Repository
public interface TechProcessRepository
        extends JpaRepository<TechProcess, Long> {

    @Query("""
            select distinct process
            from TechProcess process
            left join fetch process.productClassifier classifier
            left join fetch classifier.sensorCatalog
            order by process.code
            """)
    List<TechProcess> findAllDetailed();

    @Query("""
            select distinct process
            from TechProcess process
            left join fetch process.productClassifier classifier
            left join fetch classifier.sensorCatalog
            where process.id = :id
            """)
    Optional<TechProcess> findHeaderById(@Param("id") Long id);

    @Query("""
            select distinct process
            from TechProcess process
            left join fetch process.productClassifier classifier
            left join fetch classifier.sensorCatalog
            left join fetch process.operations operation
            left join fetch operation.operation baseOperation
            left join fetch baseOperation.operationName
            left join fetch baseOperation.operationType
            left join fetch baseOperation.service
            left join fetch baseOperation.organizationUnit
            left join fetch baseOperation.executorRole
            where process.id = :id
            order by operation.position
            """)
    Optional<TechProcess> findFullById(@Param("id") Long id);

    boolean existsByCode(String code);

    /*
     * Список для страницы техпроцессов: одна строка на пару
     * «техпроцесс — изделие». Маршрут, применённый к нескольким
     * изделиям, показан отдельно для каждого из них.
     */
    @Query("""
            select
                process.id as id,
                process.code as code,
                classifier.id as classifierId,
                classifier.code as classifierCode,
                classifier.productType as productType,
                classifier.name as classifierName,
                sensor.name as sensorClassName,
                (
                    select count(node)
                    from TechProcessOperation node
                    where node.techProcess = process
                ) as operationCount
            from TechProcess process
            join process.appliedClassifiers classifier
            left join classifier.sensorCatalog sensor
            where process.active = true
            order by process.code, classifier.code
            """)
    List<TechProcessListItemView> findAllWithAppliedClassifiers();

    /* Все коды: префикс отбирается на стороне. */
    @Query("select process.code from TechProcess process")
    List<String> findAllCodes();

    /*
     * Техпроцесс со списком изделий, к которым он применён.
     * Используется при применении маршрута к новому изделию.
     */
    @Query("""
            select distinct process
            from TechProcess process
            left join fetch process.appliedClassifiers
            where process.id = :id
            """)
    Optional<TechProcess> findWithAppliedClassifiers(
            @Param("id") Long id
    );

    /*
     * Сколько изделий использует каждый техпроцесс.
     * Отдельный запрос, чтобы не раздувать основной fetch-запрос.
     */
    @Query("""
            select process.id, count(applied.id)
            from TechProcess process
            left join process.appliedClassifiers applied
            group by process.id
            """)
    List<Object[]> countAppliedClassifiers();

    boolean existsByCodeAndIdNot(String code, Long id);

    long countByActiveTrue();

    /*
     * Есть ли у изделия активный техпроцесс — основной или
     * применённый к нему. Используется при создании заказа:
     * выпуск позиции по базовому техпроцессу возможен только
     * когда такой техпроцесс у изделия задан.
     */
    @Query("""
            select count(distinct process.id)
            from TechProcess process
            left join process.appliedClassifiers applied
            where process.active = true
              and (
                    process.productClassifier.id = :classifierId
                    or applied.id = :classifierId
              )
            """)
    long countActiveForClassifier(@Param("classifierId") Long classifierId);
}
