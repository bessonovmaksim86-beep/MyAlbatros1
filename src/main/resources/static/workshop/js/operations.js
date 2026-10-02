(() => {
    'use strict';

    /*
     * Удаление операции или рабочего места меняет и закрепления
     * сотрудников: связь удаляется вместе со справочной позицией.
     * Поэтому подтверждение запрашивается до отправки формы — как при
     * снятии привязки в разделе «Сотрудники».
     */
    function initializeRemoveConfirmation() {
        document.querySelectorAll('[data-remove-unit-item]')
            .forEach((button) => {
                button.addEventListener('click', (event) => {
                    const item = button.dataset.removeTitle || 'позицию';

                    if (!window.confirm('Удалить ' + item + '?')) {
                        event.preventDefault();
                    }
                });
            });
    }

    if (document.readyState === 'loading') {
        document.addEventListener(
            'DOMContentLoaded',
            initializeRemoveConfirmation
        );
    } else {
        initializeRemoveConfirmation();
    }
})();
