package ru.company.production.repository;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Занятость сотрудника в конкретный день плана.
 *
 * Проекция, а не сущность: считать нужно сумму часов, а не перебирать
 * строки графика в памяти — у подразделения за горизонт планирования
 * их тысячи.
 *
 * @param executorId  сотрудник
 * @param plannedDate день плана
 * @param hours       суммарная трудоёмкость закреплённых строк
 */
public interface ExecutorDayLoadView {

    Long getExecutorId();

    LocalDate getPlannedDate();

    BigDecimal getHours();
}
