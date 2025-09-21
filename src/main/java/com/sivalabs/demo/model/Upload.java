package com.sivalabs.demo.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("uploads")
public class Upload {
    @Id
    private String id;
    private String serviceName;
    private String serviceEmail;
    // fileName used for original filename or Cloudinary-sourced filename
    private String fileName;
    // link holds Cloudinary secure_url (if file uploaded)
    private String link;
    private String content;
    private long uploadTime;

    public Upload() {}

    public Upload(String serviceName, String serviceEmail, String fileName, String link, String content, long uploadTime) {
        this.serviceName = serviceName;
        this.serviceEmail = serviceEmail;
        this.fileName = fileName;
        this.link = link;
        this.content = content;
        this.uploadTime = uploadTime;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getServiceName() { return serviceName; }
    public void setServiceName(String serviceName) { this.serviceName = serviceName; }

    public String getServiceEmail() { return serviceEmail; }
    public void setServiceEmail(String serviceEmail) { this.serviceEmail = serviceEmail; }

    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }

    public String getLink() { return link; }
    public void setLink(String link) { this.link = link; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public long getUploadTime() { return uploadTime; }
    public void setUploadTime(long uploadTime) { this.uploadTime = uploadTime; }
}
