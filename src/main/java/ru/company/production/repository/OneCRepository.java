package ru.company.production.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.company.production.entity.ProductionOrder;
import ru.company.production.entity.ProductionOrderStatus;

import java.time.LocalDate;
import java.util.List;

/**
 * Выборки для интеграции с 1С.
 *
 * Отдельный репозиторий, а не разрастание {@code ProductionOrderRepository}:
 * выгрузка читает только скалярные поля заказа и клиента, тогда как
 * штатные запросы модуля диспетчера тянут состав, серийники и счётчики.
 * Разные проекции — разные запросы, и правка одного не задевает другой.
 *
 * Выгружаются только заказы верхнего уровня (ЗП): {@code parent is null}
 * и серийный вид {@code KITTING}. ЗНП-комплекты 1С ведёт иначе, и в
 * первую очередь согласуют именно выпуск.
 */
@Repository
public interface OneCRepository extends JpaRepository<ProductionOrder, Long> {

    /**
     * Заказы верхнего уровня (ЗП) за период и по набору статусов.
     *
     * Список статусов обязан быть непустым: пустой набор означал бы
     * «все статусы», и сервис подставляет полный перечень сам, чтобы
     * в JPQL не ветвить {@code in} на пустоту.
     *
     * Клиент дочитывается {@code left join}: у выпуска заказ покупателя
     * обязателен по бизнес-правилу, но явный join безопаснее неявного —
     * иначе строка без клиента исчезла бы из выдачи.
     */
    @Query("""
            select
                ord.number as number,
                ord.orderDate as orderDate,
                co.number as customerOrderNumber,
                customer.name as customerName,
                ord.status as status
            from ProductionOrder ord
            left join ord.customerOrder co
            left join co.customer customer
            where ord.active = true
              and ord.parentProductionOrder is null
              and ord.orderDate between :from and :to
              and ord.status in :statuses
            order by ord.orderDate desc, ord.number desc
            """)
    List<ZpExportView> findZpForExport(
            @Param("from") LocalDate from,
            @Param("to") LocalDate to,
            @Param("statuses") List<ProductionOrderStatus> statuses
    );
}
