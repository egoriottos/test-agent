package ru.bublikov.demo;

/** Функциональный интерфейс специально — удобно стабить лямбдой без Mockito, см. OrderServiceTest. */
public interface OrderRepository {
    double findAmount(String orderId);
}
