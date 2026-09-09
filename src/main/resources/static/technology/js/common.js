(() => {
    'use strict';

    function initializeInterface() {
        const dateTimeElement = document.getElementById(
            'technologyDateTime'
        );

        const mainElement = document.querySelector('.main');

        /*
         * Обновление текущей даты и времени.
         */
        function updateDateTime() {
            if (!dateTimeElement) {
                return;
            }

            const now = new Date();

            dateTimeElement.dateTime = now.toISOString();

            dateTimeElement.textContent =
                new Intl.DateTimeFormat('ru-RU', {
                    day: '2-digit',
                    month: '2-digit',
                    year: 'numeric',
                    hour: '2-digit',
                    minute: '2-digit',
                    second: '2-digit'
                }).format(now);
        }

        /*
         * Если меню было раскрыто с клавиатуры через focus-within,
         * щелчок по основному полю снимает фокус и сворачивает меню.
         *
         * При работе мышью меню сворачивается автоматически,
         * как только курсор покидает левую панель.
         */
        if (mainElement) {
            mainElement.addEventListener(
                'pointerdown',
                () => {
                    const activeElement =
                        document.activeElement;

                    const sidebar =
                        document.getElementById(
                            'technologySidebar'
                        );

                    if (
                        sidebar &&
                        activeElement &&
                        sidebar.contains(activeElement) &&
                        typeof activeElement.blur === 'function'
                    ) {
                        activeElement.blur();
                    }
                }
            );
        }

        updateDateTime();

        window.setInterval(
            updateDateTime,
            1000
        );
    }

    if (document.readyState === 'loading') {
        document.addEventListener(
            'DOMContentLoaded',
            initializeInterface
        );
    } else {
        initializeInterface();
    }
})();