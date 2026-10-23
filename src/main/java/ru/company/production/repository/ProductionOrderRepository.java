package ru.company.production.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.company.production.entity.OrderPriority;
import ru.company.production.entity.ProductionItemStatus;
import ru.company.production.entity.ProductionOrder;
import ru.company.production.entity.ProductionOrderStatus;
import ru.company.production.entity.ZnpKind;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProductionOrderRepository
        extends JpaRepository<ProductionOrder, Long> {

    /**
     * Список заказов выбранного набора видов: один заказ = одна строка.
     *
     * Набор передаётся списком, а не жёстко зашит в запрос: страницы
     * «Заказы на производство» и «Групповые ЗНП» читают одну и ту же
     * проекцию, различаясь только множеством видов (см.
     * ZnpKind.productionOrderKinds() и ZnpKind.groupKinds()).
     *
     * Число выпускаемых изделий и число собранных считаются скалярными
     * подзапросами — join на позиции размножал бы строки заказа.
     *
     * Изделие и количество группового заказа выводятся через сам
     * классификатор (code / product_name): у групповых видов состав
     * задан именно ими, а для комплектования эти поля пусты и строке
     * списка не нужны.
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
                ord.znpKind as znpKind,
                classifier.code as classifierCode,
                classifier.name as classifierName,
                ord.quantity as quantity,
                operationName.name as operationName,
                techProcess.code as techProcessCode,
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
                (
                    select count(child.id)
                    from ProductionOrder child
                    where child.parentProductionOrder = ord
                      and child.active = true
                ) as childCount,
                (
                    select count(znpItem.id)
                    from ProductionOrderItem znpItem
                    left join znpItem.serial znpSerial
                    where znpItem.productionOrder = ord
                      and (
                              znpSerial.id is null
                           or znpSerial.znpNumber is null
                           or trim(znpSerial.znpNumber) = ''
                          )
                ) as missingZnpCount,
                ord.status as status
            from ProductionOrder ord
            left join ord.customerOrder customerOrder
            left join ord.parentProductionOrder parent
            left join ord.productClassifier classifier
            left join ord.productionOperation operation
            left join operation.operationName operationName
            left join ord.techProcess techProcess
            where ord.active = true
              and ord.znpKind in :kinds
            order by ord.orderDate desc, ord.number desc
            """)
    List<ProductionOrderListItemView> findListItemsByKinds(
            @Param("doneStatus") ProductionItemStatus doneStatus,
            @Param("kinds") List<ZnpKind> kinds
    );

    /**
     * Заголовок заказа со связями для страницы просмотра.
     *
     * Родительский заказ дочитывается вместе с остальными связями:
     * карточка и форма правки показывают уровень заказа, а при
     * open-in-view: false ленивая связь вне сессии дала бы
     * LazyInitializationException — в том числе на getId() в
     * контроллере.
     *
     * Приоритет (V41) дочитывается здесь же: карточка группового заказа
     * показывает его значком, и без fetch шаблон споткнулся бы о ту же
     * ленивую связь.
     */
    @Query("""
            select distinct ord
            from ProductionOrder ord
            left join fetch ord.customerOrder customerOrder
            left join fetch customerOrder.customer
            left join fetch ord.parentProductionOrder
            left join fetch ord.productClassifier classifier
            left join fetch classifier.sensorCatalog
            left join fetch ord.productionOperation operation
            left join fetch operation.operationName
            left join fetch operation.operationType
            left join fetch operation.organizationUnit
            left join fetch ord.techProcess
            left join fetch ord.priority
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

    /*
     * Глобальный поиск по номеру заказа на производство (ЗНП).
     *
     * Выборка отдаёт заказы ЛЮБОГО уровня и вида: автономер единый
     * («ЗНП-ГГГГ-NNN») и для выпуска, и для комплектующего, поэтому
     * различить их по номеру нельзя — уровень строке выдачи даёт
     * getLevelDisplayName() самой сущности.
     *
     * Готовый шаблон LIKE, как и в CustomerOrderRepository#search,
     * передаёт TechnologySearchService. Комментарии внутри @Query
     * недопустимы.
     *
     * Родительский заказ дочитывается fetch-join'ом обязательно:
     * getLevelDisplayName() спрашивает isRoot(), а тот читает
     * parentProductionOrder; при open-in-view: false ленивая связь
     * вне сессии дала бы LazyInitializationException — ровно так же,
     * как это учтено в findDetailedById.
     */
    @Query("""
            select distinct ord
            from ProductionOrder ord
            left join fetch ord.parentProductionOrder
            left join fetch ord.productClassifier
            where ord.active = true
              and lower(ord.number) like lower(:pattern)
            order by ord.orderDate desc, ord.number desc
            """)
    List<ProductionOrder> search(
            @Param("pattern") String pattern,
            Pageable pageable
    );

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
     *
     * Вид в условии обязателен с V38: групповой и ремонтный ЗНП
     * заводятся без родителя и выпуском не являются, поэтому второй
     * групповой заказ того же покупателя повторным выпуском считаться
     * не должен.
     */
    @Query("""
            select count(ord) > 0
            from ProductionOrder ord
            where ord.customerOrder.id = :customerOrderId
              and ord.parentProductionOrder is null
              and ord.znpKind
                    = ru.company.production.entity.ZnpKind.KITTING
              and ord.active = true
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
     * Условие по виду нужно с V38: групповые и ремонтные ЗНП тоже не
     * имеют родителя, но выпуском не являются — предлагать их как
     * «заказ верхнего уровня» нельзя, внутрь них комплектующий не
     * входит.
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
              and ord.znpKind
                    = ru.company.production.entity.ZnpKind.KITTING
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
     * Счётчик активных заказов заданного набора видов — счётчик пункта
     * меню и карточка «Всего».
     *
     * Считать по всем заказам (countByActiveTrue) нельзя: после
     * разделения страниц он показал бы в меню «Заказы на производство»
     * и групповые заказы, которых там уже нет.
     */
    @Query("""
            select count(ord)
            from ProductionOrder ord
            where ord.active = true
              and ord.znpKind in :kinds
            """)
    long countActiveByKinds(
            @Param("kinds") List<ZnpKind> kinds
    );

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
     * Тот же счётчик по статусу, но в границах вида — карточки сводки
     * страницы «Групповые ЗНП» и страницы комплектования.
     */
    @Query("""
            select count(ord)
            from ProductionOrder ord
            where ord.active = true
              and ord.status = :status
              and ord.znpKind in :kinds
            """)
    long countActiveByStatusAndKinds(
            @Param("status") ProductionOrderStatus status,
            @Param("kinds") List<ZnpKind> kinds
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

    /*
     * Комплектование ремонтных ячеек/подсборок из изолятора брака
     * (страница бюро входного контроля).
     *
     * Отбор: ремонтные виды ЗНП (ZnpKind.repairKinds()), статус
     * «В работе», выбранный техпроцесс, и в этом техпроцессе есть
     * комплектовочная операция подразделения «Бюро входного контроля»
     * (коды справочников — V15 и V30; передаются параметрами).
     * EXISTS, а не join: маршрут с несколькими комплектовочными
     * операциями БВК не должен размножать строки списка.
     *
     * Справочники дочитаны fetch-join'ом: open-in-view выключен, и
     * изделие, техпроцесс, приоритет и автор в шаблоне читались бы
     * через ленивую связь вне сессии.
     */
    @Query("""
            select distinct ord
            from ProductionOrder ord
            left join fetch ord.productClassifier
            left join fetch ord.techProcess
            left join fetch ord.priority
            left join fetch ord.createdBy
            where ord.active = true
              and ord.status = :status
              and ord.znpKind in :kinds
              and ord.techProcess is not null
              and exists (
                  select 1
                  from TechProcessOperation tpo
                  where tpo.techProcess = ord.techProcess
                    and tpo.operation.operationType.code = :kittingCode
                    and tpo.operation.organizationUnit.code = :bvkUnitCode
              )
            order by ord.orderDate desc, ord.number desc
            """)
    List<ProductionOrder> findRepairKittingOrders(
            @Param("status") ProductionOrderStatus status,
            @Param("kinds") List<ZnpKind> kinds,
            @Param("kittingCode") String kittingCode,
            @Param("bvkUnitCode") String bvkUnitCode
    );

    /**
     * Тот же отбор количеством: счётчик кнопки «Обзор бюро» и пункта
     * меню. Отдельный запрос, а не size() списка — страница
     * существует без загрузки заказа целиком.
     */
    @Query("""
            select count(ord)
            from ProductionOrder ord
            where ord.active = true
              and ord.status = :status
              and ord.znpKind in :kinds
              and ord.techProcess is not null
              and exists (
                  select 1
                  from TechProcessOperation tpo
                  where tpo.techProcess = ord.techProcess
                    and tpo.operation.operationType.code = :kittingCode
                    and tpo.operation.organizationUnit.code = :bvkUnitCode
              )
            """)
    long countRepairKittingOrders(
            @Param("status") ProductionOrderStatus status,
            @Param("kinds") List<ZnpKind> kinds,
            @Param("kittingCode") String kittingCode,
            @Param("bvkUnitCode") String bvkUnitCode
    );

    /*
     * ============================================================
     * Комплектование подсборок в ЗНП (склад полуфабрикатов)
     * ============================================================
     *
     * Раздел собирает ЗНП, у которых подсборки принимает склад
     * полуфабрикатов. Операция такой приёмки ищется в ДВУХ местах,
     * потому что заказывает её по-разному:
     *
     *   * заказ-комплектующий, вложенный в выпуск, ведёт операцию на
     *       самом заказе (production_operation_id) — маршрут ему не
     *       назначают (см. ZnpKind#requiresTechProcess);
     *   * групповой или ремонтный ЗНП самостоятелен и ведётся по
     *       техпроцессу, поэтому его приёмка живёт внутри маршрута.
     *
     * Подходящей считает операцию сервис: тип из перечня (приёмка на
     * склад либо комплектование подсборок) и подразделение «Склад
     * полуфабрикатов». Оба признака параметрами, а не вшиты: коды
     * справочников заведены миграциями V2, V54 и V55.
     *
     * В список берут заказы в работе: закрытый или отменённый заказ
     * складу не интересен, а «новый» ещё не поднят в работу.
     */

    /**
     * Оснований попасть в раздел два, и оба значат одно: приёмку
     * подсборок проводит склад полуфабрикатов.
     *
     *   * операция самого заказа относится к складу и имеет подходящий
     *       тип — так укомплектовывают ЗНП, вложенный в выпуск;
     *   * такая операция есть в техпроцессе заказа — так укомплектовывают
     *       групповой заказ, который ведут по маршруту (V55), в том
     *       числе заказ «на изготовление ячеек из подсборок» (V72):
     *       у него приёмка на склад и есть перевод подсборки в ячейку.
     *
     * Заказа без такой операции в списке нет намеренно: провести его
     * не по чему, и показать заказ без возможности проведения значило бы
     * обещать то, что экран не выполняет.
     *
     * Справочники дочитаны fetch-join'ом: open-in-view выключен, и
     * номер родителя, вид заказа, наименование операции и автор в
     * шаблоне читались бы через ленивую связь вне сессии. Техпроцесс
     * дочитан затем же: когда операции заказа нет, сервис смотрит
     * наименование приёмки в маршруте.
     */
    @Query("""
            select distinct ord
            from ProductionOrder ord
            left join fetch ord.parentProductionOrder parent
            left join fetch ord.productClassifier
            left join fetch ord.createdBy
            left join fetch ord.priority
            left join fetch ord.techProcess
            left join fetch ord.productionOperation operation
            left join fetch operation.operationName
            left join fetch operation.operationType
            left join fetch operation.organizationUnit
            where ord.active = true
              and ord.status = :status
              and (
                    (
                          ord.kittingType =
                              ru.company.production.entity.KittingType.SUBASSEMBLY
                    )
                 or (
                          ord.productionOperation is not null
                      and operation.operationType.code in :typeCodes
                      and operation.organizationUnit.code = :unitCode
                    )
                 or exists (
                        select 1
                        from TechProcessOperation node
                        join node.operation routeOperation
                        join routeOperation.operationType routeType
                        join routeOperation.organizationUnit routeUnit
                        where node.techProcess = ord.techProcess
                          and routeType.code in :typeCodes
                          and routeUnit.code = :unitCode
                    )
              )
            order by ord.orderDate desc, ord.number desc
            """)
    List<ProductionOrder> findSubassemblyKittingOrders(
            @Param("status") ProductionOrderStatus status,
            @Param("typeCodes") List<String> typeCodes,
            @Param("unitCode") String unitCode
    );

    /*
     * Отдельного count-запроса для счётчика раздела здесь нет и быть
     * не должно: счётчик «Комплектование подсборок» считает заказы с
     * неполным составом, а это зависит от внесённого состава и
     * складского остатка — одними полями заказа такое не выразить.
     * Правило живёт в SubassemblyKittingZnpRow#incomplete(), и счётчик
     * считает тот же список, что печатает экран, иначе цифра меню и
     * строки «Взято X из Y» разошлись бы.
     */
}
