package ru.company.production.controller;

import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.company.production.dto.WorkPlaceForm;
import ru.company.production.entity.AppUser;
import ru.company.production.repository.UserRoleRepository;
import ru.company.production.repository.WorkPlaceTypeRepository;
import ru.company.production.repository.UserRepository;
import ru.company.production.service.WorkshopUnitOperationsService;

/**
 * Руководитель подразделения: операции и рабочие места своего цеха,
 * склада или бюро.
 *
 * Раздел нужен руководителям склада комплектации, склада полуфабрикатов
 * и бюро входного контроля: рабочее место он заводит сам, но перечень
 * службы и подразделения в форме зафиксирован учётной записью — см.
 * {@link WorkshopUnitOperationsService}, куда подставляются они, а не
 * значения из запроса.
 *
 * Операции здесь только просматриваются и удаляются: создаёт их
 * технолог в разделе «Справочники → Операции», поэтому справочник
 * операций у подразделения и технологического отдела общий, и план
 * производства, выпущенный там, видит те же операции.
 */
@Controller
@RequestMapping("/workshop/operations")
@RequiredArgsConstructor
public class WorkshopUnitOperationsController {

    private final WorkshopUnitOperationsService unitOperationsService;
    private final UserRepository userRepository;
    private final WorkPlaceTypeRepository workPlaceTypeRepository;
    private final UserRoleRepository roleUserRepository;

    /**
     * Список операций и рабочих мест подразделения с формой
     * добавления рабочего места.
     */
    @GetMapping({"", "/"})
    public String index(
            Authentication authentication,
            Model model
    ) {
        AppUser supervisor = currentUser(authentication);

        var operations = unitOperationsService.findOperations(supervisor);
        var workPlaces = unitOperationsService.findWorkPlaces(supervisor);

        model.addAttribute("operations", operations);
        model.addAttribute("unitWorkPlaces", workPlaces);
        model.addAttribute("operationCount", operations.size());
        model.addAttribute("workPlaceCount", workPlaces.size());

        /*
         * Форма добавления рабочего места всегда пустая: после
         * сохранения идёт redirect.
         */
        if (!model.containsAttribute("workPlaceForm")) {
            model.addAttribute(
                    "workPlaceForm",
                    unitOperationsService.newWorkPlaceForm(supervisor)
            );
        }

        addFormData(model);

        model.addAttribute("activePage", "operations");

        return "workshop/operations/index";
    }

    /**
     * Новое рабочее место подразделения.
     */
    @PostMapping("/work-place/create")
    public String createWorkPlace(
            @Valid @ModelAttribute("workPlaceForm") WorkPlaceForm workPlaceForm,
            BindingResult bindingResult,
            Authentication authentication,
            Model model,
            RedirectAttributes redirectAttributes
    ) {
        AppUser supervisor = currentUser(authentication);

        if (!bindingResult.hasErrors()) {
            try {
                unitOperationsService.createWorkPlace(
                        workPlaceForm,
                        supervisor
                );

                redirectAttributes.addFlashAttribute(
                        "successMessage",
                        "Рабочее место добавлено"
                );

                return "redirect:/workshop/operations";
            } catch (
                    IllegalArgumentException
                    | EntityNotFoundException
                    | IllegalStateException exception
            ) {
                redirectAttributes.addFlashAttribute(
                        "errorMessage",
                        exception.getMessage()
                );

                return "redirect:/workshop/operations";
            }
        }

        fillIndexModel(authentication, model);

        model.addAttribute("activePage", "operations");

        return "workshop/operations/index";
    }

    /**
     * Удаление операции. Занятая в маршрутах операция не удаляется —
     * об этом говорит исключение целостности схемы, и формулировка
     * объясняет, почему кнопка не сработала.
     */
    @PostMapping("/operation/delete")
    public String deleteOperation(
            @RequestParam Long operationId,
            Authentication authentication,
            RedirectAttributes redirectAttributes
    ) {
        try {
            unitOperationsService.deleteOperation(
                    operationId,
                    currentUser(authentication)
            );

            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "Операция удалена"
            );
        } catch (DataIntegrityViolationException exception) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "Операция используется в маршрутах или заказах "
                            + "и не может быть удалена"
            );
        } catch (
                IllegalArgumentException
                | EntityNotFoundException
                | IllegalStateException exception
        ) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    exception.getMessage()
            );
        }

        return "redirect:/workshop/operations";
    }

    /**
     * Удаление рабочего места.
     */
    @PostMapping("/work-place/delete")
    public String deleteWorkPlace(
            @RequestParam Long workPlaceId,
            Authentication authentication,
            RedirectAttributes redirectAttributes
    ) {
        try {
            unitOperationsService.deleteWorkPlace(
                    workPlaceId,
                    currentUser(authentication)
            );

            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "Рабочее место удалено"
            );
        } catch (DataIntegrityViolationException exception) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "Рабочее место используется в привязках или маршрутах "
                            + "и не может быть удалено"
            );
        } catch (
                IllegalArgumentException
                | EntityNotFoundException
                | IllegalStateException exception
        ) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    exception.getMessage()
            );
        }

        return "redirect:/workshop/operations";
    }

    /*
     * Пересборка модели после ошибки валидации: список и форма нужны
     * на странице целиком, иначе форма пропала бы вместе с уже
     * введёнными в неё значениями.
     */
    private void fillIndexModel(Authentication authentication, Model model) {
        AppUser supervisor = currentUser(authentication);

        var operations = unitOperationsService.findOperations(supervisor);
        var workPlaces = unitOperationsService.findWorkPlaces(supervisor);

        model.addAttribute("operations", operations);
        model.addAttribute("unitWorkPlaces", workPlaces);
        model.addAttribute("operationCount", operations.size());
        model.addAttribute("workPlaceCount", workPlaces.size());

        if (!model.containsAttribute("workPlaceForm")) {
            model.addAttribute(
                    "workPlaceForm",
                    unitOperationsService.newWorkPlaceForm(supervisor)
            );
        }

        addFormData(model);
    }

    /*
     * Справочники для селектов формы. Служба и подразделение в них не
     * выбираются — они зафиксированы учётной записью, поэтому списки
     * служб и подразделений в модель не кладутся вовсе.
     */
    private void addFormData(Model model) {
        model.addAttribute(
                "workPlaceTypes",
                workPlaceTypeRepository.findByActiveTrueOrderByNameAsc()
        );

        model.addAttribute(
                "executorRoles",
                roleUserRepository.findAll(
                        Sort.by(
                                Sort.Direction.ASC,
                                "displayName"
                        )
                )
        );
    }

    private AppUser currentUser(Authentication authentication) {
        return userRepository
                .findWithUnitsByUsernameIgnoreCase(authentication.getName())
                .orElseThrow(() -> new IllegalStateException(
                        "Учётная запись руководителя не найдена"
                ));
    }
}
