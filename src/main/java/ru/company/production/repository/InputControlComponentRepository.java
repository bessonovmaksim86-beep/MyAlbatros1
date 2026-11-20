package ru.company.production.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.company.production.entity.InputControlComponent;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Журнал входного контроля несерийных комплектующих.
 *
 * open-in-view выключен, поэтому запросы, отдающие данные для
 * шаблона, подтягивают связи {@code join fetch}.
 */
@Repository
public interface InputControlComponentRepository
        extends JpaRepository<InputControlComponent, Long> {

    /**
     * Перечень записей с подтяжкой поставщика и контролёра.
     *
     * Новые записи — сверху. Связи берутся fetch'ом, потому что
     * open-in-view отключён, а шаблоны читают {@code checkerName}
     * и {@code supplierName} прямо из строки.
     */
    @Query("""
            select j
            from InputControlComponent j
            left join fetch j.checkedBy
            left join fetch j.supplier
            order by j.controlDate desc, j.id desc
            """)
    List<InputControlComponent> findAllDetailed();

    /**
     * Одна запись вместе с поставщиком и контролёром.
     *
     * Поставщик нужен форме правки: из детальной записи берут его
     * идентификатор, чтобы выпадающий список открылся с уже
     * выбранным написанием.
     */
    @Query("""
            select j
            from InputControlComponent j
            left join fetch j.checkedBy checker
            left join fetch j.supplier supplier
            where j.id = :id
            """)
    Optional<InputControlComponent> findDetailedById(
            @Param("id") Long id
    );

    /**
     * Отбор записей по периоду, поставщику и наименованию.
     *
     * Границы периода приходят меткой времени, а не датой: колонка
     * {@code control_date} хранит {@code DATETIME}, и сравнение даты
     * с меткой времени в JPQL разобрались бы по-разному на разных
     * драйверах. Сервис превращает «по» в начало следующих суток,
     * поэтому последний день периода попадает в выборку целиком.
     *
     * Null-параметр означает «условие не задано»: пустой пункт
     * выпадающего списка отдаёт пустую строку, и страница обязана
     * вернуть весь перечень, а не бросить исключение на разборе
     * запроса.
     */
    @Query("""
            select j
            from InputControlComponent j
            left join fetch j.checkedBy checker
            left join fetch j.supplier supplier
            where (:dateFrom is null or j.controlDate >= :dateFrom)
              and (:dateTo is null or j.controlDate < :dateTo)
              and (:supplierId is null or j.supplier.id = :supplierId)
              and (:productName is null or lower(j.productName) like lower(:productName))
            order by j.controlDate desc, j.id desc
            """)
    List<InputControlComponent> findFiltered(
            @Param("dateFrom") LocalDateTime dateFrom,
            @Param("dateTo") LocalDateTime dateTo,
            @Param("supplierId") Long supplierId,
            @Param("productName") String productName
    );

    /** Записей в журнале — цифра у пункта меню. */
    long count();
}
