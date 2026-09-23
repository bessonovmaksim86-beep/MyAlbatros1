(() => {
    'use strict';

    /*
     * Форма одна, а привязка бывает двух видов. Переключатель
     * показывает только нужный селект и очищает второй: иначе в
     * запрос ушли бы оба идентификатора, и сервер отклонил бы их
     * по правилу «либо операция, либо рабочее место».
     */
    function initializeBindingSwitch() {
        const form = document.getElementById('bindingForm');

        if (!form) {
            return;
        }

        const switcher = form.querySelector('[data-binding-switch]');
        const targets = Array.from(
            form.querySelectorAll('[data-binding-target]')
        );

        if (!switcher || targets.length === 0) {
            return;
        }

        function sync() {
            const selected = switcher.value;

            targets.forEach((target) => {
                const visible = target.dataset.bindingTarget === selected;
                const control = target.querySelector('select');

                target.hidden = !visible;

                if (!visible && control) {
                    control.value = '';
                }
            });
        }

        switcher.addEventListener('change', sync);

        form.addEventListener('submit', () => {
            targets.forEach((target) => {
                if (target.hidden) {
                    const control = target.querySelector('select');

                    if (control) {
                        control.value = '';
                    }
                }
            });
        });

        sync();
    }

    /*
     * Снятие привязки — действие, которое незаметно меняет план
     * работ, поэтому подтверждение запрашивается до отправки формы.
     */
    function initializeRemoveConfirmation() {
        document.querySelectorAll('[data-remove-binding]').forEach((button) => {
            button.addEventListener('click', (event) => {
                const employee = button.dataset.employee || 'сотрудника';
                const binding = button.dataset.binding || 'привязку';

                if (!window.confirm(
                    'Снять ' + binding + ' у сотрудника «' + employee + '»?'
                )) {
                    event.preventDefault();
                }
            });
        });
    }

    function initialize() {
        initializeBindingSwitch();
        initializeRemoveConfirmation();
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', initialize);
    } else {
        initialize();
    }
})();
