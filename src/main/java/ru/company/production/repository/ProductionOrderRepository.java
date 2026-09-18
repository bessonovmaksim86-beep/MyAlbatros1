package ru.company.production.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.company.production.entity.ProductionItemStatus;
import ru.company.production.entity.ProductionOrder;
import ru.company.production.entity.ProductionOrderStatus;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProductionOrderRepository
        extends JpaRepository<ProductionOrder, Long> {

    /**
     * Список заказов на производство: один заказ = одна строка.
     *
     * Число выпускаемых изделий и число собранных считаются скалярными
     * подзапросами — join на позиции размножал бы строки заказа.
     *
     * Важно: внутри @Query комментарии недопустимы, их не принимает
     * парсер JPQL Spring Data.
     */
    @Query("""
            select
                ord.id as id,
                ord.number as number,
                ord.orderDate as orderDate,
                ord.dueDate as dueDate,
                customerOrder.number as customerOrderNumber,
                parent.number as parentOrderNumber,
                operationName.name as operationName,
                (
                    select count(item.id)
                    from ProductionOrderItem item
                    where item.productionOrder = ord
                ) as itemCount,
                (
                    select count(item.id)
                    from ProductionOrderItem item
                    where item.productionOrder = ord
                      and item.status = :doneStatus
                ) as doneItemCount,
                ord.status as status
            from ProductionOrder ord
            left join ord.customerOrder customerOrder
            left join ord.parentProductionOrder parent
            left join ord.productionOperation operation
            left join operation.operationName operationName
            where ord.active = true
            order by ord.orderDate desc, ord.number desc
            """)
    List<ProductionOrderListItemView> findAllListItems(
            @Param("doneStatus") ProductionItemStatus doneStatus
    );

    /**
     * Заголовок заказа со связями для страницы просмотра.
     *
     * Родительский заказ дочитывается вместе с остальными связями:
     * карточка и форма правки показывают уровень заказа, а при
     * open-in-view: false ленивая связь вне сессии дала бы
     * LazyInitializationException — в том числе на getId() в
     * контроллере.
     */
    @Query("""
            select distinct ord
            from ProductionOrder ord
            left join fetch ord.customerOrder customerOrder
            left join fetch customerOrder.customer
            left join fetch ord.parentProductionOrder
            left join fetch ord.productionOperation operation
            left join fetch operation.operationName
            left join fetch operation.operationType
            left join fetch ord.createdBy
            where ord.id = :id
            """)
    Optional<ProductionOrder> findDetailedById(@Param("id") Long id);

    /**
     * Заказ вместе с составом выпускаемых изделий и их серийниками.
     */
    @Query("""
            select distinct ord
            from ProductionOrder ord
            left join fetch ord.items item
            left join fetch item.productClassifier classifier
            left join fetch classifier.sensorCatalog
            left join fetch item.serial
            where ord.id = :id
            order by item.position
            """)
    Optional<ProductionOrder> findFullById(@Param("id") Long id);

    Optional<ProductionOrder> findByNumber(String number);

    boolean existsByNumber(String number);

    boolean existsByNumberAndIdNot(String number, Long id);

    /**
     * Заказ покупателя уже обслуживается ЗАКАЗОМ НА ПРОИЗВОДСТВО
     * верхнего уровня (ЗП).
     *
     * Ограничение «один заказ покупателя — один выпуск» с миграции V21
     * действует только для корневых заказов: внутри выпуска допускается
     * несколько заказов-комплектующих (ЗНП) того же покупателя, и они
     * не должны считаться повторным выпуском. Условие
     * parent_production_order_id is null здесь обязательна — иначе
     * создание второго ЗНП получало бы отказ, хотя база его разрешает.
     *
     * На уровне базы ограничение держит уникальный ключ
     * uk_production_order_customer_root (служебная колонка
     * root_customer_order_id); запрос даёт понятное сообщение до
     * обращения к базе.
     */
    @Query("""
            select count(ord) > 0
            from ProductionOrder ord
            where ord.customerOrder.id = :customerOrderId
              and ord.parentProductionOrder is null
            """)
    boolean existsRootByCustomerOrderId(
            @Param("customerOrderId") Long customerOrderId
    );

    /**
     * Дочерние заказы (ЗНП) родительского выпуска.
     *
     * Нужны для закрытия по дереву: при закрытии ЗП все входящие в
     * него заказы закрываются, поэтому список детей должен быть
     * под рукой до обхода.
     */
    @Query("""
            select ord
            from ProductionOrder ord
            where ord.parentProductionOrder.id = :parentId
              and ord.active = true
            order by ord.number
            """)
    List<ProductionOrder> findActiveChildren(
            @Param("parentId") Long parentId
    );

    /**
     * Дочерние заказы со справочниками — для блока карточки.
     *
     * Отдельный запрос, а не fetch-join в findActiveChildren: тот
     * вызывается при закрытии по дереву, где нужны только идентификатор
     * и статус, и лишние join'ы там означали бы пустые обращения к базе.
     * Здесь же карточке нужно имя операции, а связи lazy и сессия
     * закрыта (open-in-view: false).
     */
    @Query("""
            select ord
            from ProductionOrder ord
            left join fetch ord.productionOperation operation
            left join fetch operation.operationName
            where ord.parentProductionOrder.id = :parentId
              and ord.active = true
            order by ord.number
            """)
    List<ProductionOrder> findActiveChildrenDetailed(
            @Param("parentId") Long parentId
    );

    /**
     * Есть ли у заказа дочерние — проверка перед удалением:
     * выпуск с вложенными заказами-комплектующими удалять нельзя,
     * иначе ЗНП повиснет с ссылкой на неактивный родителя.
     */
    @Query("""
            select count(ord) > 0
            from ProductionOrder ord
            where ord.parentProductionOrder.id = :parentId
              and ord.active = true
            """)
    boolean existsActiveChildren(@Param("parentId") Long parentId);

    /**
     * Действующие заказы верхнего уровня (ЗП) — выбор родителя для
     * заказа-комплектующего.
     *
     * Вложенные заказы в список не попадают сознательно: родителем
     * может быть только выпуск, и форма ЗНП не должна предлагать
     * заведомо недопустимый вариант.
     *
     * Заказчик дочитывается fetch-join'ом: шаблону нужен номер заказа
     * покупателя через @Transient-геттер, а при open-in-view: false
     * ленивая связь вне сессии дала бы LazyInitializationException.
     */
    @Query("""
            select distinct ord
            from ProductionOrder ord
            left join fetch ord.customerOrder
            where ord.active = true
              and ord.parentProductionOrder is null
            order by ord.orderDate desc, ord.number desc
            """)
    List<ProductionOrder> findActiveRoots();

    @Query("""
            select distinct ord
            from ProductionOrder ord
            left join fetch ord.customerOrder customerOrder
            where ord.active = true
              and ord.customerOrder.id = :customerOrderId
            """)
    Optional<ProductionOrder> findActiveByCustomerOrderId(
            @Param("customerOrderId") Long customerOrderId
    );

    long countByActiveTrue();

    /**
     * Счётчик активных заказов в заданном статусе — карточки сводки.
     */
    @Query("""
            select count(ord)
            from ProductionOrder ord
            where ord.active = true
              and ord.status = :status
            """)
    long countActiveByStatus(
            @Param("status") ProductionOrderStatus status
    );

    /**
     * Номера заказов, созданные вручную (вне шаблона автогенерации),
     * чтобы автоподбор номера не предлагал занятый.
     */
    @Query("""
            select ord.number
            from ProductionOrder ord
            """)
    List<String> findAllNumbers();
}
