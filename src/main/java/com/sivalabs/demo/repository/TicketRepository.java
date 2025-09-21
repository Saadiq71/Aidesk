package com.sivalabs.demo.repository;

import com.sivalabs.demo.model.*;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface TicketRepository extends MongoRepository<Ticket, String> {
    Optional<Ticket> findByTicketId(String ticketId);
    List<Ticket> findByUserEmail(String userEmail);
    List<Ticket> findByServiceEmail(String serviceEmail);
}