package vn.techies.ecommerce.catalog.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.techies.ecommerce.catalog.domain.Category;

import java.util.List;
import java.util.UUID;

public interface CategoryRepository extends JpaRepository<Category, UUID> {

    List<Category> findAllByOrderByDisplayOrderAsc();
}
