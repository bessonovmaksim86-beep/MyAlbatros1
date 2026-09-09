package ru.company.production.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.company.production.entity.ProductClassifier;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProductClassifierRepository
        extends JpaRepository<ProductClassifier, Long> {

    /**
     * Получение списка для главной страницы.
     *
     * Загружаются sensorCatalog и состав классификатора,
     * чтобы Thymeleaf не обращался к ленивым связям
     * после завершения транзакции.
     */
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

    /**
     * Полная загрузка одного классификатора.
     */
    @Query("""
            select distinct classifier
            from ProductClassifier classifier
            left join fetch classifier.sensorCatalog
            left join fetch classifier.inclusions inclusion
            left join fetch inclusion.target target
            left join fetch target.sensorCatalog
            where classifier.id = :id
            """)
    Optional<ProductClassifier> findDetailedById(
            @Param("id") Long id
    );

    /**
     * Классификаторы, которые можно добавить
     * в состав текущего классификатора.
     */
    @Query("""
            select distinct classifier
            from ProductClassifier classifier
            left join fetch classifier.sensorCatalog
            where (
                :excludedId is null
                or classifier.id <> :excludedId
            )
            order by classifier.code
            """)
    List<ProductClassifier> findInclusionOptionsDetailed(
            @Param("excludedId") Long excludedId
    );

    List<ProductClassifier> findAllByOrderByCodeAsc();

    boolean existsByCode(Integer code);

    boolean existsByCodeAndIdNot(
            Integer code,
            Long id
    );
}