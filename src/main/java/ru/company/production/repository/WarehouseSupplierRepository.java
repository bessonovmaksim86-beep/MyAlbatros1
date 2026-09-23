package ru.company.production.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.company.production.entity.WarehouseSupplier;

import java.util.List;
import java.util.Optional;

@Repository
public interface WarehouseSupplierRepository
        extends JpaRepository<WarehouseSupplier, Long> {

    List<WarehouseSupplier> findAllByOrderByNameAsc();

    /**
     * Поиск по написанию без учёта регистра: сравнение идёт по
     * нормализованной строке, потому что в партиях поставщик
     * записан свободно введённым текстом.
     */
    @Query("""
            select supplier
            from WarehouseSupplier supplier
            where lower(trim(supplier.name)) = lower(trim(:name))
            """)
    Optional<WarehouseSupplier> findByNormalizedName(@Param("name") String name);
}
