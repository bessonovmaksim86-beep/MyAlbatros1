package ru.company.production.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;
import ru.company.production.entity.ProductType;
import ru.company.production.entity.ProductionOrderStatus;
import ru.company.production.repository.ProductionOrderListItemView;
import ru.company.production.service.ProductionOrderListEnrichmentService;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Дозаполняет список заказов на производство серийниками и фильтрами.
 *
 * Работает ПОСЛЕ контроллера (postHandle), поэтому контроллер «Заказов
 * на производство» остаётся нетронутым: он кладёт в модель строки через
 * productionOrderRepository.findListItemsByKinds(...), а здесь они
 * читаются из модели, фильтруются и заменяются отфильтрованным списком.
 * Тот же вызов отдаёт колонку «Серийник(и)» и выпуски для селекта
 * фильтра.
 *
 * Почему интерцептор, а не правка контроллера: DispatcherController
 * обслуживает весь отдел диспетчера, и любая его правка обязывает
 * перезаписывать файл целиком с риском молча потерять чужой рабочий код.
 * Задача списка при этом полностью изолирована — ей нужны только
 * атрибуты модели, которые контроллер уже положил.
 *
 * Обработка ничего не делает, если ожидаемого атрибута в модели нет:
 * страница могла вернуться и без строк (например, это редирект после
 * сохранения), и вмешиваться тогда не во что.
 */
@Component
@RequiredArgsConstructor
public class ProductionOrderListInterceptor implements HandlerInterceptor {

    /* Атрибуты, которыми список и шаблон уже договорились. */
    private static final String ROWS = "productionOrders";
    private static final String SERIALS = "orderSerials";
    private static final String RELEASES = "rootOrders";

    /*
     * Узлы дерева для клиентской разметки. Атрибут отдаётся списком
     * Map, а не проекцией: Jackson сериализует прокси Spring Data не
     * гарантированно, а обычный Map из скаляров превращается в JSON
     * всегда одинаково.
     */
    private static final String TREE = "znpTree";

    private final ProductionOrderListEnrichmentService enrichmentService;

    @Override
    public void postHandle(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull Object handler,
            ModelAndView modelAndView
    ) {

        if (modelAndView == null) {
            return;
        }

        Map<String, Object> model = modelAndView.getModel();

        if (!(model.get(ROWS) instanceof List<?> rowsAtAll)) {
            return;
        }

        @SuppressWarnings("unchecked")
        List<ProductionOrderListItemView> rows =
                (List<ProductionOrderListItemView>) rowsAtAll;

        /*
         * Значения читаются из параметров запроса, а не из формы:
         * фильтры отправляются GET'ом на тот же адрес, поэтому выбор
         * живёт в адресной строке и переживает перезагрузку.
         */
        ProductionOrderListEnrichmentService.Enriched enriched =
                enrichmentService.enrich(
                        rows,
                        productType(request),
                        status(request),
                        releaseOrderId(request)
                );

        List<Map<String, Object>> tree =
                treeOf(enriched.rows(), enriched.serialsByOrder());

        modelAndView.addObject(ROWS, enriched.rows());
        modelAndView.addObject(SERIALS, enriched.serialsByOrder());
        modelAndView.addObject(RELEASES, enriched.releases());
        modelAndView.addObject(TREE, tree);

        /*
         * Верх диапазона ползунка берётся из уже посчитанных глубин:
         * дерево строится одним проходом, и второй обход тех же связей
         * только плодил бы код.
         */
        modelAndView.addObject("treeMaxDepth", maxDepthOf(tree));
    }

    private static int maxDepthOf(List<Map<String, Object>> tree) {

        int max = 0;

        for (Map<String, Object> node : tree) {

            Object depth = node.get("depth");

            if (depth instanceof Integer integer && integer > max) {
                max = integer;
            }
        }

        return max;
    }

