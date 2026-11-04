package com.flashmart.inventory.repository;

import com.flashmart.inventory.entity.Product;
import org.springframework.data.repository.Repository;

import java.util.Optional;

/** Finder only: {@code reserve} reads the product name for Order's receipt snapshot. */
public interface ProductRepository extends Repository<Product, Long> {

    Optional<Product> findById(Long id);
}
