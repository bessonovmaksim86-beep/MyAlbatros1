package ru.company.production.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum Role {

    ADMIN("Администратор базы"),
    SDP_SERVICE_HEAD_TECHNOLOGY ("Технологическое бюро"),
    SDP_SERVICE_HEAD_DISPATCHER ("Диспетчерский отдел"),
    SDP_SPP_EXECUTOR("СДП.Цех подготовки производства.Исполнитель."),
    SDP_SA_EXECUTOR("СДП.Цех сборки.Исполнитель."),
    SDP_SC_EXECUTOR("СДП.Цех настройки.Исполнитель."),
    SDP_ST_EXECUTOR("СДП.Цех испытаний.Исполнитель."),
    SDP_SPP_MASTER("СДП.Цех подготовки производства.Руководитель."),
    SDP_SA_MASTER("СДП.Цех сборки.Руководитель."),
    SDP_SC_MASTER("СДП.Цех настройки.Руководитель."),
    SDP_ST_MASTER("СДП.Цех испытаний.Руководитель."),
    BVK_EXECUTOR("СК.Бюро входного контроля.Исполнитель"),
    SGP("Склад готовой продукции"),

    SGM("Служба главного метролога"),

    SC("Служба качества"),

    /*
     * Руководитель службы качества: собственный интерфейс с изолятором
     * брака и заказами на ремонт (ЗНП).
     */
    SK_GENERAL("СК.Руководитель службы качества"),

    OMTSIK_EXECUTOR("СГД.Склад полуфабрикатов.Исполнитель"),

    DISPATCHER("Диспетчерский отдел");

    private final String displayName;
}