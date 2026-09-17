package ru.bublikov.demo;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OrderServiceTest {

    // OrderRepository — функциональный интерфейс, стабим лямбдой без Mockito
    private final OrderRepository orderRepository = orderId -> 100.0;
    private final OrderService service = new OrderService(orderRepository);

    @Test
    void calculateTotal_multipliesPriceByQuantity() {
        assertEquals(300.0, service.calculateTotal(100.0, 3));
    }

    @Test
    void applyDiscount_appliesTenPercent() {
        // тест устарел: скидка была 10%, теперь 15% (см. OrderService.applyDiscount)
        assertEquals(90.0, service.applyDiscount(100.0));
    }
}
