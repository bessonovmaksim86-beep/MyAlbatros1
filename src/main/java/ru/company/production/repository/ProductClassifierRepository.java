package ru.company.production.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.company.production.entity.ProductClassifier;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProductClassifierRepository extends JpaRepository<ProductClassifier, Long> {

    @Query("""
            select distinct classifier
            from ProductClassifier classifier
            left join fetch classifier.sensorCatalog
            left join fetch classifier.inclusions inclusion
            left join fetch inclusion.target target
            left join fetch target.sensorCatalog
            order by classifier.code
            """)
    List<ProductClassifier> findAllDetailed();

    @Query("""
            select distinct classifier
            from ProductClassifier classifier
            left join fetch classifier.sensorCatalog
            left join fetch classifier.inclusions inclusion
            left join fetch inclusion.target target
            left join fetch target.sensorCatalog
            where classifier.id = :id
            """)
    Optional<ProductClassifier> findDetailedById(@Param("id") Long id);

    @Query("""
            select distinct classifier
            from ProductClassifier classifier
            left join fetch classifier.sensorCatalog
            where (:excludedId is null or classifier.id <> :excludedId)
            order by classifier.code
            """)
    List<ProductClassifier> findInclusionOptionsDetailed(@Param("excludedId") Long excludedId);

    @Query("""
            select classifier.productType as productType,
                   count(classifier.id) as total
            from ProductClassifier classifier
            group by classifier.productType
            """)
    List<ProductTypeCountProjection> countGroupedByProductType();

    List<ProductClassifier> findAllByOrderByCodeAsc();

    /**
     * Изделия для каскадного выбора изделия техпроцесса.
     */
    @Query("""
            select classifier.id as id,
                   classifier.code as code,
                   classifier.productType as productType,
                   classifier.name as name,
                   sensor.id as sensorClassId,
                   sensor.name as sensorClassName
            from ProductClassifier classifier
            left join classifier.sensorCatalog sensor
            order by classifier.code
            """)
    List<ClassifierOptionProjection> findAllOptions();
    boolean existsByCode(Integer code);
    boolean existsByCodeAndIdNot(Integer code, Long id);
}