    /*
     * Плоский перечень узлов дерева.
     *
     * Связь родитель → ребёнок держится ПО НОМЕРУ заказа, а не по
     * идентификатору: проекция строки (ProductionOrderListItemView)
     * отдаёт parentOrderNumber, но не id родителя — тот же разбор, что
     * у фильтра по выпуску в сервисе обогащения.
     *
     * Порядок строк сохранён как в списке, иерархию из него строит
     * скрипт: так фильтр по статусу или выпуску, убравший родителя, не
     * «теряет» детей — сироты остаются видимыми корнями.
     */
    private List<Map<String, Object>> treeOf(
            List<ProductionOrderListItemView> rows,
            Map<Long, String> serialsByOrder) {

        if (rows == null || rows.isEmpty()) {
            return List.of();
        }

        /*
         * Строим Map номер → узел, чтобы потом быстро найти детей.
         */
        List<Map<String, Object>> nodes = new ArrayList<>();
        Map<String, Map<String, Object>> byNumber = new LinkedHashMap<>();

        for (ProductionOrderListItemView row : rows) {

            Map<String, Object> node = new LinkedHashMap<>();

            node.put("id", row.getId());
            node.put("number", row.getNumber());
            node.put("parent", blankToNull(row.getParentOrderNumber()));
            node.put("kind", row.getKindColumn());
            node.put("component", row.isComponent());
            node.put("serials", serialsOf(serialsByOrder, row.getId()));
            node.put("orderDate", row.getFormattedOrderDate());
            node.put("customerOrderNumber", row.getCustomerOrderNumber());
            node.put("itemCount", row.getItemCount());
            node.put("doneProgress", row.getDoneProgress());
            node.put("dueDate", row.getFormattedDueDate());
            node.put("statusName", row.getStatusDisplayName());
            node.put("statusClass", row.getStatusBadgeClass());
            node.put("statusCode", row.getStatus() == null
                    ? null
                    : row.getStatus().name());
            node.put("childCount", row.getChildCount());
            node.put("planAllowed", row.isPlanAllowed());
            node.put("planBlockReason", row.getPlanBlockReason());
            node.put("deleteConfirmText", row.getDeleteConfirmText());

            nodes.add(node);
            byNumber.put(row.getNumber(), node);
        }

        /*
         * Глубина и признак «есть дети».

         * Глубину считает рекурсивный подъём по цепочке родителей, а не
         * чтение depth у родителя: порядок строк в списке задан датой и
         * номером, поэтому родитель обязан стоять раньше ребёнка.
         */
        Set<String> parents = new HashSet<>();

        for (Map<String, Object> node : nodes) {

            String parent = (String) node.get("parent");

            if (parent != null) {
                parents.add(parent);
            }
        }

        for (Map<String, Object> node : nodes) {

            node.put("depth", depthOf((String) node.get("parent"), byNumber));
            node.put("hasChildren", parents.contains(node.get("number")));
        }

        return orderDepthFirst(nodes, parents);
    }

