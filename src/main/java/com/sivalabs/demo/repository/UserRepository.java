package com.sivalabs.demo.repository;

import com.sivalabs.demo.model.*;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;
public interface UserRepository extends MongoRepository<User, String> {
    Optional<User> findByEmail(String email);
}