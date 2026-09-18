package ru.company.production.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.company.production.entity.ProductionItemStatus;
import ru.company.production.entity.ProductionOrderItem;

import java.util.List;

@Repository
public interface ProductionOrderItemRepository
        extends JpaRepository<ProductionOrderItem, Long> {

    /**
     * Состав заказа на производство: одна строка — один выпускаемый
     * экземпляр с его серийным номером.
     *
     * Наименование изделия берётся из классификатора, а для датчика —
     * из справочника датчиков (sensorClassName).
     */
    @Query("""
            select
                item.id as id,
                item.position as position,
                classifier.id as classifierId,
                classifier.code as classifierCode,
                classifier.name as classifierName,
                sensor.name as sensorClassName,
                classifier.productType as productType,
                serial.serial as serialNumber,
                item.status as status,
                customerItem.position as customerOrderItemPosition,
                item.note as note
            from ProductionOrderItem item
            join item.productClassifier classifier
            left join classifier.sensorCatalog sensor
            left join item.serial serial
            left join item.customerOrderItem customerItem
            where item.productionOrder.id = :productionOrderId
            order by item.position
            """)
    List<ProductionOrderItemView> findItemsByOrderId(
            @Param("productionOrderId") Long productionOrderId
    );

    @Query("""
            select count(item.id)
            from ProductionOrderItem item
            where item.productionOrder.id = :productionOrderId
            """)
    long countByOrderId(@Param("productionOrderId") Long productionOrderId);

    @Query("""
            select count(item.id)
            from ProductionOrderItem item
            where item.productionOrder.id = :productionOrderId
              and item.status = :status
            """)
    long countByOrderIdAndStatus(
            @Param("productionOrderId") Long productionOrderId,
            @Param("status") ProductionItemStatus status
    );

    /**
     * Статус всех позиций заказа одним запросом.
     *
     * Заказ на производство может содержать сотни экземпляров
     * (по одному на изделие), поэтому статус меняется пакетным UPDATE,
     * а не сохранением каждой строки.
     */
    /*
     * flushAutomatically обязателен: позиции только что созданного
     * заказа ещё могут не быть записаны, а UPDATE должен их увидеть.
     */
    @Modifying(
            flushAutomatically = true,
            clearAutomatically = true
    )
    @Query("""
            update ProductionOrderItem item
            set item.status = :status
            where item.productionOrder.id = :productionOrderId
            """)
    int updateStatusByOrderId(
            @Param("productionOrderId") Long productionOrderId,
            @Param("status") ProductionItemStatus status
    );
}
