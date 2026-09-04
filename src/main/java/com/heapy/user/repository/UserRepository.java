package com.heapy.user.repository;

import com.heapy.user.domain.User;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, UUID> {
}
