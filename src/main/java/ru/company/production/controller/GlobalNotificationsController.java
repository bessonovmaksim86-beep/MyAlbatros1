package ru.company.production.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.company.production.entity.AppUser;
import ru.company.production.repository.UserRepository;
import ru.company.production.service.GlobalNotificationService;

import java.util.List;

/**
 * Счётчики незакрытых дел всех модулей в JSON — для всплывающих
 * уведомлений.
 *
 * Боковое меню печатает счётчики серверной отрисовкой, но сотрудник
 * сидит на одной странице и не видит, что за это время пришла новая
 * работа. Клиент (js/notifications.js) опрашивает этот адрес раз в
 * ~30 секунд, сравнивает числа со снимком в {@code localStorage} и на
 * прирост показывает строку вида «Новое «Приёмка с БВК» — 1» в правом
 * нижнем углу со звуком и переходом в раздел.
 *
 * Граница — учётная запись обратившегося: перечень возвращается только
 * по тем разделам, которые ей открыты, поэтому адрес не требует своей
 * роли в {@code SecurityConfig} и достаточно общего
 * {@code anyRequest().authenticated()}.
 *
 * Адрес лежит вне префиксов разделов (/dispatcher, /warehouse, …)
 * намеренно: уведомление нужно всем интерфейсам сразу, а правило
 * одного модуля отсекло бы остальные.
 */
@RestController
@RequestMapping("/notifications")
@RequiredArgsConstructor
public class GlobalNotificationsController {

    private final UserRepository userRepository;
    private final GlobalNotificationService notificationService;

    /** Счётчики разделов, открытых обратившемуся сотруднику. */
    @GetMapping("/active")
    public List<GlobalNotificationService.CounterItem> active(
            Authentication authentication
    ) {
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return List.of();
        }

        /*
         * Подразделения читаются через EntityGraph: open-in-view
         * выключен, а очередь распределения считается по границе
         * подразделений — ленивая связь у detached-объекта оборвалась бы
         * LazyInitializationException на каждом опросе.
         */
        AppUser user = userRepository
                .findWithUnitsByUsernameIgnoreCase(authentication.getName())
                .orElse(null);

        return notificationService.collectActive(user);
    }
}
