package ru.company.production.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.company.production.entity.ProductSerial;
import ru.company.production.entity.ProductSerialStatus;
import ru.company.production.entity.ProductType;

import java.util.List;

@Repository
public interface ProductSerialRepository
        extends JpaRepository<ProductSerial, Long> {

    /**
     * Последний выданный порядковый номер в пределах типа изделия.
     *
     * Нумерация ведётся независимо по каждому типу (уникальный ключ
     * uk_product_serial_type_serial), поэтому следующий серийник —
     * максимум по типу плюс единица.
     *
     * null — если по типу номеров ещё не выдавали.
     */
    @Query("""
            select max(serial.serial)
            from ProductSerial serial
            where serial.productType = :productType
            """)
    Integer findMaxSerialByProductType(
            @Param("productType") ProductType productType
    );

    /**
     * Серийники, выданные в рамках заказа на производство,
     * в порядке позиций выпуска.
     */
    @Query("""
            select serial
            from ProductSerial serial
            where serial.issuedProductionOrder.id = :productionOrderId
            order by serial.productType asc, serial.serial asc
            """)
    List<ProductSerial> findByProductionOrderId(
            @Param("productionOrderId") Long productionOrderId
    );

    /**
     * Серийники, выпущенные заказом, со связями для вывода строкой.
     *
     * Отличие от findByProductionOrderId: здесь есть fetch join
     * классификатора и справочника датчиков. Нужен он для карточки
     * заказа-комплектующего, где список изделий рисуется в шаблоне, а
     * open-in-view выключен — без fetch обращение к наименованию
     * упало бы на ленивой связи вне транзакции.
     */
    @Query("""
            select serial
            from ProductSerial serial
            left join fetch serial.productClassifier classifier
            left join fetch classifier.sensorCatalog
            where serial.issuedProductionOrder.id = :productionOrderId
            order by serial.productType asc, serial.serial asc
            """)
    List<ProductSerial> findDetailsByProductionOrderId(
            @Param("productionOrderId") Long productionOrderId
    );

    boolean existsByProductTypeAndSerial(
            ProductType productType,
            int serial
    );

    /**
     * Статус всех серийников заказа одним запросом.
     *
     * Заказ на производство содержит по строке на каждый экземпляр
     * изделия, поэтому статус меняется пакетным UPDATE: сохранение
     * сотен сущностей по одной неоправданно.
     *
     * Реестр и позиции живут одной жизнью: позиция взята в работу —
     * номер «В работе», позиция собрана — номер «Собран».
     *
     * flushAutomatically обязателен: серийники только что созданного
     * заказа ещё могут не быть записаны, а UPDATE должен их увидеть.
     */
    @Modifying(
            flushAutomatically = true,
            clearAutomatically = true
    )
    @Query("""
            update ProductSerial serial
            set serial.status = :status
            where serial.issuedProductionOrder.id = :productionOrderId
            """)
    int updateStatusByOrderId(
            @Param("productionOrderId") Long productionOrderId,
            @Param("status") ProductSerialStatus status
    );
}
