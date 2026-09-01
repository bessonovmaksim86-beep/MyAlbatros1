package ru.company.production.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.company.production.dto.CreateOperationForm;
import ru.company.production.entity.Operation;
import ru.company.production.repository.OperationRepository;

import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class AdminOperationService {

    private final OperationRepository operationRepository;

    @Transactional(readOnly = true)
    public List<Operation> findAll() {
        return operationRepository.findAll();
    }

    @Transactional
    public void create(CreateOperationForm form) {
        String code = form.getCode()
                .trim()
                .toUpperCase(Locale.ROOT);

        if (operationRepository.existsByCodeIgnoreCase(code)) {
            throw new IllegalArgumentException(
                    "Операция с таким кодом уже существует"
            );
        }

        Operation operation = new Operation();
        operation.setCode(code);
        operation.setName(form.getName().trim());
        operation.setDescription(normalizeDescription(form.getDescription()));
        operation.setActive(true);

        operationRepository.save(operation);
    }

    @Transactional
    public void delete(Long id) {
        Operation operation = operationRepository.findById(id)
                .orElseThrow(() ->
                        new IllegalArgumentException("Операция не найдена"));

        operationRepository.delete(operation);
    }

    private String normalizeDescription(String description) {
        if (description == null || description.isBlank()) {
            return null;
        }

        return description.trim();
    }
}