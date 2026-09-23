package ru.company.production.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.company.production.entity.RepairOrderItem;

import java.util.Collection;
import java.util.List;

@Repository
public interface RepairOrderItemRepository
        extends JpaRepository<RepairOrderItem, Long> {

    /**
     * Состав заказа с классификатором ячейки: строка выводит
     * наименование, а {@code open-in-view} выключен.
     */
    @Query("""
            select item
            from RepairOrderItem item
            left join fetch item.productClassifier classifier
            left join fetch classifier.sensorCatalog
            where item.repairOrder.id = :orderId
            order by item.position asc, item.id asc
            """)
    List<RepairOrderItem> findDetailsByOrderId(@Param("orderId") Long orderId);

    /**
     * Номера ячеек, уже включённых в незакрытый заказ.
     *
     * Нужны перечню изолятора: ячейку, по которой заказ уже выставлен,
     * отмечают отдельно, иначе руководитель повторно оформит ремонт на
     * ту же ячейку. Отменённые заказы не учитывают — снятый заказ
     * возвращает ячейку в работу.
     */
    @Query("""
            select distinct item.fullNumber
            from RepairOrderItem item
            where item.repairOrder.status <>
                  ru.company.production.entity.RepairOrderStatus.CANCELLED
            """)
    List<String> findNumbersInOpenOrders();

    /**
     * Номера из перечня, по которым заказ уже выставлен.
     *
     * Отбор по списку, а не целиком: карточка ячейки открывает одну
     * строку, и тянуть ради неё весь перечень заказов незачем.
     */
    @Query("""
            select distinct item.fullNumber
            from RepairOrderItem item
            where item.fullNumber in :numbers
              and item.repairOrder.status <>
                  ru.company.production.entity.RepairOrderStatus.CANCELLED
            """)
    List<String> findNumbersInOpenOrdersByNumbers(
            @Param("numbers") Collection<String> numbers
    );
}
