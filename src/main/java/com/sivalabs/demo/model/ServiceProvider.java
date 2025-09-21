package com.sivalabs.demo.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("serviceProviders")
public class ServiceProvider {
    @Id
    private String id;
    private String email;
    private String password;
    private String serviceName;

    public ServiceProvider() {}

    public ServiceProvider(String email, String password, String serviceName) {
        this.email = email;
        this.password = password;
        this.serviceName = serviceName;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public String getServiceName() { return serviceName; }
    public void setServiceName(String serviceName) { this.serviceName = serviceName; }
}
