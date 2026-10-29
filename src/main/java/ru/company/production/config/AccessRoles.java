package ru.company.production.config;

import ru.company.production.entity.Role;

/**
 * Наборы ролей, которым открыт раздел.
 *
 * <p>Раньше правила перечисляли роли строками. Это два отдельных риска.
 *
 * <p><b>Строка не проверяется компилятором.</b> Опечатка в имени не ломает
 * сборку: {@code hasRole("SDP_SPP_MASTR")} просто никогда не совпадёт, и
 * роль молча теряет раздел. Симптом далёкий от причины — «у мастера
 * пропала страница», а ошибка живёт в другом файле.
 *
 * <p><b>Часть имён содержит кириллицу.</b> {@link Role#OMTSIK_С_MASTER} и
 * {@link Role#OMTSIK_С_EXECUTOR} названы буквой «С» (U+0421), а не
 * латинской «C» (U+0043). В строковом литерале эти символы неразличимы
 * глазами, сравнение в Spring Security точное, и роль с «не той» буквой в
 * списке доступа не получает — выглядит это как «правило есть, но оно не
 * работает».
 *
 * <p>Через {@code Role.*.name()} расхождение между enum и правилом
 * становится ошибкой компиляции. Имя ролей в правах остаётся строкой
 * только на выходе: Spring Security прибавляет к нему префикс {@code ROLE_}
 * и сравнивает со authorities, которые выдаёт
 * {@code SecurityConfig#userDetailsService()}.
 *
 * <p>Обоснование состава каждого набора — в {@code docs/security-access.md}
 * (разделы 4–10). Правильный порядок правил внутри фильтра важнее их
 * содержания: применяется первое совпадение.
 */
final class AccessRoles {

    /** Мастера цехов СДП: подготовка производства, сборка, настройка, испытания. */
    static final String[] SDP_WORKSHOP_MASTERS = {
            Role.SDP_SPP_MASTER.name(),
            Role.SDP_SA_MASTER.name(),
            Role.SDP_SC_MASTER.name(),
            Role.SDP_ST_MASTER.name()
    };

    /** Руководители складов: полуфабрикатов и комплектации. */
    static final String[] WAREHOUSE_HEADS = {
            Role.OMTSIK_MASTER.name(),
            Role.OMTSIK_С_MASTER.name(),
            Role.SGP_MASTER.name()
    };

    /**
     * Руководители подразделений цехового модуля: цеха, склады, бюро
     * контроля, отдел метрологии. Границы внутри модуля проходит
     * подразделение учётной записи, а не роль.
     */
    static final String[] UNIT_MANAGERS = concat(
            SDP_WORKSHOP_MASTERS,
            WAREHOUSE_HEADS,
            Role.SGM_MASTER.name(),
            Role.BVK_MASTER.name(),
            Role.BTK_MASTER.name());

    /**
     * Сотрудники, операции и производственный календарь подразделения.
     *
     * <p>Это {@link #UNIT_MANAGERS} плюс руководитель службы качества:
     * отдельное правило перед общим {@code /workshop/**} существует
     * именно ради него (docs/security-access.md, раздел 5). Наборы
     * разведены, потому что полный цеховой модуль SK_GENERAL по-прежнему
     * закрыт, и склейка в один список открыла бы ему {@code /workshop/**}
     * целиком.
     */
    static final String[] UNIT_SECTIONS = withExtra(
            UNIT_MANAGERS, Role.SK_GENERAL.name());

    /**
     * Роли, которым распределение работ поручено по службе: мастера
     * цехов и складов, руководители служб и диспетчерского отдела,
     * руководитель службы качества.
     *
     * <p>Руководителей БВК и БТК здесь нет намеренно — в бюро строку
     * берут из перечня подразделения, а не закрепляют за человеком
     * (см. WorkshopDistributionService#isUnitPool).
     */
    static final String[] DISTRIBUTION_MANAGERS = concat(
            SDP_WORKSHOP_MASTERS,
            WAREHOUSE_HEADS,
            Role.SGM_MASTER.name(),
            Role.SK_GENERAL.name(),
            Role.SDP_SERVICE_HEAD_TECHNOLOGY.name(),
            Role.SDP_SERVICE_HEAD_DISPATCHER.name(),
            Role.DISPATCHER.name());

    /** Кладовщики: полуфабрикатов и комплектования. */
    static final String[] WAREHOUSE_EXECUTORS = {
            Role.OMTSIK_EXECUTOR.name(),
            Role.OMTSIK_С_EXECUTOR.name()
    };

    /** Сотрудники бюро входного контроля: исполнитель и руководитель. */
    static final String[] BVK_STAFF = {
            Role.BVK_EXECUTOR.name(),
            Role.BVK_MASTER.name()
    };

    /** Контролёры, ведущие заявки на отбраковку: БВК и БТК, обе роли. */
    static final String[] DEFECT_REQUEST_STAFF = {
            Role.BVK_EXECUTOR.name(),
            Role.BVK_MASTER.name(),
            Role.BTK_EXECUTOR.name(),
            Role.BTK_MASTER.name()
    };

    private AccessRoles() {
    }

    /**
     * Склейка наборов: списки ролей в правилах пересекаются, и отдельный
     * набор на каждое правило означал бы, что новую роль цеха надо
     * дописывать в четыре места.
     */
    private static String[] concat(String[] first, String[] rest) {
        String[] result = new String[first.length + rest.length];
        System.arraycopy(first, 0, result, 0, first.length);
        System.arraycopy(rest, 0, result, first.length, rest.length);
        return result;
    }

    /**
     * Набор плюс одна роль отдельно: иначе единственное отличие двух
     * соседних правил пришлось бы описывать копированием всего списка,
     * и новую роль цеха дописывали бы в оба.
     */
    private static String[] withExtra(String[] base, String extra) {
        return concat(base, new String[]{extra});
    }

    private static String[] concat(
            String[] first,
            String[] second,
            String... singles
    ) {
        String[] result = concat(first, second);
        String[] extended = new String[result.length + singles.length];
        System.arraycopy(result, 0, extended, 0, result.length);
        System.arraycopy(singles, 0, extended, result.length, singles.length);
        return extended;
    }
}
