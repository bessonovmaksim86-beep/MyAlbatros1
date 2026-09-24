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
     * Заказы вместе с автором, поставщиком, изделием заявки и маршрутом:
     * список выводит ФИО, написание поставщика, наименование изделия и
     * код техпроцесса, а {@code open-in-view} выключен — ленивая связь
     * вне транзакции не читается.
     *
     * Классификатор самой заявки открывают отдельным fetch: с тех пор
     * как изделие назначает диспетчер, оно стало свойством заголовка и
     * участвует в каждой строке реестра. Классификатор маршрута берут
     * вторым — подпись «ТП-14 — 1212» строится по коду изделия техпроцесса.
     */
    @Query("""
            select distinct ord
            from RepairOrder ord
            left join fetch ord.createdBy author
            left join fetch ord.supplier supplier
            left join fetch ord.productClassifier classifier
            left join fetch classifier.sensorCatalog sensor
            left join fetch ord.techProcess process
            left join fetch process.productClassifier processClassifier
            order by ord.orderDate desc, ord.id desc
            """)
    List<RepairOrder> findAllDetailed();

    @Query("""
            select distinct ord
            from RepairOrder ord
            left join fetch ord.createdBy author
            left join fetch ord.supplier supplier
            left join fetch ord.productClassifier classifier
            left join fetch classifier.sensorCatalog sensor
            left join fetch ord.techProcess process
            left join fetch process.productClassifier processClassifier
            where ord.id = :id
            """)
    Optional<RepairOrder> findDetailedById(@Param("id") Long id);

    /**
     * Заявки, ожидающие службы качества.
     *
     * Порядок обратный реестру: свежие заявки разбирают первыми, и
     * перечень открытой работы читают сверху вниз.
     */
    @Query("""
            select distinct ord
            from RepairOrder ord
            left join fetch ord.createdBy author
            left join fetch ord.supplier supplier
            left join fetch ord.productClassifier classifier
            left join fetch classifier.sensorCatalog sensor
            left join fetch ord.techProcess process
            left join fetch process.productClassifier processClassifier
            where ord.status = ru.company.production.entity.RepairOrderStatus.NEW
            order by ord.orderDate asc, ord.id asc
            """)
    List<RepairOrder> findNewRequests();

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

    /**
     * Занят ли номер: его вводит руководитель по журналу подразделения,
     * и повторный номер означал бы два бумажных документа с одной
     * подписью. Отменённые заказы тоже считаются занятыми — номер
     * обязан быть свободен для сверки.
     */
    boolean existsByNumberIgnoreCase(String number);

    long countByOrderType(RepairOrderType orderType);

    /**
     * Незакрытых заказов: их считают сводка модуля и счётчик меню.
     *
     * Возвращённую диспетчеру заявку считают незакрытой: состав по ней
     * служба качества уже сдала, но ремонт ещё не выполнен и техпроцесс
     * не выбран, поэтому такая заявка остаётся открытым документом.
     */
    @Query("""
            select count(ord.id)
            from RepairOrder ord
            where ord.status in
                (ru.company.production.entity.RepairOrderStatus.NEW,
                 ru.company.production.entity.RepairOrderStatus.IN_WORK,
                 ru.company.production.entity.RepairOrderStatus.RETURNED)
            """)
    long countOpen();

    /** Заявок, ожидающих набора состава службой качества. */
    long countByStatusIn(java.util.Collection<RepairOrderStatus> statuses);
}