    /*
     * Обход в глубину: родитель идёт сразу перед своими детьми.
     *
     * Порядок исходного списка (дата, затем номер) ставит заказчика
     * рядом с его выпуском произвольно, а дерево читают сверху вниз:
     * строка ребёнка выше строки родителя выглядела бы ошибкой
     * вложенности.
     *
     * Корнями считают узлы без родителя И узлы, чей родителя в списке
     * нет, — последний отсеян фильтром или снят с активности. Иначе
     * такая ветвь исчезла бы из выдачи целиком: обход идёт от корней,
     * и без этого допущения отфильтрованный список показал бы пустую
     * таблицу вместо детей.
     */
    private List<Map<String, Object>> orderDepthFirst(
            List<Map<String, Object>> nodes,
            Set<String> parentNumbers) {

        Map<String, List<Map<String, Object>>> childrenOf =
                new LinkedHashMap<>();

        for (Map<String, Object> node : nodes) {

            String parent = (String) node.get("parent");

            childrenOf
                    .computeIfAbsent(parent, key -> new ArrayList<>())
                    .add(node);
        }

        List<Map<String, Object>> ordered = new ArrayList<>(nodes.size());
        Set<String> visited = new HashSet<>();
        Deque<Map<String, Object>> stack = new ArrayDeque<>();

        for (Map<String, Object> node : nodes) {

            String parent = (String) node.get("parent");

            if (parent == null || !parentNumbers.contains(parent)) {
                stack.push(node);
            }
        }

        while (!stack.isEmpty()) {

            Map<String, Object> node = stack.pop();
            String number = (String) node.get("number");

            /* Цикл «родитель ↔ ребёнок» не должен зациклить обход. */
            if (!visited.add(number)) {
                continue;
            }

            ordered.add(node);

            List<Map<String, Object>> children =
                    childrenOf.getOrDefault(number, List.of());

            /*
             * Дети кладутся в стек в обратном порядке: стек разворачивает
             * последним положенный, и в выдаче они остаются в порядке
             * исходного списка.
             */
            for (int i = children.size() - 1; i >= 0; i--) {
                stack.push(children.get(i));
            }
        }

        /*
         * Узлы, не дошедшие обходом (замкнутый цикл ссылок), дописываются
         * в конце: список не имеет права потерять заказ.
         */
        for (Map<String, Object> node : nodes) {

            if (!visited.contains((String) node.get("number"))) {
                ordered.add(node);
            }
        }

        return ordered;
    }

    /*
     * Глубина узла по номеру его родителя: 0 у заказа без родителя.
     *
     * Родитель, отсутствующий в списке (снят с активности или отсеян
     * фильтром), глубину не увеличивает — узел становится корнем.
     * Множество посещённых номеров держит обход на случай цикла в
     * ссылках «родитель ↔ ребёнок».
     */
    private int depthOf(String parentNumber,
                        Map<String, Map<String, Object>> byNumber) {

        int depth = 0;
        Set<String> visited = new HashSet<>();

        while (parentNumber != null && visited.add(parentNumber)) {

            Map<String, Object> parent = byNumber.get(parentNumber);

            if (parent == null) {
                break;
            }

            depth++;
            parentNumber = (String) parent.get("parent");
        }

        return depth;
    }

    private String serialsOf(Map<Long, String> serialsByOrder, Long orderId) {

        if (serialsByOrder == null || orderId == null) {
            return "—";
        }

        String serials = serialsByOrder.get(orderId);

        return serials == null || serials.isBlank() ? "—" : serials;
    }

    private String blankToNull(String value) {

        if (value == null || value.isBlank() || "—".equals(value)) {
            return null;
        }

        return value;
    }

    /*
     * Разбор параметров с защитой от мусора в адресной строке:
     * неизвестное значение трактуется как «фильтр не выбран». Страница
     * списка не должна падать на набранном вручную URL — иначе
     * пользователь теряет весь список из-за одной буквы.
     */
    private ProductType productType(HttpServletRequest request) {
        return parse(
                request.getParameter("productType"),
                ProductType.class
        );
    }

    private ProductionOrderStatus status(HttpServletRequest request) {
        return parse(
                request.getParameter("status"),
                ProductionOrderStatus.class
        );
    }

    private Long releaseOrderId(HttpServletRequest request) {

        String value = trimToNull(
                request.getParameter("releaseOrderId"));

        if (value == null) {
            return null;
        }

        try {
            return Long.valueOf(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private <E extends Enum<E>> E parse(
            String value,
            Class<E> type) {

        String text = trimToNull(value);

        if (text == null) {
            return null;
        }

        try {
            return Enum.valueOf(type, text);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private String trimToNull(String value) {

        if (value == null) {
            return null;
        }

        String text = value.trim();

        return text.isEmpty() ? null : text;
    }
}