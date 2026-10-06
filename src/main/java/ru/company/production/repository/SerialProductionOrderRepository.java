package ru.company.production.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.company.production.entity.SerialProductionOrder;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Связь «многие ко многим» изделия и заказа на производство.
 *
 * Оба направления нужны одинаково часто, поэтому индексы и запросы
 * есть в обе стороны:
 *
 *   «чем укомплектован датчик»  — карточка изделия;
 *   «какие датчики в группе ЗНП» — карточка заказа-комплектующего.
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
     * Датчики набора заказов — колонка «Серийник(и)» списка заказов.
     *
     * Это второй из двух источников колонки. У выпуска (ЗП) номера
     * читаются из состава выпуска, а у заказа-комплектующего (ЗНП)
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
}