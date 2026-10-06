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

import java.util.List;
import java.util.Map;

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

        modelAndView.addObject(ROWS, enriched.rows());
        modelAndView.addObject(SERIALS, enriched.serialsByOrder());
        modelAndView.addObject(RELEASES, enriched.releases());
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