package ru.company.production.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;
import ru.company.production.entity.AppUser;
import ru.company.production.entity.ZnpKind;
import ru.company.production.repository.ProductionOrderRepository;
import ru.company.production.repository.UserRepository;
import ru.company.production.service.CustomerService;
import ru.company.production.service.OrderPriorityService;

import java.util.Locale;

/**
 * Данные текущего пользователя для всех страниц
 * диспетчерского отдела (верхняя панель).
 */
@ControllerAdvice(
        assignableTypes = {
                DispatcherController.class,
                DispatcherPriorityController.class
        }
)
@RequiredArgsConstructor
public class DispatcherViewAdvice {

    private final UserRepository userRepository;
    private final CustomerService customerService;
    private final OrderPriorityService orderPriorityService;
    private final ProductionOrderRepository productionOrderRepository;

    @ModelAttribute
    public void addCurrentUser(
            Authentication authentication,
            Model model
    ) {
        setDefaultUserAttributes(model);

        /*
         * Счётчик действующих заказчиков нужен боковому меню
         * на всех страницах отдела, а не только на справочнике.
         */
        model.addAttribute("customerCount", customerService.countActive());

        /*
         * Число действующих приоритетов — тот же случай: раздел
         * «Приоритеты» присутствует в меню постоянно.
         */
        model.addAttribute(
                "priorityCount",
                orderPriorityService.countActive()
        );

        /*
         * Счётчики двух разделов заказов — тоже для меню целиком, а не
         * только для самих страниц: пункт «Групповые ЗНП» виден и на
         * заказах покупателя, и на обзоре.
         *
         * Считаются по наборам видов (ZnpKind.productionOrderKinds() и
         * groupKinds()), а не по всей таблице: общий счётчик
         * (countByActiveTrue) показал бы в обоих пунктах одно и то же
         * число, хотя разделы теперь не пересекаются.
         *
         * Проставляются здесь, а не в обработчиках страниц: иначе
         * каждый раздел отдела обращался бы к базе за ними повторно.
         */
        model.addAttribute(
                "productionOrderCount",
                productionOrderRepository.countActiveByKinds(
                        ZnpKind.productionOrderKinds()
                )
        );

        model.addAttribute(
                "groupZnpCount",
                productionOrderRepository.countActiveByKinds(
                        ZnpKind.groupKinds()
                )
        );

        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication
                instanceof AnonymousAuthenticationToken) {
            return;
        }

        AppUser user = userRepository
                .findByUsernameIgnoreCase(authentication.getName())
                .orElse(null);

        if (user == null) {
            return;
        }

        model.addAttribute("currentUser", user);

        model.addAttribute(
                "userDisplayName",
                abbreviatedName(
                        user.getFullName(),
                        user.getUsername()
                )
        );

        String specialty = user.getSpecialty();

        model.addAttribute(
                "userPosition",
                specialty == null || specialty.isBlank()
                        ? "Должность не указана"
                        : specialty
        );
    }

    private void setDefaultUserAttributes(Model model) {
        model.addAttribute("userDisplayName", "Пользователь");
        model.addAttribute(
                "userPosition",
                "Должность не указана"
        );
    }

    /**
     * Преобразует "Иванов Иван Иванович"
     * в "Иванов И.".
     */
    private String abbreviatedName(
            String fullName,
            String username
    ) {
        if (fullName == null || fullName.isBlank()) {
            return username == null || username.isBlank()
                    ? "Пользователь"
                    : username;
        }

        String[] parts = fullName.trim().split("\\s+");

        if (parts.length == 1) {
            return parts[0];
        }

        return parts[0]
                + " "
                + parts[1].substring(0, 1)
                .toUpperCase(Locale.forLanguageTag("ru"))
                + ".";
    }
}