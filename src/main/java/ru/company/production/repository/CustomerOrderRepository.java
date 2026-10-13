package ru.company.production.repository;

import org.springframework.data.domain.Pageable;
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

    /*
     * Глобальный поиск по номеру заказа покупателя (ЗП).
     *
     * Ищем только по номеру: у заказа нет наименования, а заказчик и
     * статус — уточнение строки выдачи, а не признак поиска (заказ
     * помнят именно его номером).
     *
     * Готовый шаблон LIKE передаёт TechnologySearchService, а не
     * подстраивается здесь: разделители номера («ЗП-2026-005») он
     * заменяет на «%» и сам решает, где шаблон обязан оборваться.
     * Завершающая «%» не дописывается сознательно — иначе запрос
     * «ЗП57» выдал бы и «ЗП-2026-571». Регистр, как и в прочих
     * поисковых выборках модуля, не учитывается; внутри @Query
     * комментарии недопустимы.
     *
     * Заказчик дочитывается fetch-join'ом: строка выдачи печатает его
     * наименование, а open-in-view выключен — ленивая связь вне сессии
     * дала бы LazyInitializationException.
     */
    @Query("""
            select distinct ord
            from CustomerOrder ord
            join fetch ord.customer
            where ord.active = true
              and lower(ord.number) like lower(:pattern)
            order by ord.orderDate desc, ord.number desc
            """)
    List<CustomerOrder> search(
            @Param("pattern") String pattern,
            Pageable pageable
    );

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

    /* ============================================================
     * Проверка зависимостей перед удалением заказа покупателя
     *
     * Все четыре запроса живут здесь, а не в репозиториях чужих
     * модулей: вопрос у них один — «можно ли снять этот заказ с
     * работы», и отвечать на него должен репозиторий того заказа,
     * иначе контроллер обвешался бы пятью репозиториями ради одной
     * кнопки.
     *
     * Каждый запрос — скалярный count(...) > 0: удаляющему нужен
     * признак, а не содержимое чужих таблиц, и тянуть строки выпуска
     * или графика ради ответа «да/нет» не за чем.
     * ============================================================
     */

    /**
     * Есть ли позиция заказа, по которой отмечено готовое количество.
     *
     * Признак виден прямо в заказе (customer_order_items.done_quantity)
     * и означает, что изделия по позиции уже принимали — удалять такой
     * заказ нельзя, иначе исчезнет основание принятых изделий.
     */
    @Query("""
            select count(item) > 0
            from CustomerOrderItem item
            where item.customerOrder.id = :customerOrderId
              and item.doneQuantity > 0
            """)
    boolean existsItemWithDoneQuantity(
            @Param("customerOrderId") Long customerOrderId
    );

    /**
     * Проведена ли хоть одна строка графика этого заказа.
     *
     * Проведение — отметка исполнителя order_schedule_rows.completed_at
     * (V44). Пока строка не проведена, она остаётся планом, который
     * диспетчер вправе снять вместе с заказом; проведённая строка —
     * уже факт работы цеха и переживает заказ.
     */
    @Query("""
            select count(row) > 0
            from OrderScheduleRow row
            where row.customerOrder.id = :customerOrderId
              and row.completedAt is not null
            """)
    boolean existsCompletedScheduleRow(
            @Param("customerOrderId") Long customerOrderId
    );

    /**
     * Есть ли у заказа покупателя действующий заказ на производство.
     *
     * Проверка предшествует разбору проведённых операций: выпуск —
     * это основание, порождённое заказом, и удалять его при живом
     * выпуске нельзя даже если сам выпуск ещё не взят в работу —
     * иначе выпуск повис бы с ссылкой на снятый с работы заказ
     * (тот же смысл, что у existsActiveChildren перед удалением
     * выпуска).
     *
     * Считаются заказы ЛЮБОГО вида и уровня: заказ-комплектующий тоже
     * ссылается на заказ покупателя (наследует его заказчика), и
     * удаление основания оставило бы сиротой всё дерево выпуска.
     */
    @Query("""
            select count(ord) > 0
            from ProductionOrder ord
            where ord.customerOrder.id = :customerOrderId
              and ord.active = true
            """)
    boolean existsActiveProductionOrder(
            @Param("customerOrderId") Long customerOrderId
    );

    /**
     * Проведена ли цеховая операция по изделиям этого заказа.
     *
     * Факт операции — product_serial_operations.actual_date (V19).
     * Заказ относится к операции двумя путями, и проверяются оба:
     *
     *   * операция выполнена В РАМКАХ заказа на производство, у
     *     которого это основание: берётся op.productionOrder
     *     (production_order_id, V20), а НЕ serial.issuedProductionOrder.
     *     Разница принципиальна и зафиксирована в
     *     ProductSerialOperationRepository#findCurrentByProductionOrderId:
     *     issuedProductionOrder — заказ, ВЫДАВШИЙ серийник (ЗП), тогда
     *     как работу ведут заказом-комплектующим (ЗНП), входящим в
     *     изделие; один ЗНП комплектует датчики разных выпусков, а
     *     один датчик укомплектован несколькими ЗНП, поэтому по заказу
     *     выдачи номера проверка увидела бы чужие операции и потеряла
     *     свои. Достаточно шага op.productionOrder.customerOrder:
     *     заказ-комплектующий наследует основание выпуска, поэтому
     *     ветвь ловит и ЗП, и ЗНП;
     *   * изделие зарезервировано позицией самого заказа покупателя
     *     (product_serials.customer_order_item_id, V45) — такие номера
     *     работают до всякого выпуска.
     *
     * productionOrder и customerOrderItem — nullable-ссылки (операция
     * по резервированию бывает без выпуска), поэтому связи развёрнуты
     * явными left join: неявный join в HQL строится как inner и внутри
     * OR отбросил бы строку, у которой заполнена только одна ветвь, —
     * операция «потерялась» бы для проверки.
     */
    @Query("""
            select count(op) > 0
            from ProductSerialOperation op
            left join op.productionOrder opOrder
            left join opOrder.customerOrder opCustomerOrder
            left join op.serial serial
            left join serial.customerOrderItem item
            where op.actualDate is not null
              and (
                    opCustomerOrder.id = :customerOrderId
                 or item.customerOrder.id = :customerOrderId
              )
            """)
    boolean existsPerformedSerialOperation(
            @Param("customerOrderId") Long customerOrderId
    );
}