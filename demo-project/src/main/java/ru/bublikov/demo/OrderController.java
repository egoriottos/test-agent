package ru.bublikov.demo;

public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    public double getRefundAmount(String orderId) {
        return orderService.refundOrder(orderId);
    }
}
