package ru.company.production.controller;

import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.company.production.dto.OperationForm;
import ru.company.production.dto.TypeCardView;
import ru.company.production.dto.TypeCards;
import ru.company.production.entity.Operation;
import ru.company.production.entity.OperationType;
import ru.company.production.repository.OperationTypeRepository;
import ru.company.production.repository.OrganizationUnitRepository;
import ru.company.production.repository.ProductionServiceRepository;
import ru.company.production.repository.UserRoleRepository;
import ru.company.production.service.OperationService;

import java.util.ArrayList;
import java.util.List;


@Controller
@RequestMapping("/technology/operation")
@RequiredArgsConstructor
public class OperationController {

    private final OperationService operationService;
    private final OperationTypeRepository operationTypeRepository;
    private final UserRoleRepository roleUserRepository;
    private final OrganizationUnitRepository organizationUnitRepository;
    private final ProductionServiceRepository productionServiceRepository;


    @GetMapping
    public String index(
            @RequestParam(
                    name = "type",
                    required = false
            )
            String type,
            Model model
    ) {
        List<Operation> all = operationService.findAll();

        Long activeTypeId = parseTypeId(type);

        model.addAttribute("activePage", "operation");

        model.addAttribute(
                "operations",
                activeTypeId == null
                        ? all
                        : all.stream()
                            .filter(operation -> operation
                                    .getOperationType()
                                    .getId()
                                    .equals(activeTypeId))
                            .toList()
        );

        model.addAttribute("operationCount", all.size());
        model.addAttribute(
                "activeType",
                activeTypeId == null
                        ? null
                        : String.valueOf(activeTypeId)
        );
        model.addAttribute(
                "activeTypeName",
                activeTypeId == null
                        ? null
                        : all.stream()
                            .map(Operation::getOperationType)
                            .filter(candidate ->
                                    candidate.getId().equals(activeTypeId))
                            .map(OperationType::getName)
                            .findFirst()
                            .orElse(null)
        );
        model.addAttribute(
                "operationCards",
                buildOperationCards(all)
        );

        return "technology/operation/operation";
    }

