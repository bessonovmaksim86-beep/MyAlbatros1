package ru.company.production.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.company.production.entity.RepairKittingItem;

import java.util.Collection;
import java.util.List;

/**
 * Состав ремонтных заказов-комплектующих, проведённый бюро входного
 * контроля.
 *
 * Основной путь чтения — состав одного заказа (форма комплектования и
 * карточка заказа), второй — счётчик «Готово X из Y» сразу по всему
 * списку заказов: меню «Моих операций» рисует десятки заказов, и
 * запрос на каждый заказ раздул бы страницу до сотни обращений к базе.
 */
@Repository
public interface RepairKittingItemRepository
        extends JpaRepository<RepairKittingItem, Long> {

    /** Состав заказа в порядке проведения — история комплектования. */
    @Query("""
            select item
            from RepairKittingItem item
            left join fetch item.kittedBy kittedBy
            left join fetch item.productClassifier classifier
            where item.productionOrder.id = :productionOrderId
            order by item.kittedAt asc, item.id asc
            """)
    List<RepairKittingItem> findByOrderId(
            @Param("productionOrderId") Long productionOrderId
    );

    /**
     * Состав перечня заказов одним запросом.
     *
     * Счётчик готовности считается в памяти из этого же списка:
     * заказу соответствуют десятки ячеек, и группировка на стороне базы
     * не сэкономила бы ничего, а держать её отдельным запросом значило
     * бы читать состав дважды.
     */
    @Query("""
            select item
            from RepairKittingItem item
            where item.productionOrder.id in :productionOrderIds
            """)
    List<RepairKittingItem> findByOrderIds(
            @Param("productionOrderIds") Collection<Long> productionOrderIds
    );

    /** Проводилась ли ячейка по заказу — защита от повторного выбора. */
    boolean existsByProductionOrderIdAndFullNumber(
            Long productionOrderId,
            String fullNumber
    );

    /**
     * Все номера, когда-либо взятые в ремонтные заказы.
     *
     * Нужен перечню изолятора: форма обязана показать, что ячейка уже
     * укомплектована (в этот заказ или в любой другой), и делать это
     * надо одним чтением. Отбор по заказу или проверка «существовало
     * ли» на каждую строку дали бы запрос на каждую ячейку изолятора,
     * а их в перечне сотни.
     */
    @Query("select item.fullNumber from RepairKittingItem item")
    List<String> findAllFullNumbers();

    /** Номера, взятые конкретным заказом — чтобы снять их с выбора. */
    @Query("""
            select item.fullNumber
            from RepairKittingItem item
            where item.productionOrder.id = :productionOrderId
            """)
    List<String> findFullNumbersByOrderId(
            @Param("productionOrderId") Long productionOrderId
    );

    /**
     * Удаляет перечень комплектования ремонтного заказа.
     *
     * Ключ заказа стоит с ON DELETE CASCADE (V52); запрос нужен
     * разделу «Очистка данных», чтобы состав ушёл до удаления самого
     * заказа — пакетные удаления идут в фиксированном порядке и не
     * должны зависеть от того, как MySQL разберёт каскады.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            delete from RepairKittingItem item
            where item.productionOrder.id = :productionOrderId
            """)
    int deleteAllByOrderId(
            @Param("productionOrderId") Long productionOrderId
    );
}
