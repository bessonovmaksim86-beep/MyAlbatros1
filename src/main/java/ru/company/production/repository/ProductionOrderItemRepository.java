package ru.company.production.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.company.production.entity.ProductionItemStatus;
import ru.company.production.entity.ProductionOrderItem;

import java.util.Collection;
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

    /*
     * Серийники выпуска для набора заказов покупателя.
     *
     * Нужен разделу распределения: строка графика планирует изделие до
     * выпуска и своего серийного номера не хранит, а номер появляется
     * именно здесь — в составе выпуска, привязанном к позиции заказа
     * покупателя. Выборка пакетная (по всем заказам страницы одним
     * запросом), иначе таблица распределения порождала бы запрос на
     * каждую строку.
     *
     * Порядок задаётся полем position выпуска (он сквозной по заказу);
     * номер экземпляра внутри позиции по нему восстанавливает сервис.
     * Полное наименование берётся из fullName позиции заказа («как в
     * КД»), а род датчика и имя классификатора подставляются, когда
     * fullName не заполнен. Строки без серийного номера исключены:
     * сопоставлять нечего.
     *
     * Aliases join'а (serialRow) и выбранного поля (serial) разведены
     * намеренно: совпадающее имя алиаса связи и алиаса выборки
     * Hibernate трактует неоднозначно при сборке метамодели запроса.
     */
    @Query("""
            select
                customerOrder.id as customerOrderId,
                customerItem.position as itemPosition,
                item.position as releasePosition,
                classifier.code as classifierCode,
                serialRow.serial as serial,
                customerItem.fullName as fullName,
                sensor.name as sensorName,
                classifier.name as classifierName
            from ProductionOrderItem item
            join item.productClassifier classifier
            join item.serial serialRow
            join item.customerOrderItem customerItem
            join customerItem.customerOrder customerOrder
            left join classifier.sensorCatalog sensor
            where customerOrder.id in :customerOrderIds
            order by customerOrder.id, customerItem.position, item.position
            """)
    List<ProductionOrderSerialProjection> findSerialsByCustomerOrderIds(
            @Param("customerOrderIds") Collection<Long> customerOrderIds
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