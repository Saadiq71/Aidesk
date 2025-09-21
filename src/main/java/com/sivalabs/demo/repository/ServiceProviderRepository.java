package com.sivalabs.demo.repository;

import com.sivalabs.demo.model.*;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface ServiceProviderRepository extends MongoRepository<ServiceProvider, String> {
    Optional<ServiceProvider> findByEmail(String email);
    Optional<ServiceProvider> findByServiceName(String serviceName);

    Optional<ServiceProvider> findByServiceNameIgnoreCase(String predictedServiceName);
}