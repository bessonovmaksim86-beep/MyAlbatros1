package ru.company.production.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.company.production.entity.ProductionOrder;
import ru.company.production.entity.SerialProductionOrder;
import ru.company.production.entity.ZnpKind;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Связь «многие ко многим» изделия и заказа на производство.
 *
 * Оба направления нужны одинаково часто, поэтому индексы и запросы
 * есть в обе стороны:
 *
 *   «чем укомплектован датчик»  — карточка изделия;
 *   «какие датчики в группе ЗНП» — карточка заказ-комплектующего.
 */
@Repository
public interface SerialProductionOrderRepository
        extends JpaRepository<SerialProductionOrder, Long> {

    /**
     * Заказы, вошедшие в изделие, со связями для вывода строкой.
     *
     * Fetch join обязателен: getDisplayLabel() читает номер заказа и
     * наименование операции, а open-in-view выключен — без fetch шаблон
     * упал бы на ленивой связи вне транзакции.
     */
    @Query("""
            select link
            from SerialProductionOrder link
            left join fetch link.productionOrder ord
            left join fetch ord.parentProductionOrder parent
            left join fetch link.operation operation
            left join fetch operation.operationName operationName
            where link.serial.id = :serialId
            order by ord.number
            """)
    List<SerialProductionOrder> findDetailsBySerialId(
            @Param("serialId") Long serialId
    );

    /**
     * Изделия, в которые вошёл заказ (состав группы комплектования).
     */
    @Query("""
            select link
            from SerialProductionOrder link
            left join fetch link.serial serial
            left join fetch serial.productClassifier classifier
            left join fetch classifier.sensorCatalog
            where link.productionOrder.id = :productionOrderId
            order by serial.productType, serial.serial
            """)
    List<SerialProductionOrder> findByProductionOrderId(
            @Param("productionOrderId") Long productionOrderId
    );

    /**
     * Состав заказа с связями для вывода строкой.
     *
     * Отличие от findByProductionOrderId: выводится и комплектовочная
     * операция, поэтому она тоже выбирается fetch join — open-in-view
     * выключен, и обращение к наименованию операции из шаблона упало бы
     * на ленивой связи.
     */
    @Query("""
            select link
            from SerialProductionOrder link
            left join fetch link.serial serial
            left join fetch serial.productClassifier classifier
            left join fetch classifier.sensorCatalog
            left join fetch link.operation operation
            left join fetch operation.operationName operationName
            where link.productionOrder.id = :productionOrderId
            order by serial.productType, serial.serial
            """)
    List<SerialProductionOrder> findDetailsByProductionOrderId(
            @Param("productionOrderId") Long productionOrderId
    );

    /**
     * Ячейки, оформленные в набор заказов, — внесённый состав списка
     * комплектования.
     *
     * Нужен складу: проведённая в ЗНП ячейка со свободных полок снята и
     * в складской остаток не возвращается, поэтому «что уже внесено»
     * читается матрицей, а не перечнем склада.
     *
     * Пакетность та же, что у {@link #findSerialsByOrderIds}: список
     * раздела перечисляет десятки заказов, и запрос на строку дал бы
     * N+1 на экране, который кладовщик открывает за смену много раз.
     *
     * Снятые с работы заказы исключены: отменённый заказ обязан вернуть
     * ячейку на склад, иначе он держал бы номер занятым.
     */
    @Query("""
            select link
            from SerialProductionOrder link
            join fetch link.serial serial
            left join fetch serial.productClassifier classifier
            left join fetch classifier.sensorCatalog
            where link.productionOrder.id in :orderIds
              and link.productionOrder.active = true
            order by link.productionOrder.id asc,
                     link.createdAt asc,
                     serial.serial asc
            """)
    List<SerialProductionOrder> findAttachedByOrderIds(
            @Param("orderIds") Collection<Long> orderIds
    );

    /**
     * Внесённые ячейки набора заказов — номера для подписи работ.
     *
     * Нужен «Мои операции» в групповом режиме: там работу подписывает
     * номер ячейки, внесённый складом, а не серийный номер выпуска,
     * которого у группового ЗНП не бывает вовсе.
     *
     * Скалярная проекция, а не сущности связи: список исполнителя
     * перечисляет много заказов, и fetch join ради двух колонок тянул бы
     * за собой классификатор и справочник целиком.
     *
     * Порядок — заказ, момент внесения, номер: он же задаёт
     * сопоставление ячейки экземпляру плана (i-я по внесению ячейка
     * принадлежит экземпляру с номером i), поэтому менять его нельзя без
     * правки того же правила в сервисах.
     *
     * Снятые с работы заказы исключены: отменённый заказ обязан вернуть
     * ячейку на склад, иначе он держал бы номер занятым и подписывал бы
     * работу чужим номером.
     */
    @Query("""
            select
                ord.id as orderId,
                serialRow.id as serialId,
                classifier.code as classifierCode,
                serialRow.serial as serial,
                classifier.name as classifierName,
                sensor.name as sensorName
            from SerialProductionOrder link
            join link.productionOrder ord
            join link.serial serialRow
            join serialRow.productClassifier classifier
            left join classifier.sensorCatalog sensor
            where ord.id in :orderIds
              and ord.active = true
            order by ord.id asc, link.createdAt asc, serialRow.serial asc
            """)
    List<AttachedCellView> findAttachedCellsByOrderIds(
            @Param("orderIds") Collection<Long> orderIds
    );

    /**
     * Датчики набора заказов — колонка «Серийник(и)» списка заказов.
     *
     * Это второй из двух источников колонки. У выпуска (ЗП) номера
     * читаются из состава выпуска, а у заказ-комплектующего (ЗНП)
     * состава выпуска нет: его серийники — датчики, в которые заказ
     * вошёл, то есть строки этой связи.
     *
     * Пакетность та же, что у ProductionOrderItemRepository
     * .findSerialsByOrderIds: список показывает десятки заказов, и
     * запрос на строку дал бы N+1. Один вызов закрывает все строки
     * страницы; интерцептор списка объединяет результат с выпусками в
     * один перечень по идентификатору заказа.
     *
     * Номер и тип берутся из серийника (product_serials): там есть и
     * код классификатора, и серия, и тип изделия — последний нужен
     * фильтру «датчики / системы». Порядок — по заказу, затем типу и
     * серии, чтобы перечень в клетке был предсказуем и не менялся от
     * перезагрузки страницы.
     *
     * Отличие от findByProductionOrderId: тот отдаёт сущности связи с
     * fetch join'ами для карточки одного заказа, здесь строки нужны
     * только как пары «заказ → номер», поэтому скалярная проекция без
     * разыменования связей.
     */
    @Query("""
            select
                link.productionOrder.id as orderId,
                classifier.code as classifierCode,
                serialRow.serial as serial,
                serialRow.productType as productType
            from SerialProductionOrder link
            join link.serial serialRow
            join serialRow.productClassifier classifier
            where link.productionOrder.id in :orderIds
            order by link.productionOrder.id,
                     serialRow.productType,
                     serialRow.serial
            """)
    List<ProductionOrderSerialRowView> findSerialsByOrderIds(
            @Param("orderIds") Collection<Long> orderIds
    );

    /**
     * Заказ уже входит в изделие.
     *
     * Уникальный ключ uk_spo_serial_order отклонит повтор на уровне
     * базы, но сообщение о причине нужно пользователю, а не
     * ConstraintViolationException.
     */
    boolean existsBySerial_IdAndProductionOrder_Id(
            Long serialId,
            Long productionOrderId
    );

    /**
     * Заказы-комплектующие в работе, вошедшие в набор изделий, —
     * ключ пары «серийник + операция».
     *
     * Раздел комплектования обязан показать только те операции сборки,
     * под которые диспетчер поднял заказ комплектующих в статусе «в
     * работе». Связь изделия с заказом (SerialProductionOrder, V20)
     * хранит и операцию, в которую заказ вошёл, и сам заказ, поэтому
     * один пакетный запрос отдаёт тройки, по которым сопоставляется
     * строка графика (ключ — серийник + операция, тот же, что в
     * карточке выпуска).
     *
     * Пакетная форма обязательна: раздел перечисляет много изделий, и
     * выборка на каждую строку обернулась бы N+1. Неактивные заказы
     * исключены, поэтому снятый с работы заказ не вернётся в список.
     */
    @Query("""
            select
                link.serial.id as serialId,
                operation.id as operationId,
                ord.number as znpNumber
            from SerialProductionOrder link
            join link.operation operation
            join link.productionOrder ord
            where link.serial.id in :serialIds
              and ord.active = true
              and ord.status =
                  ru.company.production.entity.ProductionOrderStatus.IN_WORK
            """)
    List<KittingZnpLinkView> findActiveZnpLinksBySerialIds(
            @Param("serialIds") Collection<Long> serialIds
    );

    /**
     * Действующие заказы-комплектующие, вошедшие в набор изделий, —
     * ключ пары «серийник + операция», независимо от статуса заказа.
     *
     * Отличие от {@link #findActiveZnpLinksBySerialIds}: здесь нет
     * ограничения статусом «в работе». Разделы комплектования обязаны
     * показать операцию, как только под неё заведён заказ: диспетчер
     * вносит номера ЗНП в карточке выпуска и ждёт, что склад увидит
     * работу сразу, а не после отдельного поднятия заказа в работу.
     * Граница списка строится тем же признаком «есть привязка с
     * операцией», что и в карточке выпуска, поэтому оба склада
     * показывают ровно те изделия, которым диспетчер завёл заказ, и не
     * показывают те, у которых заказа нет.
     *
     * Исключён только снятый с работы заказ (active = false): он не
     * возвращает операцию в список. Отменённый заказ деактивируется,
     * поэтому отдельная проверка статуса не нужна.
     */
    @Query("""
            select
                link.serial.id as serialId,
                operation.id as operationId,
                ord.number as znpNumber
            from SerialProductionOrder link
            join link.operation operation
            join link.productionOrder ord
            where link.serial.id in :serialIds
              and ord.active = true
            """)
    List<KittingZnpLinkView> findZnpLinksBySerialIds(
            @Param("serialIds") Collection<Long> serialIds
    );

    Optional<SerialProductionOrder> findBySerial_IdAndProductionOrder_Id(
            Long serialId,
            Long productionOrderId
    );

    /**
     * Сколько заказов вошло в изделие — счётчик состава карточки.
     */
    long countBySerial_Id(Long serialId);

    /**
     * Сколько изделий укомплектовано заказом — размер группы.
     */
    long countByProductionOrder_Id(Long productionOrderId);

    /**
     * Изделия, укомплектованные хотя бы одним заказом.
     *
     * Отличие от заказа выдачи номера: выпуск был, а комплектующих
     * нет — такие изделия видно как незавершённые.
     */
    @Query("""
            select count(distinct link.serial.id)
            from SerialProductionOrder link
            """)
    long countDistinctSerials();

    /*
     * ============================================================
     * Приёмка ячеек по заказам-комплектующим (ЗНП)
     * ============================================================
     *
     * Склад принимает ячейки, которые вошли в состав заказ-
     * комплектующего, но ещё не отмечены принятыми. Приёмка проходит
     * по заказу: все непринятые ячейки состава переходят в «принято»
     * разом, а их комплектовочная операция закрывается фактом.
     *
     * Попадают заказы двух родов:
     *
     *   * серийные ЗНП (родитель задан) — заказ внутрь выпуска, его
     *       комплектовочная операция живёт на самом заказе;
     *   * групповые ЗНП (набор {@link ZnpKind#groupKinds()}) —
     *       самостоятельные заказы, которые диспетчер заводит изделием
     *       и количеством с выбором техпроцесса (карточка «Групповой
     *       ЗНП на ячейки»). Их ячейка привязана к заказу операцией
     *       комплектования в начале маршрута, то есть строкой матрицы
     *       {@link SerialProductionOrder}, где и лежит эта операция.
     *
     * Ремонтные виды в отбор не входят намеренно: их состав — ячейки,
     * возвращённые из изолятора брака, а не заказанные на склад, и
     * приёмку этих ячеек проводит комплектование ремонтных ЗНП, а не
     * раздел приёмки.
     *
     * Количество принятых ячеек заграничено заказанным не здесь:
     * потолок держит внесение состава (SubassemblyKittingService), а
     * приёмка берёт ровно те номера, что дошли до склада и ещё не
     * отмечены, — сколько дошло, столько и принимается.
     */

    /**
     * Заказы-комплектующие с непринятыми ячейками: список раздела
     * «Приёмка ячеек».
     *
     * EXISTS, а не join: заказ обязан попасть в выдачу один раз, даже
     * если в его составе несколько непринятых ячеек.
     */
    @Query("""
            select distinct ord
            from ProductionOrder ord
            where ord.active = true
              and (
                    ord.parentProductionOrder is not null
                 or ord.znpKind in :groupKinds
              )
              and exists (
                  select 1
                  from SerialProductionOrder link
                  join link.serial serial
                  where link.productionOrder.id = ord.id
                    and serial.productType =
                        ru.company.production.entity.ProductType.CELL
                    and serial.acceptedAt is null
              )
            order by ord.orderDate desc, ord.number desc
            """)
    List<ProductionOrder> findPendingCellZnp(
            @Param("groupKinds") Collection<ZnpKind> groupKinds
    );

    /**
     * Число заказов, ожидающих приёмки ячеек: счётчик пункта меню.
     */
    @Query("""
            select count(distinct ord.id)
            from ProductionOrder ord
            where ord.active = true
              and (
                    ord.parentProductionOrder is not null
                 or ord.znpKind in :groupKinds
              )
              and exists (
                  select 1
                  from SerialProductionOrder link
                  join link.serial serial
                  where link.productionOrder.id = ord.id
                    and serial.productType =
                        ru.company.production.entity.ProductType.CELL
                    and serial.acceptedAt is null
              )
            """)
    long countPendingCellZnp(
            @Param("groupKinds") Collection<ZnpKind> groupKinds
    );

    /**
     * Комплектовочные операции, которыми изделие уже закрыто
     * действующим заказом.
     *
     * Нужен проверке при взятии заказ-комплектующего в работу: состав
     * ЗНП — операции базового маршрута изделия, в которое он вошёл, и
     * каждая операция считается закрытой, если в это изделие вошёл
     * активный заказ именно этой операцией. Сам проверяемый заказ тоже
     * попадает в набор: его собственная операция закрыта им же, и
     * требовать от него второго ЗНП на ту же операцию незачем.
     */
    @Query("""
            select distinct operation.id
            from SerialProductionOrder link
            join link.operation operation
            join link.productionOrder ord
            where link.serial.id = :serialId
              and ord.active = true
            """)
    Set<Long> findCoveredOperationIdsBySerialId(
            @Param("serialId") Long serialId
    );

    /**
     * Непринятые ячейки состава заказа: что принимает склад.     *
     * Fetch join обязателен: строка списка печатает полный номер
     * (getFullNumber() читает code классификатора), а open-in-view
     * выключен.
     *
     * Форма запроса повторяет рабочий findDetailsByProductionOrderId:
     * цепочка fetch идёт ОТ выбираемого link (join fetch link.serial →
     * serial.productClassifier → classifier.sensorCatalog). Hibernate
     * 6 отвергает fetch ассоциации, владелец которой не попал в select,
     * — обычный `join link.serial` с последующим `fetch
     * serial.productClassifier` давал бы «owner of the fetched
     * association was not present in the select list». inner fetch join
     * сохраняет смысл прежнего inner join: фильтр по типу «ячейка» и
     * непринятости держится в where, distinct не нужен — связи только
     * ManyToOne, строк связь не размножает.
     */
    @Query("""
            select link
            from SerialProductionOrder link
            join fetch link.serial serial
            left join fetch serial.productClassifier classifier
            left join fetch classifier.sensorCatalog
            where link.productionOrder.id = :productionOrderId
              and serial.productType =
                  ru.company.production.entity.ProductType.CELL
              and serial.acceptedAt is null
            order by serial.serial
            """)
    List<SerialProductionOrder> findPendingCellsByOrderId(
            @Param("productionOrderId") Long productionOrderId
    );

    /*
     * ============================================================
     * Жёсткое удаление заказа (раздел «Очистка данных»)
     * ============================================================
     */

    /**
     * Изделия, внесённые в удаляемый заказ, — перечень для предпросмотра.
     *
     * Нужны именно номера, а не количество: администратор обязан
     * увидеть, какие ячейки вернутся на склад, потому что для склада
     * это изменение остатка, а не техническое удаление строк.
     */
    @Query("""
            select serial.id
            from SerialProductionOrder link
            join link.serial serial
            where link.productionOrder.id = :productionOrderId
            order by serial.productType asc, serial.serial asc
            """)
    List<Long> findSerialIdsByProductionOrderId(
            @Param("productionOrderId") Long productionOrderId
    );

    /**
     * Вносит ли заказ хоть что-нибудь в чужие изделия.
     *
     * Проверяется ДО удаления: связь «изделие ⇄ заказ» — это
     * укомплектованность конкретного датчика, и заказ, внесённый в
     * изделия чужого выпуска, одним удалением менял бы состав
     * уже собранного прибора.
     */
    @Query("""
            select count(link) > 0
            from SerialProductionOrder link
            where link.productionOrder.id = :productionOrderId
            """)
    boolean existsAnyByProductionOrderId(
            @Param("productionOrderId") Long productionOrderId
    );

    /** Удаляет состав комплектования заказа. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            delete from SerialProductionOrder link
            where link.productionOrder.id = :productionOrderId
            """)
    int deleteByProductionOrderId(
            @Param("productionOrderId") Long productionOrderId
    );

    /**
     * Удаляет связи удаляемых изделий целиком.
     *
     * Применяется к серийникам, которых раздел убирает из реестра:
     * ключ матрицы по изделию стоит с ON DELETE CASCADE, но удаление
     * идёт пакетным запросом, и без явного шага Hibernate мог бы
     * уронить удаление реестра на внешнем ключе в другой порядок.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            delete from SerialProductionOrder link
            where link.serial.id in :serialIds
            """)
    int deleteBySerialIdIn(
            @Param("serialIds") Collection<Long> serialIds
    );
}
