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

    /**
     * Приборы (DEVICE) для выбора в составе системы.
     */
    @Query("""
            select distinct classifier
            from ProductClassifier classifier
            left join fetch classifier.sensorCatalog
            where classifier.productType = ru.company.production.entity.ProductType.DEVICE
              and (:excludedId is null or classifier.id <> :excludedId)
            order by classifier.code
            """)
    List<ProductClassifier> findDeviceOptions(@Param("excludedId") Long excludedId);

    @Query("""
            select classifier.productType as productType,
                   count(classifier.id) as total
            from ProductClassifier classifier
            group by classifier.productType
            """)
    List<ProductTypeCountProjection> countGroupedByProductType();

    List<ProductClassifier> findAllByOrderByCodeAsc();

    /**
     * Датчики-классификаторы одного рода (одной записи
     * sensor_catalog) — область поиска при подборе изделия
     * по полному наименованию, введённому в позицию заказа.
     */
    List<ProductClassifier> findByProductTypeAndSensorCatalog_IdOrderByCodeAsc(
            ru.company.production.entity.ProductType productType,
            Long sensorCatalogId
    );

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

    /**
     * Состав системы для раскрытия полей выбора датчиков и приборов:
     * род датчика (sensorCatalog) либо прибор-классификатор.
     * Используется в форме заказа покупателя при выборе типа «Система».
     */
    @Query("""
            select component.sensorCatalog.id as sensorCatalogId,
                   sensor.name as sensorCatalogName,
                   component.componentClassifier.id as componentClassifierId,
                   componentClassifier.code as componentCode,
                   componentClassifier.name as componentName
            from SystemComponent component
            left join component.sensorCatalog sensor
            left join component.componentClassifier componentClassifier
            where component.system.id = :systemId
            order by component.position
            """)
    List<SystemComponentProjection> findSystemComponents(
            @Param("systemId") Long systemId
    );

    boolean existsByCode(Integer code);
    boolean existsByCodeAndIdNot(Integer code, Long id);
}
