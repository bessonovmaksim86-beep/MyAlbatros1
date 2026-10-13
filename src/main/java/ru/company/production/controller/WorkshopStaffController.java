package ru.company.production.controller;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.company.production.dto.StaffMemberView;
import ru.company.production.dto.WorkshopBindingForm;
import ru.company.production.entity.AppUser;
import ru.company.production.entity.Operation;
import ru.company.production.entity.StaffBindingType;
import ru.company.production.entity.WorkPlace;
import ru.company.production.entity.WorkshopStaffBinding;
import ru.company.production.repository.UserRepository;
import ru.company.production.service.WorkshopStaffService;

/**
 * Руководитель цеха: сотрудники и их закрепление за операциями
 * и рабочими местами.
 *
 * Границы цеха (кто виден в списке, какие операции и рабочие места
 * доступны для выбора) устанавливает {@link WorkshopStaffService};
 * контроллер только передаёт в сервис учётную запись текущего
 * руководителя и превращает исключения сервиса в flash-сообщения —
 * как и в остальных разделах системы.
 */
@Controller
@RequestMapping("/workshop/staff")
@RequiredArgsConstructor
public class WorkshopStaffController {

    private final WorkshopStaffService staffService;
    private final UserRepository userRepository;

    /**
     * Список сотрудников цеха с привязками и форма закрепления.
     */
    @GetMapping({"", "/"})
    public String index(
            @ModelAttribute("bindingForm") WorkshopBindingForm bindingForm,
            Authentication authentication,
            Model model
    ) {
        AppUser supervisor = currentUser(authentication);

        /*
         * Граница — список подразделений, а не одно: руководитель службы
         * качества отвечает и за БТК, и за БВК, и выборка одного
         * подразделения показала бы ему половину его же сотрудников.
         */
        var scope = staffService.unitScope(supervisor);

        var staff = staffService.findStaff(scope);

        model.addAttribute("staff", staff);
        model.addAttribute(
                "bindingTotal",
                staff.stream().mapToInt(StaffMemberView::bindingCount).sum()
        );
        var operationGroups = staffService.findBindingOperationGroups(
                scope,
                staffService.unitKind(supervisor).getGenitive()
        );

        model.addAttribute("operationGroups", operationGroups);
        model.addAttribute(
                "operationTotal",
                operationGroups.stream()
                        .mapToInt(group -> group.operations().size())
                        .sum()
        );
        model.addAttribute(
                "workPlaces",
                staffService.findWorkshopWorkPlaces(scope)
        );
        model.addAttribute("bindingTypes", StaffBindingType.values());
        model.addAttribute("activePage", "staff");

        return "workshop/staff/index";
    }

    /**
     * Закрепление операции или рабочего места.
     *
     * После сохранения — redirect, чтобы повторный F5 не создавал
     * дубль привязки. Форма при этом остаётся пустой: закрепляют
     * обычно несколько сотрудников подряд, и чистая форма заполняется
     * быстрее заполненной.
     */
    @PostMapping("/bind")
    public String bind(
            @ModelAttribute("bindingForm") WorkshopBindingForm bindingForm,
            Authentication authentication,
            RedirectAttributes redirectAttributes
    ) {
        try {
            WorkshopStaffBinding binding = staffService.bind(
                    bindingForm.getEmployeeId(),
                    parseBindingType(bindingForm.getBindingType()),
                    bindingForm.getOperationId(),
                    bindingForm.getWorkPlaceId(),
                    currentUser(authentication)
            );

            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "За сотрудником закреплена " + describeTarget(binding)
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

        return "redirect:/workshop/staff";
    }

    /**
     * Снятие привязки. Удаляется запись закрепления, а не операция
     * и не рабочее место из справочника.
     */
    @PostMapping("/unbind")
    public String unbind(
            @RequestParam Long bindingId,
            Authentication authentication,
            RedirectAttributes redirectAttributes
    ) {
        try {
            staffService.unbind(bindingId, currentUser(authentication));

            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "Привязка снята"
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

        return "redirect:/workshop/staff";
    }

    /**
     * Переключатель формы решает, какое из двух полей заполнено,
     * поэтому пустое значение недопустимо: без вида привязки сервис
     * не знает, какой селект читать.
     */
    private static StaffBindingType parseBindingType(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        try {
            return StaffBindingType.valueOf(value.trim());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "Неизвестный вид привязки: " + value
            );
        }
    }

    /**
     * Наименование закреплённого объекта для flash-сообщения.
     * Код и наименование — скалярные поля самой операции и рабочего
     * места, они загружены в транзакции сервиса и читаются безопасно.
     */
    private static String describeTarget(WorkshopStaffBinding binding) {
        if (binding.getBindingType() == StaffBindingType.OPERATION) {
            Operation operation = binding.getOperation();

            return "операция «"
                    + (operation == null
                    || operation.getOperationName() == null
                    ? "—"
                    : operation.getOperationName().getName())
                    + "»";
        }

        WorkPlace place = binding.getWorkPlace();

        return "рабочее место «"
                + (place == null ? "—" : place.getCode())
                + "»";
    }

    /*
     * Руководитель нужен сервису вместе с подразделением и службой:
     * граница цеха определяется ими, а open-in-view отключён — связи
     * должны быть загружены в том же запросе.
     */
    private AppUser currentUser(Authentication authentication) {
        return userRepository
                .findWithUnitsByUsernameIgnoreCase(authentication.getName())
                .orElseThrow(() -> new IllegalStateException(
                        "Учётная запись руководителя не найдена"
                ));
    }
}
