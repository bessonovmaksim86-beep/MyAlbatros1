package ru.company.production.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum OperationTypeCode {

    PRODUCTION("Производственная"),
    TRANSFER("Передаточная"),
    RETURN("Возвратная"),
    WORK_DISTRIBUTION("Распределение работ"),
    KIT_RECEIPT("Получение комплектации");

    private final String displayName;
}