package ru.company.production.repository;

/**
 * Строка выбора техпроцесса для конкретного изделия.
 *
 * Список строят так, чтобы базовый маршрут изделия шёл первым,
 * поэтому признак базовости отдают вместе с кодом: шаблон рисует
 * по нему метку «Базовый», а не вычисляет её заново.
 */
public interface ClassifierRouteOptionView {

    /** Идентификатор техпроцесса. */
    Long getId();

    String getCode();

    boolean isBasic();

    long getOperationCount();
}
