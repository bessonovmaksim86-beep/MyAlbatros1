package ru.company.production.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.company.production.entity.ProductionItemStatus;
import ru.company.production.entity.ProductionOrderItem;

import java.util.Collection;
import java.util.List;

@Repository
public interface ProductionOrderItemRepository
        extends JpaRepository<ProductionOrderItem, Long> {

    /**
     * Состав заказа на производство: одна строка — один выпускаемый
     * экземпляр с его серийным номером.
     *
     * Наименование изделия берётся из классификатора, а для датчика —
     * из справочника датчиков (sensorClassName).
     */
    @Query("""
            select
                item.id as id,
                item.position as position,
                classifier.id as classifierId,
                classifier.code as classifierCode,
                classifier.name as classifierName,
                sensor.name as sensorClassName,
                classifier.productType as productType,
                serial.serial as serialNumber,
                item.status as status,
                customerItem.position as customerOrderItemPosition,
                item.note as note,
                serial.znpNumber as znpNumber
            from ProductionOrderItem item
            join item.productClassifier classifier
            left join classifier.sensorCatalog sensor
            left join item.serial serial
            left join item.customerOrderItem customerItem
            where item.productionOrder.id = :productionOrderId
            order by item.position
            """)
    List<ProductionOrderItemView> findItemsByOrderId(
            @Param("productionOrderId") Long productionOrderId
    );

    /*
     * Серийники выпуска для набора заказов покупателя.
     *
     * Нужен разделу распределения: строка графика планирует изделие до
     * выпуска и своего серийного номера не хранит, а номер появляется
     * именно здесь — в составе выпуска, привязанном к позиции заказа
     * покупателя. Выборка пакетная (по всем заказам страницы одним
     * запросом), иначе таблица распределения порождала бы запрос на
     * каждую строку.
     *
     * Порядок задаётся полем position выпуска (он сквозной по заказу);
     * номер экземпляра внутри позиции по нему восстанавливает сервис.
     * Полное наименование берётся из fullName позиции заказа («как в
     * КД»), а род датчика и имя классификатора подставляются, когда
     * fullName не заполнен. Строки без серийного номера исключены:
     * сопоставлять нечего.
     *
     * serialId (идентификатор ProductSerial) выбирается наряду с самим
     * номером: write-пути (хук закрытия ЗНП на СГП) по нему ищут связи
     * «датчик ⇄ заказ» в serial_production_orders, где ключ — id, а не
     * человекочитаемый номер. Read-модулям распределения он безразличен.
     *
     * Aliases join'а (serialRow) и выбранного поля (serial) разведены
     * намеренно: совпадающее имя алиаса связи и алиаса выборки
     * Hibernate трактует неоднозначно при сборке метамодели запроса.
     */
    @Query("""
            select
                customerOrder.id as customerOrderId,
                customerItem.position as itemPosition,
                item.position as releasePosition,
                serialRow.id as serialId,
                classifier.code as classifierCode,
                serialRow.serial as serial,
                customerItem.fullName as fullName,
                sensor.name as sensorName,
                classifier.name as classifierName
            from ProductionOrderItem item
            join item.productClassifier classifier
            join item.serial serialRow
            join item.customerOrderItem customerItem
            join customerItem.customerOrder customerOrder
            left join classifier.sensorCatalog sensor
            where customerOrder.id in :customerOrderIds
            order by customerOrder.id, customerItem.position, item.position
            """)
    List<ProductionOrderSerialProjection> findSerialsByCustomerOrderIds(
            @Param("customerOrderIds") Collection<Long> customerOrderIds
    );

    /**
     * Серийники выпуска для набора заказов на производство — колонка
     * «Серийник(и)» списка заказов и фильтр по типу изделия.
     *
     * Пакетность обязательна: список показывает десятки заказов, и
     * запрос на строку превратил бы страницу в N+1. Ключом строки
     * служит production_order_id — тот же идентификатор, под которым
     * список хранит сам заказ.
     *
     * Номер и тип берутся из самого серийника (product_serials), а не
     * из классификатора позиции: реестр хранит и код, и серию, и тип
     * изделия, поэтому строка не зависит от того, дописан ли
     * классификатор позже. Строки без номера исключены — показывать в
     * колонке нечего.
     *
     * У заказа-комплектующего (ЗНП) позиций выпуска нет, поэтому этот
     * запрос для него пустой, а его датчики читаются из
     * SerialProductionOrderRepository.findSerialsByOrderIds.
     */
    @Query("""
            select
                item.productionOrder.id as orderId,
                classifier.code as classifierCode,
                serialRow.serial as serial,
                serialRow.productType as productType
            from ProductionOrderItem item
            join item.serial serialRow
            join serialRow.productClassifier classifier
            where item.productionOrder.id in :orderIds
            order by item.productionOrder.id, item.position
            """)
    List<ProductionOrderSerialRowView> findSerialsByOrderIds(
            @Param("orderIds") Collection<Long> orderIds
    );

    @Query("""
            select count(item.id)
            from ProductionOrderItem item
            where item.productionOrder.id = :productionOrderId
            """)
    long countByOrderId(@Param("productionOrderId") Long productionOrderId);

    @Query("""
            select count(item.id)
            from ProductionOrderItem item
            where item.productionOrder.id = :productionOrderId
              and item.status = :status
            """)
    long countByOrderIdAndStatus(
            @Param("productionOrderId") Long productionOrderId,
            @Param("status") ProductionItemStatus status
    );

    /**
     * Статус всех позиций заказа одним запросом.
     *
     * Заказ на производство может содержать сотни экземпляров
     * (по одному на изделие), поэтому статус меняется пакетным UPDATE,
     * а не сохранением каждой строки.
     */
    /*
     * flushAutomatically обязателен: позиции только что созданного
     * заказа ещё могут не быть записаны, а UPDATE должен их увидеть.
     */
    @Modifying(
            flushAutomatically = true,
            clearAutomatically = true
    )
    @Query("""
            update ProductionOrderItem item
            set item.status = :status
            where item.productionOrder.id = :productionOrderId
            """)
    int updateStatusByOrderId(
            @Param("productionOrderId") Long productionOrderId,
            @Param("status") ProductionItemStatus status
    );

    /**
     * Строки состава выпуска, для которых номер ЗНП ещё не внесён.
     *
     * Нужен проверке при взятии выпуска в работу: номер ЗНП диспетчер
     * вписывает напротив каждого серийника, и заказ, где часть строк
     * пуста, в работу брать нельзя — цех получит изделия без
     * документа комплектования.
     *
     * Строка без серийного номера засчитывается как невнесённая:
     * выпуск с незаполненным резервом ровно в том же положении.
     * Пустая строка и строка из пробелов считаются невнесёнными —
     * поле в форме остаётся именно таким, когда его не трогали.
     *
     * У заказа-комплектующего позиций выпуска нет, поэтому запрос для
     * него возвращает ноль и проверка к нему неприменима.
     */
    @Query("""
            select count(item.id)
            from ProductionOrderItem item
            left join item.serial serial
            where item.productionOrder.id = :productionOrderId
              and (
                      serial.id is null
                   or serial.znpNumber is null
                   or trim(serial.znpNumber) = ''
                  )
            """)
    long countWithoutZnpByOrderId(
            @Param("productionOrderId") Long productionOrderId
    );

    /**
     * Экземпляр плана, ради которого выпущено изделие, несущее заказ.
     *
     * Нужен браковке ячейки: заявка знает номер ЗНП, а строка
     * {@code order_schedule_rows} серийного номера не хранит — её
     * экземпляр задан тройкой «заказ покупателя + позиция + номер
     * внутри позиции». Мост между ними — состав заказ-комплектующего:
     * в матрицу (V20) наряду с ячейками входит выпускаемое изделие, и
     * именно его позиция выпуска отвечает на вопрос «для какого
     * экземпляра планировали эту работу».
     *
     * Ячейки отсекаются самим join'ом: у них нет позиции выпуска,
     * привязанной к позиции заказа покупателя, — ячейку не выпускают,
     * её принимают на склад.
     *
     * Номер внутри позиции — порядковый, а не значение {@code position}:
     * позиция заказа на десять изделий даёт десять строк выпуска со
     * сквозным {@code position}, и для второй позиции заказа экземпляр
     * обязан считаться от нуля. Считается ровно тем же способом, что и
     * сопоставление в {@code WorkshopDistributionSerialService#resolve}:
     * количеством строк той же позиции заказа с меньшим
     * {@code position}. Расхождение двух мест подписало бы работу
     * чужим номером.
     */
    @Query("""
            select
                customerItem.customerOrder.id as customerOrderId,
                customerItem.position as itemPosition,
                (
                    select count(other.id)
                    from ProductionOrderItem other
                    where other.customerOrderItem.id = item.customerOrderItem.id
                      and other.position < item.position
                ) as instanceIndex
            from ProductionOrderItem item
            join item.customerOrderItem customerItem
            join item.serial carrier
            where carrier.id in (
                select link.serial.id
                from SerialProductionOrder link
                where link.productionOrder.id = :znpOrderId
            )
            """)
    List<ScheduleInstanceView> findScheduleInstancesOfZnp(
            @Param("znpOrderId") Long znpOrderId
    );

    /**
     * Тройка экземпляра плана для выпускаемого изделия.
     *
     * Позиция обязана быть привязана к позиции заказа покупателя: без
     * неё экземпляр графика не существует, и снимать проведение не с
     * чего.
     */
    interface ScheduleInstanceView {

        Long getCustomerOrderId();

        Integer getItemPosition();

        Integer getInstanceIndex();
    }

    /**
     * Изделия-носители заказ-комплектующего: какие выпуски он обслуживает.
     *
     * Нужен потолку комплектования: лимит «сколько таких ячеек
     * допускается» задан составом изделия-носителя
     * (classifier_inclusion, V10), а носитель в матрице (V20) стоит
     * рядом с ячейками и отличается от них именно позицией выпуска.
     *
     * У группового ЗНП выпуска нет, поэтому выдача пустая и потолок по
     * составу изделия к нему неприменим.
     */
    @Query("""
            select distinct classifier.id
            from ProductionOrderItem item
            join item.serial carrier
            join carrier.productClassifier classifier
            where carrier.id in (
                select link.serial.id
                from SerialProductionOrder link
                where link.productionOrder.id = :znpOrderId
            )
            """)
    List<Long> findCarrierClassifierIdsOfZnp(
            @Param("znpOrderId") Long znpOrderId
    );

    /**
     * Удаляет позиции заказа целиком.
     *
     * Ключ заказа в этой таблице стоит с ON DELETE CASCADE (V16), но
     * раздел «Очистка данных» удаляет заказ пакетными запросами в
     * определённом порядке: позиция держит ссылку на серийный номер с
     * RESTRICT (V17), и строки обязаны уйти ДО удаления реестра, иначе
     * удаление номеров упрётся во внешний ключ.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            delete from ProductionOrderItem item
            where item.productionOrder.id = :productionOrderId
            """)
    int deleteAllByOrderId(
            @Param("productionOrderId") Long productionOrderId
    );

    /** Позиции выпуска с их серийниками — для предпросмотра удаления. */
    @Query("""
            select serial.id
            from ProductionOrderItem item
            join item.serial serial
            where item.productionOrder.id = :productionOrderId
            """)
    List<Long> findSerialIdsByOrderId(
            @Param("productionOrderId") Long productionOrderId
    );

    /**
     * Снимает удаляемые серийники с позиций выпуска.
     *
     * Ключ serial_id ссылается на реестр с RESTRICT (V17): позиция
     * выпуска удержала бы удаление номера из реестра. Колонка
     * разрешающая — выпуск теряет привязку изделия к позиции, и
     * раздел обязан назвать это предупреждением до удаления, а не
     * получить отказ базы на середине транзакции.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update ProductionOrderItem item
            set item.serial = null
            where item.serial.id in :serialIds
            """)
    int detachSerials(
            @Param("serialIds") java.util.Collection<Long> serialIds
    );
}
