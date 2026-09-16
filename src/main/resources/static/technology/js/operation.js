(() => {
    'use strict';

    /*
     * Фильтрация списка операций.
     */
    const searchInput =
        document.querySelector('#operationSearch');

    const typeFilter =
        document.querySelector('#operationTypeFilter');

    const rows = [
        ...document.querySelectorAll('.operation-row')
    ];

    const noResults =
        document.querySelector('#noResults');

    const filterOperations = () => {
        let visibleCount = 0;

        const searchValue = searchInput
            ? searchInput.value
                .trim()
                .toLocaleLowerCase('ru')
            : '';

        const typeValue = typeFilter
            ? typeFilter.value
            : '';

        rows.forEach(row => {
            const rowSearch =
                (row.dataset.search || '')
                    .toLocaleLowerCase('ru');

            const matchesSearch =
                !searchValue
                || rowSearch.includes(searchValue);

            const matchesType =
                !typeValue
                || row.dataset.typeId === typeValue;

            row.hidden = !(matchesSearch && matchesType);

            if (!row.hidden) {
                visibleCount++;
            }
        });

        if (noResults) {
            noResults.hidden = visibleCount !== 0;
        }
    };

    searchInput?.addEventListener(
        'input',
        filterOperations
    );

    typeFilter?.addEventListener(
        'change',
        filterOperations
    );

    /*
     * Последовательный выбор:
     *
     * 1. Тип исполнителя.
     * 2. Служба.
     * 3. Подразделение выбранной службы.
     */
    const executorRoleSelect =
        document.querySelector(
            '[data-executor-role-select]'
        );

    const serviceSelect =
        document.querySelector(
            '[data-service-select]'
        );

    const organizationUnitSelect =
        document.querySelector(
            '[data-organization-unit-select]'
        );

    const organizationUnitOptions =
        organizationUnitSelect
            ? [
                ...organizationUnitSelect.querySelectorAll(
                    'option[data-service-id]'
                )
            ]
            : [];

    const updateOrganizationUnits = (
        clearInvalidSelection
    ) => {
        if (!organizationUnitSelect) {
            return;
        }

        const selectedServiceId =
            serviceSelect?.value || '';

        organizationUnitSelect.disabled =
            !selectedServiceId;

        organizationUnitOptions.forEach(option => {
            const belongsToSelectedService =
                selectedServiceId
                && option.dataset.serviceId
                    === selectedServiceId;

            option.hidden =
                !belongsToSelectedService;

            option.disabled =
                !belongsToSelectedService;
        });

        if (!selectedServiceId) {
            organizationUnitSelect.value = '';
            return;
        }

        const selectedOption =
            organizationUnitSelect
                .selectedOptions[0];

        const selectedOptionIsValid =
            !organizationUnitSelect.value
            || (
                selectedOption
                && selectedOption.dataset.serviceId
                    === selectedServiceId
            );

        if (
            clearInvalidSelection
            && !selectedOptionIsValid
        ) {
            organizationUnitSelect.value = '';
        }
    };

    const updateServiceAvailability = (
        clearDependentFields
    ) => {
        if (!serviceSelect) {
            return;
        }

        const executorRoleSelected =
            Boolean(executorRoleSelect?.value);

        serviceSelect.disabled =
            !executorRoleSelected;

        if (
            !executorRoleSelected
            || clearDependentFields
        ) {
            serviceSelect.value = '';

            if (organizationUnitSelect) {
                organizationUnitSelect.value = '';
            }
        }

        updateOrganizationUnits(true);
    };

    executorRoleSelect?.addEventListener(
        'change',
        () => {
            updateServiceAvailability(true);
        }
    );

    serviceSelect?.addEventListener(
        'change',
        () => {
            updateOrganizationUnits(true);
        }
    );

    /*
     * Начальная настройка формы.
     *
     * При изменении операции сохраняются уже выбранные
     * роль, служба и подразделение.
     */
    if (
        executorRoleSelect
        && serviceSelect
    ) {
        serviceSelect.disabled =
            !executorRoleSelect.value;

        updateOrganizationUnits(false);
    }

    /*
     * Автоматическая отправка формы выбора операции.
     */
    document
        .querySelectorAll('[data-auto-submit]')
        .forEach(element => {
            element.addEventListener(
                'change',
                () => {
                    if (element.value) {
                        element.form.submit();
                    }
                }
            );
        });

    /*
     * Подтверждение удаления.
     * Форма используется и для операций, и для рабочих мест,
     * поэтому формулировка нейтральная.
     */
    const deleteForm =
        document.querySelector(
            '[data-delete-form]'
        );

    deleteForm?.addEventListener(
        'submit',
        event => {
            const confirmed = window.confirm(
                'Удалить выбранную запись? '
                + 'Это действие нельзя отменить.'
            );

            if (!confirmed) {
                event.preventDefault();
            }
        }
    );
})();