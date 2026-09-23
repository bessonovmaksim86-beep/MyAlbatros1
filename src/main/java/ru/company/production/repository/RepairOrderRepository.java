package ru.company.production.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.company.production.entity.RepairOrder;
import ru.company.production.entity.RepairOrderStatus;
import ru.company.production.entity.RepairOrderType;

import java.util.List;
import java.util.Optional;

@Repository
public interface RepairOrderRepository extends JpaRepository<RepairOrder, Long> {

    /**
     * Заказы вместе с автором и поставщиком: список выводит ФИО и
     * написание поставщика ссылкой, а {@code open-in-view} выключен —
     * ленивая связь вне транзакции не читается.
     */
    @Query("""
            select ord
            from RepairOrder ord
            left join fetch ord.createdBy author
            left join fetch ord.supplier supplier
            order by ord.orderDate desc, ord.id desc
            """)
    List<RepairOrder> findAllDetailed();

    @Query("""
            select ord
            from RepairOrder ord
            left join fetch ord.createdBy author
            left join fetch ord.supplier supplier
            where ord.id = :id
            """)
    Optional<RepairOrder> findDetailedById(@Param("id") Long id);

    /**
     * Номера заданного префикса — по ним генератор подбирает
     * следующий номер заказа.
     *
     * Берут все номера, включая отменённые: номер заказа обязан быть
     * свободен для сверки бумажных документов, а не только для
     * незакрытых заказов.
     */
    @Query("""
            select ord.number
            from RepairOrder ord
            where ord.number like concat(:prefix, '%')
            order by ord.number asc
            """)
    List<String> findNumbersByPrefix(@Param("prefix") String prefix);

    long countByStatus(RepairOrderStatus status);

    long countByOrderType(RepairOrderType orderType);

    /** Незакрытых заказов: их считают сводка модуля и счётчик меню. */
    @Query("""
            select count(ord.id)
            from RepairOrder ord
            where ord.status in
                (ru.company.production.entity.RepairOrderStatus.NEW,
                 ru.company.production.entity.RepairOrderStatus.IN_WORK)
            """)
    long countOpen();
}
