package ru.company.production.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Вид привязки сотрудника цеха.
 *
 * Сотруднику закрепляют либо операцию, либо рабочее место —
 * одновременно и то и другое в одной записи не хранится.
 */
@Getter
@RequiredArgsConstructor
public enum StaffBindingType {

    OPERATION("Операция"),
    WORK_PLACE("Рабочее место");

    private final String displayName;
}
