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
import ru.company.production.dto.WorkPlaceForm;
import ru.company.production.dto.TypeCardView;
import ru.company.production.dto.TypeCards;
import ru.company.production.entity.WorkPlace;
import ru.company.production.entity.WorkPlaceType;
import ru.company.production.repository.OrganizationUnitRepository;
import ru.company.production.repository.ProductionServiceRepository;
import ru.company.production.repository.UserRoleRepository;
import ru.company.production.repository.WorkPlaceTypeRepository;
import ru.company.production.service.WorkPlaceService;

import java.util.ArrayList;
import java.util.List;

@Controller
@RequestMapping("/technology/workplace")
@RequiredArgsConstructor
public class WorkPlaceController {

    private final WorkPlaceService workPlaceService;
    private final WorkPlaceTypeRepository workPlaceTypeRepository;
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
        List<WorkPlace> all = workPlaceService.findAll();

        Long activeTypeId = parseTypeId(type);

        model.addAttribute("activePage", "workplace");

        model.addAttribute(
                "workPlaces",
                activeTypeId == null
                        ? all
                        : all.stream()
                            .filter(place -> place
                                    .getWorkPlaceType()
                                    .getId()
                                    .equals(activeTypeId))
                            .toList()
        );

        model.addAttribute("workPlaceCount", all.size());
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
                            .map(WorkPlace::getWorkPlaceType)
                            .filter(candidate ->
                                    candidate.getId().equals(activeTypeId))
                            .map(WorkPlaceType::getName)
                            .findFirst()
                            .orElse(null)
        );
        model.addAttribute(
                "workPlaceCards",
                buildWorkPlaceCards(all)
        );

        return "technology/workplace/workplace";
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
     * Карточки активных типов рабочих мест с количеством
     * рабочих мест каждого типа.
     */
    private List<TypeCardView> buildWorkPlaceCards(
            List<WorkPlace> places
    ) {
        List<TypeCardView> cards = new ArrayList<>();

        List<WorkPlaceType> types =
                workPlaceTypeRepository
                        .findByActiveTrueOrderByNameAsc();

        for (int index = 0; index < types.size(); index++) {
            WorkPlaceType type = types.get(index);

            long count = places.stream()
                    .filter(place -> place
                            .getWorkPlaceType()
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
        model.addAttribute("activePage", "workplace");
        model.addAttribute("workPlaceForm", new WorkPlaceForm());

        addWorkPlaceFormData(model);

        return "technology/workplace/create";
    }

    @PostMapping("/create")
    public String create(
            @Valid
            @ModelAttribute("workPlaceForm")
            WorkPlaceForm workPlaceForm,
            BindingResult bindingResult,
            Model model,
            RedirectAttributes redirectAttributes
    ) {
        if (!bindingResult.hasErrors()) {
            try {
                workPlaceService.create(workPlaceForm);
            } catch (
                    IllegalArgumentException |
                    EntityNotFoundException exception
            ) {
                bindingResult.reject(
                        "workplace.create.error",
                        exception.getMessage()
                );
            }
        }

        if (bindingResult.hasErrors()) {
            model.addAttribute("activePage", "workplace");
            addWorkPlaceFormData(model);

            return "technology/workplace/create";
        }

        redirectAttributes.addFlashAttribute(
                "successMessage",
                "Рабочее место успешно создано"
        );

        return "redirect:/technology/workplace";
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
        model.addAttribute("activePage", "workplace");
        model.addAttribute(
                "workPlaces",
                workPlaceService.findAll()
        );

        if (id != null) {
            try {
                model.addAttribute(
                        "selectedWorkPlace",
                        workPlaceService.get(id)
                );

                model.addAttribute(
                        "workPlaceForm",
                        workPlaceService.getForm(id)
                );
            } catch (
                    EntityNotFoundException |
                    IllegalArgumentException exception
            ) {
                redirectAttributes.addFlashAttribute(
                        "errorMessage",
                        exception.getMessage()
                );

                return "redirect:/technology/workplace/update";
            }
        }

        if (!model.containsAttribute("workPlaceForm")) {
            model.addAttribute(
                    "workPlaceForm",
                    new WorkPlaceForm()
            );
        }

        addWorkPlaceFormData(model);

        return "technology/workplace/update";
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
        model.addAttribute("activePage", "workplace");
        model.addAttribute(
                "workPlaces",
                workPlaceService.findAll()
        );

        if (id != null) {
            try {
                model.addAttribute(
                        "selectedWorkPlace",
                        workPlaceService.get(id)
                );
            } catch (
                    EntityNotFoundException |
                    IllegalArgumentException exception
            ) {
                redirectAttributes.addFlashAttribute(
                        "errorMessage",
                        exception.getMessage()
                );

                return "redirect:/technology/workplace/delete";
            }
        }

        return "technology/workplace/delete";
    }

    @PostMapping("/{id}/delete")
    public String delete(
            @PathVariable Long id,
            RedirectAttributes redirectAttributes
    ) {
        try {
            workPlaceService.delete(id);

            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "Рабочее место успешно удалено"
            );
        } catch (DataIntegrityViolationException exception) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "Рабочее место используется в других данных "
                            + "и не может быть удалено"
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

        return "redirect:/technology/workplace";
    }

    @PostMapping("/{id}/update")
    public String update(
            @PathVariable Long id,
            @Valid
            @ModelAttribute("workPlaceForm")
            WorkPlaceForm workPlaceForm,
            BindingResult bindingResult,
            Model model,
            RedirectAttributes redirectAttributes
    ) {
        if (!bindingResult.hasErrors()) {
            try {
                workPlaceService.update(id, workPlaceForm);
            } catch (
                    IllegalArgumentException |
                    EntityNotFoundException exception
            ) {
                bindingResult.reject(
                        "workplace.update.error",
                        exception.getMessage()
                );
            }
        }

        if (bindingResult.hasErrors()) {
            model.addAttribute("activePage", "workplace");

            try {
                model.addAttribute(
                        "selectedWorkPlace",
                        workPlaceService.get(id)
                );
            } catch (
                    EntityNotFoundException |
                    IllegalArgumentException exception
            ) {
                redirectAttributes.addFlashAttribute(
                        "errorMessage",
                        exception.getMessage()
                );

                return "redirect:/technology/workplace/update";
            }

            model.addAttribute(
                    "workPlaces",
                    workPlaceService.findAll()
            );

            addWorkPlaceFormData(model);

            return "technology/workplace/update";
        }

        redirectAttributes.addFlashAttribute(
                "successMessage",
                "Рабочее место успешно изменено"
        );

        return "redirect:/technology/workplace/update?id=" + id;
    }

    private void addWorkPlaceFormData(Model model) {
        model.addAttribute(
                "workPlaceTypes",
                workPlaceTypeRepository.findAll(
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
