package ru.company.production.config;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.company.production.entity.OrganizationUnit;
import ru.company.production.entity.OrganizationUnitType;
import ru.company.production.entity.ProductionService;
import ru.company.production.repository.OrganizationUnitRepository;
import ru.company.production.repository.ProductionServiceRepository;

@Component
@RequiredArgsConstructor
public class OrganizationUnitInitializer
        implements CommandLineRunner {

    private final OrganizationUnitRepository unitRepository;
    private final ProductionServiceRepository serviceRepository;

    @Override
    @Transactional
    public void run(String... args) {
        ProductionService sdp = getOrCreateService(
                "СДП",
                "Служба подготовки производства"
        );

        ProductionService sk = getOrCreateService(
                "СК",
                "Служба контроля"
        );

        ProductionService skd = getOrCreateService(
                "СКД",
                "Служба комплектации и документации"
        );

        getOrCreateService(
                "СГМ",
                "Служба главного механика"
        );

        createIfNotExists(
                sdp,
                "SDP-ASSEMBLY",
                "Цех сборки"
        );

        createIfNotExists(
                sdp,
                "SDP-PREPARATION",
                "Цех подготовки производства"
        );

        createIfNotExists(
                sdp,
                "SDP-SETTING",
                "Цех настройки"
        );

        createIfNotExists(
                sdp,
                "SDP-TESTING",
                "Цех испытаний"
        );

        createIfNotExists(
                sdp,
                "SDP-DISPATCH",
                "Диспетчерский отдел"
        );

        createIfNotExists(
                sdp,
                "SDP-TECH",
                "Технологический отдел"
        );

        createIfNotExists(
                sk,
                "SK-QUALITY",
                "Бюро технического контроля"
        );

        createIfNotExists(
                skd,
                "SKD-FINISHED",
                "Склад готовой продукции"
        );

        /*
         * СГМ не содержит подразделений.
         */
    }

    private ProductionService getOrCreateService(
            String code,
            String name
    ) {
        return serviceRepository
                .findByCodeIgnoreCase(code)
                .orElseGet(() -> {
                    ProductionService service =
                            new ProductionService();

                    service.setCode(code);
                    service.setName(name);
                    service.setActive(true);

                    return serviceRepository.save(service);
                });
    }

    private void createIfNotExists(
            ProductionService service,
            String code,
            String name
    ) {
        if (unitRepository.existsByCodeIgnoreCase(code)) {
            return;
        }
        if (!unitRepository.existsByServiceAndName(service, name)) {
            OrganizationUnit unit = new OrganizationUnit();

            unit.setCode(code);
            unit.setName(name);
            unit.setService(service);
            unit.setActive(true);
            unit.setType(OrganizationUnitType.WORKSHOP);
            unitRepository.save(unit);
        }
    }
}