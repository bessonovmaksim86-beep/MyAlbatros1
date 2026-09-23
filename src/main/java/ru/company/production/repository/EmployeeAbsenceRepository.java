package ru.company.production.repository;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.company.production.entity.EmployeeAbsence;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

@Repository
public interface EmployeeAbsenceRepository
        extends JpaRepository<EmployeeAbsence, Long> {

    /**
     * Отсутствия сотрудника за период — весь календарь месяца одним
     * запросом, чтобы шаблон не ходил в базу на каждую ячейку.
     */
    @Query("""
            select absence
            from EmployeeAbsence absence
            where absence.employee.id = :employeeId
              and absence.absenceDate between :from and :to
            order by absence.absenceDate asc
            """)
    List<EmployeeAbsence> findInRange(
            @Param("employeeId") Long employeeId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to
    );

    /**
     * Занятые даты среди отмеченных: существующую отметку другого вида
     * сервис не перезаписывает молча, а сообщает о ней.
     */
    @EntityGraph(attributePaths = "employee")
    @Query("""
            select absence
            from EmployeeAbsence absence
            where absence.employee.id = :employeeId
              and absence.absenceDate in :dates
            """)
    List<EmployeeAbsence> findExistingOnDates(
            @Param("employeeId") Long employeeId,
            @Param("dates") Collection<LocalDate> dates
    );

    @Modifying
    @Query("""
            delete from EmployeeAbsence absence
            where absence.employee.id = :employeeId
              and absence.absenceDate in :dates
            """)
    int deleteOnDates(
            @Param("employeeId") Long employeeId,
            @Param("dates") Collection<LocalDate> dates
    );

    /*
     * Снятие только «выходного»: удаление дня из справочника
     * администратора не должно стирать отпуск или больничный,
     * поставленные на ту же дату.
     */
    @Modifying
    @Query("""
            delete from EmployeeAbsence absence
            where absence.employee.id = :employeeId
              and absence.absenceDate = :date
              and absence.absenceType = ru.company.production.entity.AbsenceType.DAY_OFF
            """)
    int deleteDayOffOnDate(
            @Param("employeeId") Long employeeId,
            @Param("date") LocalDate date
    );
}
