(() => {
    'use strict';

    /*
     * Выбор типа поставщика.
     *
     * Тип держат одним полем supplierChoice (значения CATALOG и CUSTOM),
     * а не двумя независимыми контролями: противоречивого состояния
     * «ссылка на справочник и текст разом» сервис не принимает, поэтому
     * форма его не показывает и переключает только видимое поле.
     *
     * Скрытое поле блокируют, а не только прячут — иначе введённое
     * наименование ушло бы в отправку вместе с выбранной ссылкой и
     * сервис отверг бы заказ.
     */
    function initializeSupplierChoice() {
        const form = document.getElementById('skOrderForm');

        if (!form) {
            return;
        }

        const radios = Array.from(
            form.querySelectorAll('input[name="supplierChoice"]')
        );

        const customField = document.getElementById('skCustomNameField');
        const catalogField = document.getElementById('skCatalogField');

        if (radios.length === 0 || !customField || !catalogField) {
            return;
        }

        const customInput = customField.querySelector('input[type="text"]');
        const catalogSelect = catalogField.querySelector('select');

        function selectedValue() {
            const checked = radios.find((radio) => radio.checked);

            return checked ? checked.value : 'CATALOG';
        }

        function render() {
            const custom = selectedValue() === 'CUSTOM';

            customField.hidden = !custom;
            catalogField.hidden = custom;

            if (customInput) {
                customInput.disabled = !custom;
            }

            if (catalogSelect) {
                catalogSelect.disabled = custom;
            }
        }

        radios.forEach((radio) => radio.addEventListener('change', render));

        render();
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', initializeSupplierChoice);
    } else {
        initializeSupplierChoice();
    }
})();