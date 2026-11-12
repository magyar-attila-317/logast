package com.example.shop.billing;

import org.springframework.data.repository.CrudRepository;

import java.util.List;

public interface InvoiceRepository extends CrudRepository<Invoice, String> {
    List<Invoice> findBySentFalse();
}
