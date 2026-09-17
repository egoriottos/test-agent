package ru.bublikov.demo;

public class OrderService {

    private final OrderRepository orderRepository;

    public OrderService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    public double calculateTotal(double price, int quantity) {
        return price * quantity;
    }

    public double applyDiscount(double total) {
        // скидку недавно подняли с 10% до 15% — тест ниже ещё про старую логику
        return total * 0.85;
    }

    public void cancelOrder(String orderId) {
        System.out.println("Order cancelled: " + orderId);
    }

    public double refundOrder(String orderId) {
        double amount = orderRepository.findAmount(orderId);
        return amount * 0.9; // 10% комиссия за возврат
    }
}
