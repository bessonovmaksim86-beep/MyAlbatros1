package ru.company.production.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.company.production.entity.CustomerOrderItem;
import ru.company.production.entity.CustomerOrderItemStatus;

import java.math.BigDecimal;
import java.util.List;

@Repository
public interface CustomerOrderItemRepository
        extends JpaRepository<CustomerOrderItem, Long> {

    @Query("""
            select
                item.id as id,
                item.position as itemPosition,
                classifier.id as classifierId,
                classifier.code as classifierCode,
                classifier.name as classifierName,
                sensor.name as sensorClassName,
                item.quantity as quantity,
                item.doneQuantity as doneQuantity,
                item.unit as unit,
                item.dueDate as dueDate,
                item.status as status,
                item.note as note
            from CustomerOrderItem item
            join item.productClassifier classifier
            left join classifier.sensorCatalog sensor
            where item.customerOrder.id = :orderId
            order by item.position
            """)
    List<CustomerOrderItemView> findItemsByOrderId(
            @Param("orderId") Long orderId
    );

    @Query("""
            select count(item.id)
            from CustomerOrderItem item
            where item.customerOrder.id = :orderId
            """)
    long countByOrderId(@Param("orderId") Long orderId);

    List<CustomerOrderItem> findByCustomerOrder_IdOrderByPositionAsc(
            Long orderId
    );

    /*
     * Количество позиций заказа в заданном статусе —
     * используется для счётчиков статуса самого заказа.
     */
    @Query("""
            select count(item.id)
            from CustomerOrderItem item
            where item.customerOrder.id = :orderId
              and item.status = :status
            """)
    long countByOrderIdAndStatus(
            @Param("orderId") Long orderId,
            @Param("status") CustomerOrderItemStatus status
    );

    /**
     * Суммарное заказанное количество продукции по типам изделий
     * (карточки обзора диспетчерского отдела).
     *
     * Учитываются позиции только активных заказов.
     * Тип изделия берётся из классификатора продукции.
     *
     * Без записей возвращается пустой список.
     */
    @Query("""
            select
                classifier.productType as productType,
                sum(item.quantity) as totalQuantity
            from CustomerOrderItem item
            join item.productClassifier classifier
            join item.customerOrder ord
            where ord.active = true
            group by classifier.productType
            """)
    List<ProductTypeQuantityProjection> sumQuantityGroupedByProductType();

    /**
     * Общее заказанное количество продукции по всем позициям
     * активных заказов (карточка «всего внесённой продукции»).
     *
     * null — если позиций ещё нет.
     */
    @Query("""
            select sum(item.quantity)
            from CustomerOrderItem item
            join item.customerOrder ord
            where ord.active = true
            """)
    BigDecimal sumActivePlannedQuantity();
}
