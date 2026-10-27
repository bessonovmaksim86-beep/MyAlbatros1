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

    /**
     * Проведённые отметки, где значением внесён этот номер ячейки.
     *
     * Нужен браковке из цеха: пока ячейку уже внесли в контролируемую
     * операцию, отбраковать её нельзя — операция осталась бы с отметкой
     * о ячейке, которой в изделии больше нет. Параметр «Номер ячейки»
     * текстовый ({@link ru.company.production.entity.ControlParamType#TEXT_INPUT}),
     * поэтому номер ищется по {@code text_value}, а не по ссылке.
     *
     * Контролёр обязан видеть НЕ только факт отметки, но и по какому
     * параметру она стоит, поэтому справочник подтянут fetch-join'ом:
     * open-in-view выключен.
     */
    @Query("""
            select ocp
            from OperationControlParam ocp
            join fetch ocp.controlParam param
            where ocp.completedAt is not null
              and trim(ocp.textValue) = :cellNumber
            order by ocp.completedAt desc
            """)
    List<OperationControlParam> findCompletedByCellNumber(
            @Param("cellNumber") String cellNumber
    );
}
