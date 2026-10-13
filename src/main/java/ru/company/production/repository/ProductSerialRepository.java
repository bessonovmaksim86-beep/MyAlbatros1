package ru.company.production.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.company.production.entity.AppUser;
import ru.company.production.entity.InputControlType;
import ru.company.production.entity.ProductSerial;
import ru.company.production.entity.ProductSerialStatus;
import ru.company.production.entity.ProductType;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface ProductSerialRepository
        extends JpaRepository<ProductSerial, Long> {

    /**
     * Последний выданный порядковый номер в пределах области нумерации.
     *
     * Область задаёт столбец numbering_key: для прибора, ячейки и
     * системы это сам классификатор, для датчика — его род (миграция
     * V27). Следующий серийник — максимум по области плюс единица.
     *
     * null — если по области номеров ещё не выдавали.
     */
    @Query("""
            select max(serial.serial)
            from ProductSerial serial
            where serial.numberingKey = :numberingKey
            """)
    Integer findMaxSerialByNumberingKey(
            @Param("numberingKey") String numberingKey
    );

    /**
     * Серийники, выданные в рамках заказа на производство,
     * в порядке позиций выпуска.
     */
    @Query("""
            select serial
            from ProductSerial serial
            where serial.issuedProductionOrder.id = :productionOrderId
            order by serial.productType asc, serial.serial asc
            """)
    List<ProductSerial> findByProductionOrderId(
            @Param("productionOrderId") Long productionOrderId
    );

    /**
     * Принято ли какое-либо изделие из перечня на склад
     * ({@code accepted_at}).
     *
     * Отметку приёмки ставит склад (BvkAcceptanceService,
     * ZnpAcceptanceService) — это событие склада на самом изделии,
     * независимое от того, каким заказом шла операция. Нужна проверке
     * удаления выпуска (ЗП): выпуск сам приёмку не проводит, но его
     * номера принимает склад, и освободить принятый номер — значит
     * вернуть в резерв изделие, уже побывавшее на складе.
     *
     * Пустой перечень вызывающий не передаёт (проверка обёрнута в
     * сервисе), поэтому {@code in :ids} здесь всегда непустой.
     */
    @Query("""
            select count(serial) > 0
            from ProductSerial serial
            where serial.id in :serialIds
              and serial.acceptedAt is not null
            """)
    boolean existsAcceptedByIdIn(
            @Param("serialIds") Collection<Long> serialIds
    );

    /**
     * Серийники, выпущенные заказом, со связями для вывода строкой.
     *
     * Отличие от findByProductionOrderId: здесь есть fetch join
     * классификатора и справочника датчиков. Нужен он для карточки
     * заказа-комплектующего, где список изделий рисуется в шаблоне, а
     * open-in-view выключен — без fetch обращение к наименованию
     * упало бы на ленивой связи вне транзакции.
     */
    @Query("""
            select serial
            from ProductSerial serial
            left join fetch serial.productClassifier classifier
            left join fetch classifier.sensorCatalog
            where serial.issuedProductionOrder.id = :productionOrderId
            order by serial.productType asc, serial.serial asc
            """)
    List<ProductSerial> findDetailsByProductionOrderId(
            @Param("productionOrderId") Long productionOrderId
    );

    /**
     * Ячейки оприходованной партии вместе с классификатором: карточка
     * партии выводит полные номера и наименования, а open-in-view
     * выключен.
     */
    @Query("""
            select serial
            from ProductSerial serial
            left join fetch serial.productClassifier classifier
            left join fetch classifier.sensorCatalog
            where serial.receivingBatch.id = :batchId
            order by serial.serial asc
            """)
    List<ProductSerial> findDetailsByReceivingBatchId(
            @Param("batchId") Long batchId
    );

    boolean existsByNumberingKeyAndSerial(
            String numberingKey,
            int serial
    );

    /**
     * Свободные серийники позиции заказа покупателя: заведённые
     * заранее, но ещё не подхваченные выпуском
     * (issued_production_order_id IS null).
     *
     * Нужны выпуску, чтобы переиспользовать заранее выданные номера
     * вместо новых: ряд датчика остаётся сквозным, а диспетчер видит
     * те же серийники и в заказе, и в заказе на производство.
     * Классификатор подтягивается fetch-join'ом: выпуск читает полный
     * номер (getFullNumber()), а open-in-view выключен.
     */
    @Query("""
            select serial
            from ProductSerial serial
            left join fetch serial.productClassifier classifier
            left join fetch classifier.sensorCatalog
            where serial.customerOrderItem.id = :customerOrderItemId
              and serial.issuedProductionOrder is null
            order by serial.serial asc
            """)
    List<ProductSerial> findFreeDetailsByCustomerOrderItemId(
            @Param("customerOrderItemId") Long customerOrderItemId
    );

    /**
     * Серийники позиции, которые ЕЩЁ можно присвоить выпуску.
     *
     * От {@link #findFreeDetailsByCustomerOrderItemId} отличается вторым
     * условием: номер не должен входить ни в один заказ-комплектующий
     * (serial_production_orders, миграция V20).
     *
     * Свободный по выпуску номер уже может быть укомплектован ЗНП: выпуск
     * и комплектование — разные связи (issued_production_order — «кем
     * выпущен», serial_production_orders — «чем укомплектован»). Без второй
     * проверки форма выпуска предлагала бы диспетчеру датчик, уже собранный
     * в ячейку, — выпуск задним числом переменил бы хозяина номера, а
     * операции ЗНП повисли бы на чужом выпуске.
     *
     * Подчинённый запрос, а не join: строка обязана остаться одной на
     * номер, иначе связь «многие к одному» продублировала бы серийник
     * столько раз, сколько ЗНП в него входит.
     */
    @Query("""
            select serial
            from ProductSerial serial
            left join fetch serial.productClassifier classifier
            left join fetch classifier.sensorCatalog
            where serial.customerOrderItem.id = :customerOrderItemId
              and serial.issuedProductionOrder is null
              and not exists (
                      select link.id
                      from SerialProductionOrder link
                      where link.serial.id = serial.id
                  )
            order by serial.serial asc
            """)
    List<ProductSerial> findAssignableDetailsByCustomerOrderItemId(
            @Param("customerOrderItemId") Long customerOrderItemId
    );

    /**
     * Присваиваемые серийники всего заказа покупателя — по позициям.
     *
     * Тот же фильтр, что у
     * {@link #findAssignableDetailsByCustomerOrderItemId}, но срез по
     * заказу: перечню состава выпуска нужны все позиции сразу, иначе на
     * каждую из них шло бы отдельное обращение к базе.
     *
     * Позиция подтянута fetch-join'ом: предпросмотр группирует строки по
     * номеру позиции, а open-in-view выключен.
     */
    @Query("""
            select serial
            from ProductSerial serial
            left join fetch serial.productClassifier classifier
            left join fetch classifier.sensorCatalog
            left join fetch serial.customerOrderItem customerItem
            where customerItem.customerOrder.id = :customerOrderId
              and serial.issuedProductionOrder is null
              and not exists (
                      select link.id
                      from SerialProductionOrder link
                      where link.serial.id = serial.id
                  )
            order by customerItem.position asc, serial.serial asc
            """)
    List<ProductSerial> findAssignableDetailsByCustomerOrderId(
            @Param("customerOrderId") Long customerOrderId
    );

    /**
     * Присваиваемые выпуску номера заказа: только датчики и системы.
     *
     * Выпуск выпускает именно эти типы; приборы и ячейки — предмет
     * других заказов, и в перечне выпуска они только мешали бы
     * диспетчеру отметить чужой номер.
     *
     * Типы приходят параметром, а не вшиты в запрос: перечень живёт
     * в форме, и список типов может измениться вместе с формой.
     */
    @Query("""
            select serial
            from ProductSerial serial
            left join fetch serial.productClassifier classifier
            left join fetch classifier.sensorCatalog
            left join fetch serial.customerOrderItem customerItem
            where customerItem.customerOrder.id = :customerOrderId
              and serial.productType in :productTypes
              and serial.issuedProductionOrder is null
              and not exists (
                      select link.id
                      from SerialProductionOrder link
                      where link.serial.id = serial.id
                  )
            order by customerItem.position asc, serial.serial asc
            """)
    List<ProductSerial> findAssignableReleaseDetailsByCustomerOrderId(
            @Param("customerOrderId") Long customerOrderId,
            @Param("productTypes") Collection<ProductType> productTypes
    );

    /**
     * Свободные серийники всего заказа покупателя — по позициям.
     *
     * Нужен предпросмотру состава выпуска: форма создания заказа
     * показывает диспетчеру, какие номера уже зарезервированы заказом и
     * будут переиспользованы, а какие придётся выдать заново. Номера
     * отдаются детальным запросом (open-in-view выключен, строка списка
     * печатает полный номер).
     */
    @Query("""
            select serial
            from ProductSerial serial
            left join fetch serial.productClassifier classifier
            left join fetch classifier.sensorCatalog
            where serial.customerOrderItem.customerOrder.id = :customerOrderId
              and serial.issuedProductionOrder is null
            order by serial.productClassifier.id asc, serial.serial asc
            """)
    List<ProductSerial> findFreeDetailsByCustomerOrderId(
            @Param("customerOrderId") Long customerOrderId
    );

    /**
     * Все серийники заказа покупателя — сгруппированы по позициям.
     *
     * Нужен карточке заказа: диспетчер видит, какие номера относятся
     * к каждой позиции состава. Возвращаются и резервы (номер выдан
     * заказом, выпуск ещё не создан), и подхваченные выпуском:
     * claimForRelease сохраняет customer_order_item, поэтому привязка
     * к позиции не теряется и один запрос закрывает обе стадии.
     *
     * Классификатор и позиция подтянуты fetch-join: строка карточки
     * печатает полный номер (getFullNumber() берёт код классификатора),
     * а контроллер группирует по позиции (open-in-view выключен).
     */
    @Query("""
            select serial
            from ProductSerial serial
            left join fetch serial.productClassifier classifier
            left join fetch serial.customerOrderItem customerItem
            where customerItem.customerOrder.id = :customerOrderId
            order by customerItem.position asc, serial.serial asc
            """)
    List<ProductSerial> findDetailsByCustomerOrderId(
            @Param("customerOrderId") Long customerOrderId
    );

    /**
     * Удаляет свободные серийники всех позиций заказа покупателя.
     *
     * Нужен правке заказа: состав пересоздаётся целиком, старые позиции
     * удаляются, а переиспользовать их номера нельзя — резерв привязан
     * к строке, которой больше нет. Поэтому свободные (не подхваченные
     * выпуском) номера снимаются, а под новый состав выдаются новые.
     *
     * Ряд от этого не рвётся: следующий номер считается максимумом по
     * области, а снятые резервы область отдаёт обратно.
     *
     * flushAutomatically обязателен: UPDATE/DELETE исполняются напрямую,
     * и только что сохранённые позиции обязаны попасть в БД до запроса.
     *
     * Пустой issuedProductionOrder ещё не значит «номер свободен»:
     * удаление выпуска (ProductionOrderService#delete) обнуляет эту
     * ссылку, а строку состава выпуска оставляет. Реестр поэтому
     * защищён двумя внешними ключами без ON DELETE CASCADE —
     * production_order_items.serial_id (V17) и
     * serial_movement_events.serial_id (V42), — и номер, попавший под
     * любой из них, остаётся в реестре как изделие. Условие удаления
     * обязано быть точным дополнением к
     * {@link #unlinkReleasedByCustomerOrderId}: иначе оставшаяся ссылка
     * на позицию сорвёт удаление состава тем же нарушением целостности.
     */
    @Modifying(
            flushAutomatically = true,
            clearAutomatically = true
    )
    @Query("""
            delete from ProductSerial serial
            where serial.issuedProductionOrder is null
              and serial.customerOrderItem.customerOrder.id = :customerOrderId
              and not exists (
                      select item.id
                      from ProductionOrderItem item
                      where item.serial.id = serial.id
                  )
              and not exists (
                      select event.id
                      from SerialMovementEvent event
                      where event.serialId = serial.id
                  )
            """)
    int deleteFreeByCustomerOrderId(
            @Param("customerOrderId") Long customerOrderId
    );

    /**
     * Отвязывает от позиций заказа номера, которыми уже пользуется
     * выпуск.
     *
     * Такой номер — уже не резерв диспетчера, а изделие: он стоит в
     * составе выпуска, и удалять его нельзя. Но старую позицию заказа
     * при пересоздании состава удалить нужно, а на неё ссылается
     * внешний ключ product_serials.customer_order_item_id. Связь
     * снимается: дальнейшую прослеживаемость держит строка выпуска
     * (production_order_items.customer_order_item_id), а не резерв.
     *
     * Условие обязано быть точным дополнением к
     * {@link #deleteFreeByCustomerOrderId}: номер либо удаляется вместе
     * с резервом, либо отвязывается и остаётся в реестре. Третьего
     * быть не может, иначе оставшаяся ссылка на позицию сорвёт
     * удаление состава тем же нарушением целостности.
     *
     * Вторая ветвь (есть строка выпуска) нужна из-за удаления выпуска:
     * ProductionOrderService#delete обнуляет issuedProductionOrder, но
     * строку состава оставляет, и по одному только признаку выпуска
     * такой номер выглядел бы свободным резервом. Третья ветвь —
     * отметка журнала перемещений (fk_sme_serial, V42): номер уже жил
     * своей жизнью (изолятор, ремонт), и удалять его нельзя, даже если
     * выпуск к нему больше не привязан.
     */
    @Modifying(
            flushAutomatically = true,
            clearAutomatically = true
    )
    @Query("""
            update ProductSerial serial
            set serial.customerOrderItem = null
            where serial.customerOrderItem.customerOrder.id = :customerOrderId
              and (
                      serial.issuedProductionOrder is not null
                   or exists (
                           select item.id
                           from ProductionOrderItem item
                           where item.serial.id = serial.id
                       )
                   or exists (
                           select event.id
                           from SerialMovementEvent event
                           where event.serialId = serial.id
                       )
                  )
            """)
    int unlinkReleasedByCustomerOrderId(
            @Param("customerOrderId") Long customerOrderId
    );

    /** Ячеек, оприходованных складом: номера, выпущенные приходом. */
    long countByReceivingBatchIsNotNull();

    /**
     * Занятые серии области в диапазоне, который партия хочет занять.
     *
     * Проверка нужна при подтверждении прихода: диапазон резервируется
     * на этапе формирования партии, и если за время печати и
     * сканирования номер всё-таки попал в реестр (например, выпуск
     * создавали в другой сессии), приход нельзя закрывать молча.
     */
    @Query("""
            select serial.serial
            from ProductSerial serial
            where serial.numberingKey = :numberingKey
              and serial.serial between :firstSerial and :lastSerial
            order by serial.serial asc
            """)
    List<Integer> findSerialsInRange(
            @Param("numberingKey") String numberingKey,
            @Param("firstSerial") int firstSerial,
            @Param("lastSerial") int lastSerial
    );

    /**
     * Номер по коду классификатора и серии — то, что читается из
     * отсканированного QR: «121200022» → код 1212, серия 22.
     */
    @Query("""
            select serial
            from ProductSerial serial
            where serial.numberingKey = :numberingKey
              and serial.serial = :serial
            """)
    Optional<ProductSerial> findByNumberingKeyAndSerial(
            @Param("numberingKey") String numberingKey,
            @Param("serial") int serial
    );

    /**
     * Ячейка по коду классификатора и серии.
     *
     * Нужен комплектованию ремонтных ЗНП: изолятор отдаёт ячейку полным
     * номером (код + серия), а провести операцию можно только по
     * экземпляру из реестра — и не всякий номер изолятора в реестре
     * есть (в брак попадают номера партий прихода, ещё не внесённые в
     * product_serials), поэтому результат опциональный по смыслу.
     *
     * Отдельный запрос, а не {@link #findByNumberingKeyAndSerial}:
     * numbering_key — GENERATED-колонка, и для ячейки она равна
     * «C<классификатор>», но вычислять её в приложении значило бы
     * второй раз продублировать формулу из V27.
     */
    @Query("""
            select serial
            from ProductSerial serial
            join fetch serial.productClassifier classifier
            where classifier.code = :code
              and serial.serial = :serial
            """)
    Optional<ProductSerial> findCellByClassifierCodeAndSerial(
            @Param("code") int code,
            @Param("serial") int serial
    );

    /**
     * Статус всех серийников заказа одним запросом.
     *
     * Заказ на производство содержит по строке на каждый экземпляр
     * изделия, поэтому статус меняется пакетным UPDATE: сохранение
     * сотен сущностей по одной неоправданно.
     *
     * Реестр и позиции живут одной жизнью: позиция взята в работу —
     * номер «В работе», позиция собрана — номер «Собран».
     *
     * flushAutomatically обязателен: серийники только что созданного
     * заказа ещё могут не быть записаны, а UPDATE должен их увидеть.
     */
    @Modifying(
            flushAutomatically = true,
            clearAutomatically = true
    )
    @Query("""
            update ProductSerial serial
            set serial.status = :status
            where serial.issuedProductionOrder.id = :productionOrderId
            """)
    int updateStatusByOrderId(
            @Param("productionOrderId") Long productionOrderId,
            @Param("status") ProductSerialStatus status
    );

    /**
     * Ячейки партии вместе с контролёром, отметившим входной контроль.
     *
     * Отдельный от {@code findDetailsByReceivingBatchId}: карточке
     * входного контроля, помимо номера, нужны вид контроля, дата и
     * фамилия контролёра, а {@code open-in-view} выключен — без
     * {@code fetch} обращение к ФИО прервало бы рендеринг страницы.
     */
    @Query("""
            select serial
            from ProductSerial serial
            left join fetch serial.productClassifier classifier
            left join fetch serial.inputControlBy checker
            where serial.receivingBatch.id = :batchId
            order by serial.serial asc
            """)
    List<ProductSerial> findControlDetailsByReceivingBatchId(
            @Param("batchId") Long batchId
    );

    /**
     * Отметка входного контроля сразу для перечня ячеек.
     *
     * Прошедшими контроль помечают разом все незабракованные ячейки
     * партии: событие одно (контролёр провёл контроль), и писать его
     * каждой строкой отдельно дороже, чем одним обновлением.
     *
     * status при этом не трогается: он принадлежит производству.
     */
    @Modifying(
            flushAutomatically = true,
            clearAutomatically = true
    )
    @Query("""
            update ProductSerial serial
            set serial.inputControlType = :type,
                serial.inputControlAt = :moment,
                serial.inputControlBy = :checker
            where serial.id in :ids
            """)
    int markInputControlByIds(
            @Param("ids") java.util.Collection<Long> ids,
            @Param("type") InputControlType type,
            @Param("moment") java.time.LocalDateTime moment,
            @Param("checker") AppUser checker
    );

    /**
     * Ячейки партии для экрана приёмки на склад.
     *
     * Отличие от {@code findControlDetailsByReceivingBatchId}: кладовщику
     * нужны обе отметки маршрута — входного контроля (что ячейку
     * действительно проверяли) и приёмки (что её уже приняли), — а
     * {@code open-in-view} выключен, поэтому обе подписи читаются тем же
     * запросом.
     */
    @Query("""
            select serial
            from ProductSerial serial
            left join fetch serial.productClassifier classifier
            left join fetch serial.inputControlBy checker
            left join fetch serial.acceptedBy receiver
            where serial.receivingBatch.id = :batchId
            order by serial.serial asc
            """)
    List<ProductSerial> findAcceptanceDetailsByReceivingBatchId(
            @Param("batchId") Long batchId
    );

    /**
     * Ячейки, принятые на склад полуфабрикатов — список для
     * комплектования подсборок.
     *
     * Ячейка считается принятой на склад, когда отмечена приёмкой
     * (acceptedAt IS NOT NULL). Эти номера кладовщик вводит в ЗНП:
     * склад подтверждает, что нужные подсборки физически лежат на
     * полках и готовы к выдаче.
     *
     * Классификатор подтянут fetch-join'ом: шаблон печатает полный
     * номер и наименование изделия, а open-in-view выключен — без
     * fetch обращение к наименованию упало бы на ленивой связи.
     */
    @Query("""
            select serial
            from ProductSerial serial
            left join fetch serial.productClassifier classifier
            left join fetch classifier.sensorCatalog
            where serial.acceptedAt is not null
            order by serial.productClassifier.code asc,
                     serial.serial asc
            """)
    List<ProductSerial> findAcceptedOnWarehouse();

    /**
     * Приёмка на склад сразу для перечня ячеек.
     *
     * Событие одно — кладовщик принял партию, — поэтому отметку ставят
     * одним обновлением. Статусы контроля и производства при этом не
     * трогают: у каждого свой хозяин.
     */
    @Modifying(
            flushAutomatically = true,
            clearAutomatically = true
    )
    @Query("""
            update ProductSerial serial
            set serial.acceptedAt = :moment,
                serial.acceptedBy = :receiver
            where serial.id in :ids
            """)
    int markAcceptedByIds(
            @Param("ids") java.util.Collection<Long> ids,
            @Param("moment") java.time.LocalDateTime moment,
            @Param("receiver") AppUser receiver
    );
}
