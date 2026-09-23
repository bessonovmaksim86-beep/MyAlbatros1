package ru.company.production.service;

import ru.company.production.entity.Role;

import java.util.Map;

/**
 * Соответствие роли и модуля, интерфейс которого ей принадлежит.
 *
 * Адрес нужен в двух местах: после входа в систему
 * ({@code DepartmentAuthenticationSuccessHandler}) и когда администратор
 * заходит в интерфейс другой роли. Держим одно перечисление, чтобы новый
 * модуль регистрировался в одной точке, а не копировался в if- цепочки.
 */
public final class ModuleRoutes {

    /**
     * @param name название модуля для заголовков и подписей в меню
     * @param url  адрес, куда попадает пользователь
     */
    public record Destination(String name, String url) {
    }

    /**
     * Рабочий стол — общая страница для ролей, у которых своего модуля пока
     * нет. Иначе вход под такую роль привёл бы к адресу без шаблона.
     */
    public static final Destination WORKPLACE =
            new Destination("Рабочий стол", "/home");

    private static final Map<Role, Destination> ROLE_DESTINATIONS = Map.of(

            Role.SDP_SERVICE_HEAD_TECHNOLOGY,
            new Destination("Технологический отдел", "/technology/general"),

            Role.SDP_SERVICE_HEAD_DISPATCHER,
            new Destination("Диспетчерский отдел", "/dispatcher/general"),

            Role.DISPATCHER,
            new Destination("Диспетчерский отдел", "/dispatcher/general"),

            Role.SDP_SPP_MASTER,
            new Destination("Руководитель цеха", "/workshop/staff"),

            Role.SDP_SA_MASTER,
            new Destination("Руководитель цеха", "/workshop/staff"),

            Role.SDP_SC_MASTER,
            new Destination("Руководитель цеха", "/workshop/staff"),

            Role.SDP_ST_MASTER,
            new Destination("Руководитель цеха", "/workshop/staff"),

            Role.OMTSIK_EXECUTOR,
            new Destination("Склад полуфабрикатов", "/warehouse/general"),

            Role.BVK_EXECUTOR,
            new Destination("Бюро входного контроля", "/input-control/general"),

            Role.SK_GENERAL,
            new Destination("Служба качества", "/quality/general")
    );

    private ModuleRoutes() {
    }

    public static Destination destinationFor(Role role) {
        return ROLE_DESTINATIONS.getOrDefault(role, WORKPLACE);
    }

    /**
     * Есть ли у роли собственный модуль: для таких ролей администратор прямо
     * предлагает интерфейс отдела, остальные открываются на рабочем столе.
     */
    public static boolean hasOwnModule(Role role) {
        return ROLE_DESTINATIONS.containsKey(role);
    }
}
