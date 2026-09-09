package ru.company.production.repository;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.company.production.entity.SensorCatalog;
import java.util.List;
public interface SensorCatalogRepository extends JpaRepository<SensorCatalog,Long> {
 List<SensorCatalog> findAllByOrderByNameAsc();
}
