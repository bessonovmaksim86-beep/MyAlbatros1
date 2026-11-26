package ru.company.production.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.company.production.entity.WorkWithoutPlan;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

public interface WorkWithoutPlanRepository
        extends JpaRepository<WorkWithoutPlan, Long> {

    @Query("""
            SELECT SUM(w.normHours * w.quantity)
            FROM WorkWithoutPlan w
            WHERE w.employee.id = :employeeId
              AND w.workDate >= :from
              AND w.workDate <= :to
            """)
    BigDecimal sumTotalHours(@Param("employeeId") Long employeeId,
                             @Param("from") LocalDate from,
                             @Param("to") LocalDate to);

    @Query("""
            SELECT SUM(w.normHours * w.quantity)
            FROM WorkWithoutPlan w
            WHERE w.employee.id = :employeeId
              AND w.workDate = :date
            """)
    BigDecimal sumTotalHoursForDay(@Param("employeeId") Long employeeId,
                                   @Param("date") LocalDate date);

    /**
     * Записи сотрудников подразделения за период — реестр раздела.
     */
    @Query("""
            select w
            from WorkWithoutPlan w
            where w.employee.id in :employeeIds
              and w.workDate between :from and :to
            order by w.workDate desc, w.id desc
            """)
    List<WorkWithoutPlan> findForEmployees(
            @Param("employeeIds") Collection<Long> employeeIds,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);

    /**
     * Все записи одного сотрудника — форма показывает, сколько уже
     * внесено на выбранную дату.
     */
    List<WorkWithoutPlan> findByEmployeeIdOrderByWorkDateDescIdDesc(
            Long employeeId);

    /**
     * Разрез «сотрудник и операция» по работам без плана.
     *
     * Имя операции здесь свободный текст, поэтому группировка идёт по
     * нему как есть: сверять с справочником операций нельзя — запись
     * заводится именно для работ, которых в справочнике нет.
     */
    @Query("""
            select w.employee.id               as employeeId,
                   w.operationName             as operationName,
                   count(w.id)                 as rowCount,
                   sum(w.normHours * w.quantity) as totalHours
            from WorkWithoutPlan w
            where w.employee.id in :employeeIds
              and w.workDate between :from and :to
            group by w.employee.id, w.operationName
            order by w.employee.id asc, sum(w.normHours * w.quantity) desc
            """)
    List<ru.company.production.repository.OrderScheduleRowRepository
            .HoursByEmployeeOperation> sumByEmployeeAndOperation(
            @Param("employeeIds") Collection<Long> employeeIds,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);
}
