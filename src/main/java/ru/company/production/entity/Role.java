package ru.company.production.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum Role {

    ADMIN("Администратор базы"),
    DISPATCHER("Диспетчерский отдел"),
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


    BVK_EXECUTOR("СК.Бюро входного контроля.Исполнитель."),
    BVK_MASTER("СК.Бюро входного контроля.Руководитель."),
    BTK_EXECUTOR("СК.Бюро технического контроля.Исполнитель."),
    BTK_MASTER("СК.Бюро технического контроля.Руководитель."),
    SC("Служба качества"),
    /*
     * Руководитель службы качества: собственный интерфейс с изолятором
     * брака и заказами на ремонт (ЗНП).
     */
    SK_GENERAL("Служба качества.Руководитель"),

    SGM_MASTER("СГМ.Отдел метрологии.Руководитель"),


    OMTSIK_EXECUTOR("СГД.Склад полуфабрикатов.Исполнитель"),
    OMTSIK_С_EXECUTOR("СГД.Склад комплектования.Исполнитель"),
    OMTSIK_MASTER("СГД.Склад полуфабрикатов.Руководитель"),
    OMTSIK_С_MASTER("СГД.Склад комплектации.Руководитель"),
    SGP_MASTER("СГД.Склад готовой продукции.Руководитель");

    private final String displayName;
}