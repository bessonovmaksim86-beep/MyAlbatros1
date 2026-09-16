package ru.company.production.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import ru.company.production.entity.RoleUser;

@Getter
@RequiredArgsConstructor
public enum Role {

    ADMIN("Администратор базы"),

    SDP_SERVICE_HEAD_TECHNOLOGY ("Технологическое бюро"),

    SDP_SPP_EXECUTOR("СДП.Цех подготовки производства.Исполнитель."),
    SDP_SA_EXECUTOR("СДП.Цех сборки.Исполнитель."),
    SDP_SC_EXECUTOR("СДП.Цех настройки.Исполнитель."),
    SDP_ST_EXECUTOR("СДП.Цех испытаний.Исполнитель."),
    SDP_SPP_MASTER("СДП.Цех подготовки производства.Руководитель."),
    SDP_SA_MASTER("СДП.Цех сборки.Руководитель."),
    SDP_SC_MASTER("СДП.Цех настройки.Руководитель."),
    SDP_ST_MASTER("СДП.Цех испытаний.Руководитель."),

    SGP("Склад готовой продукции"),

    SGM("Служба главного метролога"),

    SC("Служба качества"),

    OMTSIK("СПФ"),

    DISPATCHER("Диспетчерский отдел");

    private final String displayName;
}