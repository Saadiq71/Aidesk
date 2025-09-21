package com.sivalabs.demo.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document("tickets")
public class Ticket {
    @Id
    private String id;
    private String ticketId;
    private String userEmail;
    private String serviceEmail;
    private String serviceName;
    private String question;
    private String answer;
    private String status; // OPEN, COMPLETED
    private String description; // 👈 new field for user’s problem
    private long createdAt;
    private long updatedAt;

    public Ticket() {}

    public Ticket(String ticketId, String userEmail, String serviceEmail, String serviceName, String question) {
        this.ticketId = ticketId;
        this.userEmail = userEmail;
        this.serviceEmail = serviceEmail;
        this.serviceName = serviceName;
        this.question = question;
        this.status = "OPEN";
        this.createdAt = Instant.now().toEpochMilli();
        this.updatedAt = this.createdAt;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTicketId() { return ticketId; }
    public void setTicketId(String ticketId) { this.ticketId = ticketId; }

    public String getUserEmail() { return userEmail; }
    public void setUserEmail(String userEmail) { this.userEmail = userEmail; }

    public String getServiceEmail() { return serviceEmail; }
    public void setServiceEmail(String serviceEmail) { this.serviceEmail = serviceEmail; }

    public String getServiceName() { return serviceName; }
    public void setServiceName(String serviceName) { this.serviceName = serviceName; }

    public String getQuestion() { return question; }
    public void setQuestion(String question) { this.question = question; }

    public String getAnswer() { return answer; }
    public void setAnswer(String answer) { this.answer = answer; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }

    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }
}
