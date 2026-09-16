package ru.company.production.controller;

import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.company.production.dto.OperationForm;

import ru.company.production.repository.*;
import ru.company.production.service.OperationService;


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
    public String index(Model model) {
        var operations = operationService.findAll();

        model.addAttribute("activePage", "operation");
        model.addAttribute("operations", operations);
        model.addAttribute("operationCount", operations.size());

        return "technology/operation/operation";
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
