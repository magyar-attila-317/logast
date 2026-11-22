package com.example.shop.billing;

import org.springframework.data.repository.Repository;

import java.util.List;

public interface TaxRateRepository extends Repository<TaxRate, String> {
    List<TaxRate> findAll();
}
