package ru.company.production.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import ru.company.production.web.ProductionOrderListInterceptor;

/**
 * Регистрация интерцептора списка заказов на производство.
 *
 * Паттерны перечислены точно, без wildcard'ов: у раздела на этом же
 * префиксе живут форма создания (/create), карточка (/{id}) и AJAX-
 * эндпоинты. Интерцептор и без того безвреден — он выходит, если в
 * модели нет строк списка, — но держать список адресов страницы
 * открытым дешевле, чем объяснять, почему обработка дошла до запроса,
 * который никакого списка не отдаёт.
 *
 * Отдельный класс-конфиг нужен ещё и потому, что других
 * WebMvcConfigurer в проекте нет: единственный существующий
 * конфигурационный слой (SecurityConfig) к MVC-обработчикам отношения
 * не имеет.
 */
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final ProductionOrderListInterceptor productionOrderListInterceptor;

    @Override
    public void addInterceptors(
            @NonNull InterceptorRegistry registry) {

        registry.addInterceptor(productionOrderListInterceptor)
                .addPathPatterns(
                        "/dispatcher/production-order",
                        "/dispatcher/production-orders"
                );
    }
}
