package ru.company.production.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.company.production.entity.OperationControlParam;

import java.util.List;

@Repository
public interface OperationControlParamRepository
        extends JpaRepository<OperationControlParam, Long> {

    /**
     * Факты КП по строке графика — для проверки при повторном проведении.
     */
    @Query("""
            select ocp
            from OperationControlParam ocp
            where ocp.scheduleRow.id = :rowId
            """)
    List<OperationControlParam> findByScheduleRowId(
            @Param("rowId") Long rowId
    );
}
