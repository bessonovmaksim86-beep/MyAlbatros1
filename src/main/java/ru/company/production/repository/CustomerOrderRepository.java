package ru.company.production.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.company.production.entity.CustomerOrder;
import ru.company.production.entity.CustomerOrderStatus;

import java.util.List;
import java.util.Optional;

@Repository
public interface CustomerOrderRepository
        extends JpaRepository<CustomerOrder, Long> {

    /**
     * Список заказов покупателя: один заказ = одна строка.
     *
     * Количество позиций (itemCount) и суммарное плановое количество
     * (plannedQuantity) считаются скалярными подзапросами, чтобы join
     * не размножал строки заказа.
     *
     * Если у заказа нет ни одной позиции, plannedQuantity равен null —
     * getFormattedPlannedQuantity() в проекции покажет «0».
     *
     * Важно: внутри @Query комментарии недопустимы — их не принимает
     * парсер JPQL Spring Data (ошибка "no viable alternative").
     */
    @Query("""
            select
                ord.id as id,
                ord.number as number,
                ord.orderDate as orderDate,
                ord.dueDate as dueDate,
                customer.id as customerId,
                customer.name as customerName,
                ord.status as status,
                priority.name as priorityName,
                priority.badgeClass as priorityBadge,
                ord.b24OrderUrl as b24OrderUrl,
                ord.b24ChecklistUrl as b24ChecklistUrl,
                ord.active as active,
                (
                    select count(item.id)
                    from CustomerOrderItem item
                    where item.customerOrder = ord
                ) as itemCount,
                (
                    select sum(item.quantity)
                    from CustomerOrderItem item
                    where item.customerOrder = ord
                ) as plannedQuantity
            from CustomerOrder ord
            join ord.customer customer
            left join ord.priority priority
            where ord.active = true
            order by ord.orderDate desc, ord.number desc
            """)
    List<CustomerOrderListItemView> findAllListItems();

    /*
     * Приоритет подтягивается fetch-join'ом: open-in-view выключен,
     * а карточка и форма правки читают наименование и бейдж приоритета
     * уже вне сессии — ленивая ссылка дала бы LazyInitializationException.
     */
    @Query("""
            select distinct ord
            from CustomerOrder ord
            left join fetch ord.customer
            left join fetch ord.priority
            where ord.id = :id
            """)
    Optional<CustomerOrder> findHeaderById(@Param("id") Long id);

    @Query("""
            select distinct ord
            from CustomerOrder ord
            left join fetch ord.customer
            left join fetch ord.priority
            left join fetch ord.items item
            left join fetch item.productClassifier classifier
            left join fetch classifier.sensorCatalog
            where ord.id = :id
            order by item.position
            """)
    Optional<CustomerOrder> findFullById(@Param("id") Long id);

    Optional<CustomerOrder> findByNumber(String number);

    boolean existsByNumber(String number);

    boolean existsByNumberAndIdNot(String number, Long id);

    long countByActiveTrue();

    /**
     * Счётчик активных заказов в заданном статусе
     * для карточек сводки.
     */
    @Query("""
            select count(ord)
            from CustomerOrder ord
            where ord.active = true
              and ord.status = :status
            """)
    long countActiveByStatus(
            @Param("status") CustomerOrderStatus status
    );
}