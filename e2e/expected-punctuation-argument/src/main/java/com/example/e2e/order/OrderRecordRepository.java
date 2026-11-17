package com.example.e2e.order;

import org.springframework.data.repository.CrudRepository;

public interface OrderRecordRepository extends CrudRepository<OrderRecord, String> {
}
