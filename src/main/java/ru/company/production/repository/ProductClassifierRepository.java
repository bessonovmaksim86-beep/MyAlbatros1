package ru.company.production.repository;

import org.springframework.data.domain.Pageable;
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
     * Ячейки, входящие в изделие (датчик) как составные части.
     *
     * Нужен блоку «Контролируемые параметры» формы техпроцесса: для
     * параметра «…ячейки…» значение выбирают не из справочника
     * вариантов, а из состава изделия — тех же ячеек, что перечислены
     * в его классификаторе (classifier_inclusion, миграция V1).
     *
     * Тип CELL задан в запросе, а не берётся на стороне сервиса:
     * в состав датчика могут входить и детали других видов, а
     * контролируемый параметр обязан называть именно ячейку.
     *
     * Порядок — по коду, как во всех списках классификатора, чтобы
     * технологу не пришлось искать нужную ячейку в произвольной
     * последовательности.
     */
    @Query("""
            select distinct target
            from ClassifierInclusion inclusion
            join inclusion.target target
            where inclusion.owner.id = :ownerId
              and target.productType =
                  ru.company.production.entity.ProductType.CELL
            order by target.code asc
            """)
    List<ProductClassifier> findCellOptionsByOwner(
            @Param("ownerId") Long ownerId
    );

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

    /**
     * Изделия указанного типа для выбора в групповом или ремонтном
     * заказе-комплектующем (миграция V37).
     *
     * Выбор ограничен типом сознательно: групповой заказ на ячейки
     * обязан называть ячейку, а заказ на приборы — прибор, иначе
     * количество повиснет на изделии другого назначения. Тип передаётся
     * параметром, а не отдельными запросами, потому что справочников
     * видов в форме несколько, и заводить запрос на каждый — значит
     * дублировать один и тот же текст.
     */
    @Query("""
            select distinct classifier
            from ProductClassifier classifier
            left join fetch classifier.sensorCatalog
            where classifier.productType = :productType
            order by classifier.code
            """)
    List<ProductClassifier> findByProductTypeOptions(
            @Param("productType") ru.company.production.entity.ProductType productType
    );

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

    /*
     * Глобальный поиск изделий классификатора.
     *
     * Ищем по коду изделия (число приводят к строке), его
     * наименованию и роду датчика: у изделия типа «Датчик» своего
     * наименования нет, и искать нужно по справочнику родов.
     */
    @Query("""
            select classifier.id as id,
                   classifier.code as code,
                   classifier.productType as productType,
                   classifier.name as name,
                   sensor.name as sensorClassName
            from ProductClassifier classifier
            left join classifier.sensorCatalog sensor
            where cast(classifier.code as string)
                        like concat('%', :term, '%')
               or lower(classifier.name)
                        like lower(concat('%', :term, '%'))
               or lower(sensor.name)
                        like lower(concat('%', :term, '%'))
            order by classifier.code
            """)
    List<ClassifierSearchView> search(
            @Param("term") String term,
            Pageable pageable
    );
}