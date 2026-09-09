package ru.company.production.service;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.company.production.dto.OperationForm;
import ru.company.production.entity.Department;
import ru.company.production.entity.Operation;
import ru.company.production.entity.OrganizationUnitType;
import ru.company.production.entity.Workshop;
import ru.company.production.repository.DepartmentRepository;
import ru.company.production.repository.OperationRepository;
import ru.company.production.repository.WorkshopRepository;

import java.util.List;

@Service
@RequiredArgsConstructor
public class OperationService {

    private final OperationRepository operationRepository;
    private final WorkshopRepository workshopRepository;
    private final DepartmentRepository departmentRepository;

    @Transactional
    public Operation create(OperationForm form) {
        if (form.getTargetType() == null || form.getTargetId() == null) {
            throw new IllegalArgumentException(
                    "Необходимо выбрать цех или отдел"
            );
        }

        Operation operation = new Operation();
        operation.setName(form.getName().trim());
        operation.setType(form.getType());
        operation.setActive(true);

        if (form.getTargetType() == OrganizationUnitType.WORKSHOP) {
            Workshop workshop = workshopRepository.findById(form.getTargetId())
                    .orElseThrow(() -> new EntityNotFoundException(
                            "Цех с идентификатором " + form.getTargetId()
                                    + " не найден"
                    ));

            if (!workshop.isActive()) {
                throw new IllegalArgumentException(
                        "Выбранный цех неактивен"
                );
            }

            operation.setWorkshop(workshop);
            operation.setDepartment(null);
        }

        if (form.getTargetType() == OrganizationUnitType.DEPARTMENT) {
            Department department = departmentRepository.findById(form.getTargetId())
                    .orElseThrow(() -> new EntityNotFoundException(
                            "Отдел с идентификатором " + form.getTargetId()
                                    + " не найден"
                    ));

            if (!department.isActive()) {
                throw new IllegalArgumentException(
                        "Выбранный отдел неактивен"
                );
            }

            operation.setDepartment(department);
            operation.setWorkshop(null);
        }

        return operationRepository.save(operation);
    }

    @Transactional(readOnly = true)
    public List<Operation> findAll() {
        return operationRepository.findAllByOrderByNameAsc();
    }
}