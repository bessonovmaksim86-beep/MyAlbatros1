
    /**
     * Комплектовочные операции выбранного серийника.
     *
     * Список зависит от изделия серийника, а серийник меняют уже в
     * карточке выпуска — поэтому операции отдаются отдельно и только
     * для выбранного номера.
     */
    @GetMapping("/production-order/serial-kitting-operations")
    @ResponseBody
    public List<Map<String, Object>> serialKittingOperations(
            @RequestParam(required = false) Long serialId
    ) {
        List<Map<String, Object>> rows = new ArrayList<>();

        int position = 1;

        for (Operation operation
                : productionOrderService
                        .findSerialKittingOperations(serialId)) {

            Map<String, Object> row = new LinkedHashMap<>();

            row.put("id", operation.getId());
            row.put(
                    "name",
                    operation.getOperationName() == null
                            ? ""
                            : operation.getOperationName().getName()
            );
            row.put("position", position++);

            rows.add(row);
        }

        return rows;
    }

    /**
     * Создаёт заказы-комплектующие по отмеченным парам
     * «серийник + операция» внутри выпуска.
     */
    @PostMapping("/production-order/{id}/kitting-orders")
    @ResponseBody
    public Map<String, Object> createKittingOrders(
            @PathVariable Long id,
            @RequestBody List<Map<String, Object>> pairs,
            Authentication authentication
    ) {
        AppUser author = authentication == null
                ? null
                : userRepository.findByUsernameIgnoreCase(
                        authentication.getName()
                ).orElse(null);

        Map<String, Object> response = new LinkedHashMap<>();

        try {
            List<ProductionOrder> created =
                    productionOrderService.createKittingOrders(
                            id, pairs, author);

            List<Map<String, Object>> rows = new ArrayList<>();

            for (ProductionOrder order : created) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", order.getId());
                row.put("number", order.getNumber());
                rows.add(row);
            }

            response.put("ok", true);
            response.put("created", rows);
        } catch (
                EntityNotFoundException
                | IllegalArgumentException
                | IllegalStateException exception
        ) {
            response.put("ok", false);
            response.put("error", exception.getMessage());
        }

        return response;
    }
