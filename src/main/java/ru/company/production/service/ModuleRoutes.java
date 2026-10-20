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

    private static final Map<Role, Destination> ROLE_DESTINATIONS =
            Map.ofEntries(

            Map.entry(
                    Role.SDP_SERVICE_HEAD_TECHNOLOGY,
                    new Destination(
                            "Технологический отдел",
                            "/technology/general"
                    )
            ),

            Map.entry(
                    Role.SDP_SERVICE_HEAD_DISPATCHER,
                    new Destination(
                            "Диспетчерский отдел",
                            "/dispatcher/general"
                    )
            ),

            Map.entry(
                    Role.DISPATCHER,
                    new Destination(
                            "Диспетчерский отдел",
                            "/dispatcher/general"
                    )
            ),

            /*
             * Руководители цехов, складов и бюро входного контроля
             * открывают один и тот же модуль (/workshop): его границы
             * определяются подразделением учётной записи, а не ролью.
             * Название направления при этом остаётся «своим» — по нему
             * администратор в разделе «Роли и вход» группирует роли,
             * и руководитель склада оказывается в одной группе с
             * исполнителем этого склада.
             */
            Map.entry(
                    Role.SDP_SPP_MASTER,
                    new Destination(
                            "Руководитель цеха",
                            "/workshop/staff"
                    )
            ),

            Map.entry(
                    Role.SDP_SA_MASTER,
                    new Destination(
                            "Руководитель цеха",
                            "/workshop/staff"
                    )
            ),

            Map.entry(
                    Role.SDP_SC_MASTER,
                    new Destination(
                            "Руководитель цеха",
                            "/workshop/staff"
                    )
            ),

            Map.entry(
                    Role.SDP_ST_MASTER,
                    new Destination(
                            "Руководитель цеха",
                            "/workshop/staff"
                    )
            ),

            Map.entry(
                    Role.OMTSIK_MASTER,
                    new Destination(
                            "Склад полуфабрикатов",
                            "/workshop/staff"
                    )
            ),

            Map.entry(
                    Role.OMTSIK_С_MASTER,
                    new Destination(
                            "Склад комплектации",
                            "/workshop/staff"
                    )
            ),

            Map.entry(
                    Role.BVK_MASTER,
                    new Destination(
                            "Бюро входного контроля",
                            "/workshop/staff"
                    )
            ),

            /*
             * Склад готовой продукции и отдел метрологии ведут тот же
             * модуль руководителя подразделения: их границы задаёт
             * подразделение учётной записи (WorkshopStaffService), а не
             * отдельный интерфейс — операции и сотрудники у них работают
             * по тем же правилам, что у цехов и прочих складов.
             */
            Map.entry(
                    Role.SGP_MASTER,
                    new Destination(
                            "Склад готовой продукции",
                            "/workshop/staff"
                    )
            ),

            Map.entry(
                    Role.SGM_MASTER,
                    new Destination(
                            "Отдел метрологии",
                            "/workshop/staff"
                    )
            ),

            Map.entry(
                    Role.OMTSIK_EXECUTOR,
                    new Destination(
                            "Склад полуфабрикатов",
                            "/warehouse/general"
                    )
            ),

            Map.entry(
                    Role.OMTSIK_С_EXECUTOR,
                    new Destination(
                            "Склад комплектования",
                            "/warehouse/general"
                    )
            ),

            Map.entry(
                    Role.BVK_EXECUTOR,
                    new Destination(
                            "Бюро входного контроля",
                            "/input-control/general"
                    )
            ),

            Map.entry(
                    Role.SK_GENERAL,
                    new Destination(
                            "Служба качества",
                            "/quality/general"
                    )
            ),

            /*
             * Исполнители цехов — единый интерфейс «Мои операции».
             * У них нет полного цехового модуля (он закрыт в
             * SecurityConfig), поэтому адрес ведёт на страницу
             * с минимальным боковым меню и единственным пунктом.
             */
            Map.entry(
                    Role.SDP_SPP_EXECUTOR,
                    new Destination(
                            "Цех подготовки производства",
                            "/workshop-executor"
                    )
            ),

            Map.entry(
                    Role.SDP_SA_EXECUTOR,
                    new Destination(
                            "Цех сборки",
                            "/workshop-executor"
                    )
            ),

            Map.entry(
                    Role.SDP_SC_EXECUTOR,
                    new Destination(
                            "Цех настройки",
                            "/workshop-executor"
                    )
            ),

            Map.entry(
                    Role.SDP_ST_EXECUTOR,
                    new Destination(
                            "Цех испытаний",
                            "/workshop-executor"
                    )
            )
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

    /**
     * Принадлежит ли роль интерфейсу склада.
     *
     * Перечисление здесь, а не в шаблонах: боковое меню «Моих операций»
     * берётся у того модуля, в который роль входит по входу в систему, и
     * для кладовщика это склад, а не цех. Руководители складов в список не
     * входят намеренно — их модуль {@code /workshop}, и меню им нужно
     * цеховое, иначе в шапке оказался бы «Обзор склада» над списком
     * сотрудников подразделения.
     */
    public static boolean isWarehouse(Role role) {
        return role == Role.OMTSIK_EXECUTOR
                || role == Role.OMTSIK_С_EXECUTOR;
    }

    /**
     * Род склада для подписей меню и верхней панели.
     *
     * Формулировка одна на все интерфейсы: складские страницы подписывают
     * модуль этими словами, и «Мои операции» кладовщика не должны назвать
     * склад иначе, чем обзор склада.
     */
    public static String warehouseKind(Role role) {
        return role == Role.OMTSIK_С_EXECUTOR
                || role == Role.OMTSIK_С_MASTER
                ? "комплектования"
                : "полуфабрикатов";
    }
}