    private Long parseTypeId(String type) {
        if (type == null || type.isBlank()) {
            return null;
        }

        try {
            return Long.valueOf(type.trim());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    /**
     * Карточки активных типов операций с количеством операций
     * каждого типа.
     */
    private List<TypeCardView> buildOperationCards(
            List<Operation> operations
    ) {
        List<TypeCardView> cards = new ArrayList<>();

        List<OperationType> types =
                operationTypeRepository
                        .findAllByActiveTrueOrderByNameAsc();

        for (int index = 0; index < types.size(); index++) {
            OperationType type = types.get(index);

            long count = operations.stream()
                    .filter(operation -> operation
                            .getOperationType()
                            .getId()
                            .equals(type.getId()))
                    .count();

            cards.add(new TypeCardView(
                    String.valueOf(type.getId()),
                    type.getName(),
                    count,
                    TypeCards.icon(type.getName()),
                    TypeCards.modifier(index)
            ));
        }

        return cards;
    }

    @GetMapping("/create")
    public String createPage(Model model) {
        model.addAttribute("activePage", "operation");
        model.addAttribute("operationForm", new OperationForm());

        addOperationFormData(model);

        return "technology/operation/create";
    }

    @PostMapping("/create")
    public String create(
            @Valid
            @ModelAttribute("operationForm")
            OperationForm operationForm,
            BindingResult bindingResult,
            Model model,
            RedirectAttributes redirectAttributes
    ) {
        if (!bindingResult.hasErrors()) {
            try {
                operationService.create(operationForm);
            } catch (
                    IllegalArgumentException |
                    EntityNotFoundException exception
            ) {
                bindingResult.reject(
                        "operation.create.error",
                        exception.getMessage()
                );
            }
        }

        if (bindingResult.hasErrors()) {
            model.addAttribute("activePage", "operation");
            addOperationFormData(model);

            return "technology/operation/create";
        }

        redirectAttributes.addFlashAttribute(
                "successMessage",
                "Операция успешно создана"
        );

        return "redirect:/technology/operation";
    }

    @GetMapping("/update")
    public String updatePage(
            @RequestParam(
                    name = "id",
                    required = false
            )
            Long id,
            Model model,
            RedirectAttributes redirectAttributes
    ) {
        model.addAttribute("activePage", "operation");
        model.addAttribute(
                "operations",
                operationService.findAll()
        );

        if (id != null) {
            try {
                model.addAttribute(
                        "selectedOperation",
                        operationService.get(id)
                );

                model.addAttribute(
                        "operationForm",
                        operationService.getForm(id)
                );
            } catch (
                    EntityNotFoundException |
                    IllegalArgumentException exception
            ) {
                redirectAttributes.addFlashAttribute(
                        "errorMessage",
                        exception.getMessage()
                );

                return "redirect:/technology/operation/update";
            }
        }

        if (!model.containsAttribute("operationForm")) {
            model.addAttribute(
                    "operationForm",
                    new OperationForm()
            );
        }

        addOperationFormData(model);

        return "technology/operation/update";
    }

    @GetMapping("/delete")
    public String deletePage(
            @RequestParam(
                    name = "id",
                    required = false
            )
            Long id,
            Model model,
            RedirectAttributes redirectAttributes
    ) {
        model.addAttribute("activePage", "operation");
        model.addAttribute(
                "operations",
                operationService.findAll()
        );

        if (id != null) {
            try {
                model.addAttribute(
                        "selectedOperation",
                        operationService.get(id)
                );
            } catch (
                    EntityNotFoundException |
                    IllegalArgumentException exception
            ) {
                redirectAttributes.addFlashAttribute(
                        "errorMessage",
                        exception.getMessage()
                );

                return "redirect:/technology/operation/delete";
            }
        }

        return "technology/operation/delete";
    }

    @PostMapping("/{id}/delete")
    public String delete(
            @PathVariable Long id,
            RedirectAttributes redirectAttributes
    ) {
        try {
            operationService.delete(id);

            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "Операция успешно удалена"
            );
        } catch (DataIntegrityViolationException exception) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "Операция используется в других данных "
                            + "и не может быть удалена"
            );
        } catch (
                EntityNotFoundException |
                IllegalArgumentException exception
        ) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    exception.getMessage()
            );
        }

        return "redirect:/technology/operation";
    }

    @PostMapping("/{id}/update")
    public String update(
            @PathVariable Long id,
            @Valid
            @ModelAttribute("operationForm")
            OperationForm operationForm,
            BindingResult bindingResult,
            Model model,
            RedirectAttributes redirectAttributes
    ) {
        if (!bindingResult.hasErrors()) {
            try {
                operationService.update(id, operationForm);
            } catch (
                    IllegalArgumentException |
                    EntityNotFoundException exception
            ) {
                bindingResult.reject(
                        "operation.update.error",
                        exception.getMessage()
                );
            }
        }

        if (bindingResult.hasErrors()) {
            model.addAttribute("activePage", "operation");

            try {
                model.addAttribute(
                        "selectedOperation",
                        operationService.get(id)
                );
            } catch (
                    EntityNotFoundException |
                    IllegalArgumentException exception
            ) {
                redirectAttributes.addFlashAttribute(
                        "errorMessage",
                        exception.getMessage()
                );

                return "redirect:/technology/operation/update";
            }

            model.addAttribute(
                    "operations",
                    operationService.findAll()
            );

            addOperationFormData(model);

            return "technology/operation/update";
        }

        redirectAttributes.addFlashAttribute(
                "successMessage",
                "Операция успешно изменена"
        );

        return "redirect:/technology/operation/update?id=" + id;
    }

    private void addOperationFormData(Model model) {
        model.addAttribute(
                "operationTypes",
                operationTypeRepository.findAll(
                        Sort.by(
                                Sort.Direction.ASC,
                                "name"
                        )
                )
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

        model.addAttribute(
                "services",
                productionServiceRepository.findAll(
                        Sort.by(
                                Sort.Direction.ASC,
                                "name"
                        )
                )
        );

        model.addAttribute(
                "organizationUnits",
                organizationUnitRepository
                        .findAllByOrderByNameAsc()
        );
    }
}
