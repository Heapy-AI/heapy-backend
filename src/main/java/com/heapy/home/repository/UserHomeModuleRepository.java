package com.heapy.home.repository;

import com.heapy.home.domain.UserHomeModule;
import com.heapy.home.domain.UserHomeModuleId;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserHomeModuleRepository extends JpaRepository<UserHomeModule, UserHomeModuleId> {

    List<UserHomeModule> findByUserIdOrderByDisplayOrder(UUID userId);

    boolean existsByUserId(UUID userId);
}
