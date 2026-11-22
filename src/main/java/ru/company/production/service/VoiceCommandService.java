package ru.company.production.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.company.production.dto.SearchResultView;
import ru.company.production.entity.Role;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Разбор голосовых команд перехода («открой у меня …»).
 *
 * Команда отличается от обычного поиска намерением: человек просит не
 * найти строку в выдаче, а ОТКРЫТЬ объект в своём интерфейсе. Поэтому
 * ответ сервиса — не список, а один адрес раздела, и адрес выбирается
 * с учётом роли и того раздела, где пользователь стоит сейчас.
 *
 * Номера разбирает не этот класс: {@link TechnologySearchService} уже
 * отличает «ЗНП» от «ЗП», склеивает части номера через «%» и находит
 * серийники и приходы. Сервис берёт готовую выдачу и решает только
 * одно — в какой РАЗДЕЛ вести найденное, чтобы пользователь не упёрся
 * в 403.
 *
 * Права проверяются через {@link ModuleRoutes#canOpen(Role, String)} —
 * тот же справочник, что строит боковые меню и распределяет входы.
 * Новый раздел, открытый роли, становится доступен команде без правки
 * этого класса.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class VoiceCommandService {

    private final TechnologySearchService searchService;

    /**
     * Итог разбора команды.
     *
     * @param url   адрес раздела, открытый роли; null — прав нет
     * @param found найден объект поиска или нет
     * @param query нормализованный запрос (для fallback на поиск)
     */
    public record VoiceCommand(
            String url,
            boolean found,
            String query
    ) {

        /** Вести ли в раздел, или отдавать обычный поиск. */
        public boolean canRedirect() {
            return url != null;
        }
    }

    /**
     * Разбирает команду и подбирает адрес раздела.
     *
     * @param query   нормализованная фраза без слова-команды
     *                («ЗНП 123», «приход 45», «датчик давления»)
     * @param role    роль текущего пользователя
     * @param section префикс раздела, в котором пользователь сейчас
     *                работает (первый сегмент адреса страницы); может
     *                быть null. Нужен, чтобы «открой ордер 57»,
     *                произнесённое со склада, вело на склад, а не на
     *                диспетчерский отдел: один объект открывают
     *                несколько разделов, и выбор делает место работы,
     *                а не порядок в справочнике.
     */
    public VoiceCommand handle(String query, Role role, String section) {
        if (query == null || query.isBlank()) {
            return new VoiceCommand(null, false, "");
        }

        String term = query.trim();

        List<SearchResultView> results = searchService.search(term);

        if (results.isEmpty()) {
            /*
             * Объект не найден: пробуем прочитать фразу как НАЗВАНИЕ
             * раздела («открой входной контроль», «открой склад»).
             * Если это он и роль его открывает — ведём туда, иначе
             * обычная выдача поиска.
             */
            return new VoiceCommand(
                    menuUrl(term, role),
                    false,
                    term
            );
        }

        /*
         * Берётся первая строка выдачи: порядок в
         * TechnologySearchService уже выстроен так, что номер заказа
         * идёт выше перечня техпроцессов, — тот, кто диктует номер,
         * ищет именно заказ.
         */
        SearchResultView target = results.get(0);

        return new VoiceCommand(
                sectionUrl(target, role, section),
                true,
                term
        );
    }

    /*
     * Открыт ли адрес роли.
     *
     * Администратор обрабатывается здесь, а не в ModuleRoutes: тот
     * справочник отвечает за боковые меню и счётчики уведомлений, где
     * у админа СВОЙ модуль (/admin) и чужие разделы ему не перечисляются,
     * поэтому canOpen(ADMIN, «/warehouse/...») честное false. Голосовой
     * же ввод администратор диктует там, где работает, и право открыть
     * любой раздел у него есть по SecurityConfig, поэтому для него
     * открыто всё.
     *
     * Подмена роли (вход админа в чужой интерфейс) сюда не попадает:
     * в SecurityContext тогда уже целевой пользователь, и роль
     * приходит его — команда ведёт именно в тот интерфейс, где админ
     * сейчас работает.
     */
    private boolean isOpen(Role role, String url) {
        return role == Role.ADMIN || ModuleRoutes.canOpen(role, url);
    }

    /*
     * Адрес раздела для найденной строки.
     *
     * Одна категория живёт не в одном месте: серийник открывают и
     * склад, и входной контроль, и цех. Поэтому для категории задан
     * СПИСОК кандидатов, а не один адрес. Из них берётся первый
     * доступный роли, но порядок списка подстраивается под раздел,
     * где стоит пользователь: если роль работает на складе, складской
     * кандидат поднимается первым. Так выполняется правило «открывать
     * там, где сейчас работает человек», а не там, где раздел стоит
     * первым в справочнике.
     *
     * null возвращается, когда ни один кандидат роли не открыт:
     * вызывающий тогда уводит на обычный поиск (см. контроллер).
     */
    private String sectionUrl(
            SearchResultView result, Role role, String section) {

        if (result == null || result.getCategory() == null) {
            return null;
        }

        for (String candidate : orderByCurrentSection(
                candidatesFor(result), section)) {

            if (isOpen(role, candidate)) {
                return candidate;
            }
        }

        return null;
    }

    /*
     * Ставит кандидата текущего раздела на первое место.
     *
     * Раздел страницы и кандидат сравниваются по первому сегменту
     * адреса: страница «/warehouse/serial-search» и кандидат
     * «/warehouse/receiving» относятся к одному модулю «/warehouse»,
     * и для человека это одно и то же «моё». Порядок остальных
     * кандидатов сохраняется.
     */
    private List<String> orderByCurrentSection(
            List<String> candidates, String section) {

        String current = firstSegment(section);

        if (current == null || candidates.size() < 2) {
            return candidates;
        }

        List<String> ordered = new ArrayList<>(candidates.size());

        for (String candidate : candidates) {
            if (current.equals(firstSegment(candidate))) {
                ordered.add(candidate);
                break;
            }
        }

        for (String candidate : candidates) {
            if (!ordered.contains(candidate)) {
                ordered.add(candidate);
            }
        }

        return ordered;
    }

    /* Первый сегмент адреса: «/warehouse/receiving» → «warehouse». */
    private static String firstSegment(String url) {
        if (url == null) {
            return null;
        }

        String path = url.startsWith("/") ? url.substring(1) : url;
        int slash = path.indexOf('/');

        String segment = (slash >= 0 ? path.substring(0, slash) : path)
                .toLowerCase(Locale.ROOT);

        return segment.isEmpty() ? null : segment;
    }

    /*
     * Кандидаты раздела по категории результата.
     *
     * Где у категории есть карточка с собственным адресом, ведём на
     * неё: «открой ордер 123» значит «покажи мне заказ 123», а не
     * «покажи список заказов, найди его глазами». Карточку отдаёт
     * диспетчер (заказ покупателя и заказ на производство) и приёмка
     * ЗНП на складе.
     *
     * Где карточки нет, остаётся корневая страница раздела: сводку по
     * конкретному объекту поиск покажет и сам, а смысл команды —
     * перенести человека в ЕГО интерфейс, где он продолжит работу.
     */
    private List<String> candidatesFor(SearchResultView result) {

        String category = result.getCategory().toLowerCase(Locale.ROOT);
        Long id = result.getId();

        return switch (category) {
            /*
             * Заказы ведёт диспетчерский отдел: карточка, а не список.
             */
            case "customerorder" -> List.of(
                    "/dispatcher/customer-order/" + id,
                    "/dispatcher/customer-order"
            );

            /*
             * ЗНП открывают и диспетчер (карточка выпуска), и склад
             * (приёмка ЗНП): какой раздел возьмёт команду, решает
             * место работы — см. orderByCurrentSection.
             */
            case "productionorder" -> List.of(
                    "/dispatcher/production-order/" + id,
                    "/dispatcher/production-order",
                    "/warehouse/znp-acceptance/" + id,
                    "/warehouse/znp-acceptance"
            );

            /*
             * Серийник и приход — склад и бюро входного контроля.
             */
            case "serial", "receiving" -> List.of(
                    "/warehouse/serial-search",
                    "/warehouse/receiving",
                    "/input-control/general"
            );

            /*
             * Изделия, техпроцессы, операции и места ведёт техотдел.
             */
            case "classifier", "techprocess", "operation", "workplace" ->
                    List.of("/technology/general");

            default -> List.of();
        };
    }

    /*
     * Корневые адреса разделов по слову, которым их называют.
     *
     * Держится короткий список именно КОРНЕЙ модулей, а не пунктов
     * подменю: названия подменю живут в шаблонах и меняются вместе с
     * интерфейсом, а корень модуля — часть права доступа в
     * SecurityConfig и расходиться с ним не может. Совпадение — по
     * подстроке, поэтому «открой входной контроль», «открой bureau
     * входного контроля» и «открой склад» ведут в один и тот же
     * корень.
     *
     * Адрес отдаётся только если роль его открывает (canOpen): иначе
     * человек, не имеющий отношения к разделу, упёрся бы в 403, а
     * команда обязана либо вести в доступное место, либо не вести
     * вовсе.
     */
    private static final Map<String, String> SECTION_ROOTS = roots();

    private static Map<String, String> roots() {
        Map<String, String> roots = new LinkedHashMap<>();

        roots.put("входной контроль", "/input-control/general");
        roots.put("входного контроля", "/input-control/general");
        roots.put("технический контроль", "/technical-control/general");
        roots.put("технического контроля", "/technical-control/general");
        roots.put("склад", "/warehouse/general");
        roots.put("диспетчер", "/dispatcher/general");
        roots.put("технолог", "/technology/general");
        roots.put("качеств", "/quality/general");
        roots.put("админ", "/admin");

        return Map.copyOf(roots);
    }

    /*
     * Читает фразу как название раздела и проверяет доступ роли.
     * null — фраза не похожа на название раздела либо доступ закрыт.
     */
    private String menuUrl(String term, Role role) {
        String lower = term.toLowerCase(Locale.ROOT);

        for (Map.Entry<String, String> entry : SECTION_ROOTS.entrySet()) {
            if (lower.contains(entry.getKey())
                    && isOpen(role, entry.getValue())) {

                return entry.getValue();
            }
        }

        return null;
    }
}
