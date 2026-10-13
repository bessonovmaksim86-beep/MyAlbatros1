package ru.company.production.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;
import ru.company.production.entity.AppUser;
import ru.company.production.entity.Role;
import ru.company.production.repository.UserRepository;
import ru.company.production.service.InputControlService;
import ru.company.production.service.QualityService;
import ru.company.production.service.WorkshopStaffService;

import java.util.List;
import java.util.Locale;

/**
 * Данные текущего руководителя службы качества для всех страниц
 * модуля: верхняя панель, счётчик незакрытых заказов и счётчик партий
 * с ячейками в изоляторе брака — в боковом меню.
 *
 * Счётчики нужны на каждой странице: заказ, по которому ячейки уже
 * закреплены, из разбора не исчезает, и видеть это нужно, не заходя в
 * реестр заказов. Счётчик изолятора берут у {@link InputControlService}:
 * изолятор заводят карточки входного контроля, и число партий в нём —
 * одна и та же цифра для бюро и для службы качества.
 *
 * Здесь же считаются сотрудники и операции службы: пункты «Сотрудники»,
 * «Операции и рабочие места» и «Производственный календарь» живут в
 * бордовом меню, а страницы этих разделов общие с цеховым модулем,
 * поэтому их счётчики должны быть видны и на главной службе, иначе
 * цифра исчезала бы при выходе из раздела.
 */
@ControllerAdvice(assignableTypes = QualityController.class)
@RequiredArgsConstructor
public class QualityViewAdvice {

    private final UserRepository userRepository;
    private final QualityService qualityService;
    private final InputControlService inputControlService;
    private final WorkshopStaffService staffService;

    @ModelAttribute
    public void addQualityContext(
            Authentication authentication,
            Model model
    ) {
        model.addAttribute("userDisplayName", "Пользователь");
        model.addAttribute("userPosition", "Должность не указана");

        /*
         * Признаки пунктов цехового профиля проставляются и до проверки
         * аутентификации: меню обращается к ним в th:if, и неопределённый
         * атрибут обернулся бы ошибкой Thymeleaf на анонимном запросе.
         */
        model.addAttribute("canViewUnit", false);
        model.addAttribute("fullModule", false);
        model.addAttribute("staffCount", 0);

        model.addAttribute(
                "openOrderCount",
                qualityService.countOpenOrders()
        );

        model.addAttribute(
                "quarantineCount",
                inputControlService.countQuarantine()
        );

        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return;
        }

        /*
         * Граница сотрудников и операций строится по службе, поэтому
         * учётная запись читается с подразделением и службой: без
         * EntityGraph lazy-связи вне транзакции не раскрылись бы
         * (open-in-view отключён).
         */
        AppUser user = userRepository
                .findWithUnitsByUsernameIgnoreCase(authentication.getName())
                .orElse(null);

        if (user == null) {
            return;
        }

        model.addAttribute("currentUser", user);

        model.addAttribute(
                "userDisplayName",
                abbreviatedName(user.getFullName(), user.getUsername())
        );

        String specialty = user.getSpecialty();

        model.addAttribute(
                "userPosition",
                specialty == null || specialty.isBlank()
                        ? "Должность не указана"
                        : specialty
        );

        /*
         * Разделы «Сотрудники», «Операции и рабочие места» и календарь
         * общие с цеховым модулем, но рисуют цеховую обёртку: для
         * руководителя службы качества её подменяют бордовой, иначе
         * зелёное меню службы появилось бы над цеховыми стилями.
         *
         * Счётчики считаются здесь, а не в WorkshopViewAdvice: тот к
         * контроллеру службы не подключён, и без этого расчёта цифра
         * сотрудников исчезала бы на главной службе, оставаясь только
         * внутри самого раздела.
         */
        boolean canViewUnit = staffService.canViewUnit(user);
        List<Long> scope = canViewUnit
                ? staffService.unitScope(user)
                : List.of();

        model.addAttribute("canViewUnit", canViewUnit);
        model.addAttribute("fullModule", staffService.hasFullModule(user));
        model.addAttribute(
                "staffCount",
                canViewUnit ? staffService.findEmployees(scope).size() : 0
        );

        model.addAttribute(
                "isSKGeneral",
                user.getRole() == Role.SK_GENERAL
        );
    }

    /**
     * Преобразует "Иванов Иван Иванович" в "Иванов И.".
     */
    private String abbreviatedName(String fullName, String username) {
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