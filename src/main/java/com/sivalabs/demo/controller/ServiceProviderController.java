package com.sivalabs.demo.controller;

import com.sivalabs.demo.model.ServiceProvider;
import com.sivalabs.demo.model.Ticket;
import com.sivalabs.demo.model.Upload;
import com.sivalabs.demo.repository.ServiceProviderRepository;
import com.sivalabs.demo.repository.TicketRepository;
import com.sivalabs.demo.repository.UploadRepository;
import com.sivalabs.demo.util.OCRHelper;
import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import io.github.cdimascio.dotenv.Dotenv;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.json.JSONObject;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.http.ResponseEntity;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.*;

@RestController
@RequestMapping("/api/providers")
@CrossOrigin(origins = "*")
public class ServiceProviderController {

    private final ServiceProviderRepository serviceProviderRepository;
    private final TicketRepository ticketRepository;
    private final UploadRepository uploadRepository;
    private final VectorStore vectorStore;
    private final OCRHelper ocrHelper;
    private final JavaMailSender mailSender;

    // Cloudinary + OCR config
    private final Cloudinary cloudinary;
    private final String OCR_API_KEY;
    private static final String OCR_API_URL = "https://api.ocr.space/parse/image";
    private static final long MAX_FILE_SIZE = 4 * 1024 * 1024; // 4MB

    public ServiceProviderController(ServiceProviderRepository serviceProviderRepository,
                                     TicketRepository ticketRepository,
                                     UploadRepository uploadRepository,
                                     VectorStore vectorStore,
                                     OCRHelper ocrHelper,
                                     JavaMailSender mailSender) {
        this.serviceProviderRepository = serviceProviderRepository;
        this.ticketRepository = ticketRepository;
        this.uploadRepository = uploadRepository;
        this.vectorStore = vectorStore;
        this.ocrHelper = ocrHelper;
        this.mailSender = mailSender;

        // Initialize Cloudinary and OCR API key from environment (Dotenv)
        try {
            Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();
            String cloudinaryUrl = dotenv.get("CLOUDINARY_URL");
            this.OCR_API_KEY = dotenv.get("OCR_SPACE_KEY");

            if (cloudinaryUrl == null) {
                throw new IllegalStateException("Missing CLOUDINARY_URL environment variable");
            }
            this.cloudinary = new Cloudinary(cloudinaryUrl);
        } catch (Exception e) {
            // If initialization fails, rethrow so bean creation fails fast
            throw new RuntimeException("Failed to initialize Cloudinary/OCR configuration", e);
        }
    }

    // --------------------------
    // REGISTER PROVIDER
    // --------------------------
    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestParam String email,
                                      @RequestParam String serviceName,
                                      @RequestParam String password) {
        if (email == null || serviceName == null || password == null) {
            return ResponseEntity.badRequest().body("email, serviceName and password are required");
        }
        if (serviceProviderRepository.findByEmail(email).isPresent()) {
            return ResponseEntity.status(409).body("Service provider already exists");
        }

        ServiceProvider sp = new ServiceProvider();
        sp.setEmail(email);
        sp.setServiceName(serviceName);
        sp.setPassword(password);

        ServiceProvider saved = serviceProviderRepository.save(sp);
        return ResponseEntity.ok(saved);
    }

    // --------------------------
    // UPLOAD FAQ (TEXT or IMAGE) + Cloudinary + OCR
    // --------------------------
    @PostMapping("/upload")
    public ResponseEntity<?> upload(@RequestParam String email,
                                    @RequestParam(required = false) String text,
                                    @RequestParam(required = false) MultipartFile file) {
        Optional<ServiceProvider> spOpt = serviceProviderRepository.findByEmail(email);
        if (spOpt.isEmpty()) {
            return ResponseEntity.status(404).body("Service provider not found");
        }
        ServiceProvider sp = spOpt.get();

        String content = null;
        String cloudinaryUrl = null;
        File tempFile = null;
        try {
            if (text != null && !text.isBlank()) {
                content = text;
            } else if (file != null) {
                // validate file size
                if (file.getSize() > MAX_FILE_SIZE) {
                    return ResponseEntity.badRequest().body("Max file size: 4MB");
                }

                // Convert to temp file
                tempFile = convertToFile(file);
                String publicId = System.currentTimeMillis() + "_" + file.getOriginalFilename();

                @SuppressWarnings("unchecked")
                Map<String, Object> uploadResult = cloudinary.uploader().upload(
                        tempFile,
                        ObjectUtils.asMap(
                                "resource_type", "auto",
                                "public_id", publicId
                        )
                );

                cloudinaryUrl = uploadResult.get("secure_url").toString();

                // Try to extract text via OCR API by URL
                try {
                    content = extractTextFromUrl(cloudinaryUrl);
                    // fallback to OCRHelper if available and returns something
                    if ((content == null || content.isBlank()) && ocrHelper != null) {
                        content = ocrHelper.extractText(file);
                    }
                } catch (Exception ocrEx) {
                    // fallback to OCRHelper if network OCR fails
                    try {
                        if (ocrHelper != null) {
                            content = ocrHelper.extractText(file);
                        }
                    } catch (Exception helperEx) {
                        // ignore: content may remain null
                    }
                }
            }
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body("Failed to process upload: " + e.getMessage());
        } finally {
            // cleanup temp file
            if (tempFile != null && tempFile.exists()) {
                boolean deleted = tempFile.delete();
                if (!deleted) {
                    System.err.println("Failed to delete temp file: " + tempFile.getAbsolutePath());
                }
            }
        }

        if (content == null || content.isBlank()) {
            return ResponseEntity.badRequest().body("No valid text found in upload");
        }

        Upload upload = new Upload();
        upload.setServiceEmail(sp.getEmail());
        upload.setServiceName(sp.getServiceName());
        upload.setContent(content);
        upload.setUploadTime(System.currentTimeMillis());
        if (cloudinaryUrl != null) {
            upload.setLink(cloudinaryUrl);
        }

        // If file was provided, store filename (if available)
        if (upload.getFileName() == null && cloudinaryUrl != null) {
            String[] parts = cloudinaryUrl.split("/");
            upload.setFileName(parts[parts.length - 1]);
        }

        Upload saved;
        try {
            saved = uploadRepository.save(upload);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body("Failed to save upload: " + e.getMessage());
        }

        Document doc = new Document(content, Map.of(
                "serviceEmail", sp.getEmail(),
                "serviceName", sp.getServiceName(),
                "uploadId", String.valueOf(saved.getId())
        ));
        try {
            vectorStore.add(List.of(doc));
        } catch (Exception e) {
            String msg = "Upload saved (id=" + saved.getId() + ") but failed to index into vector store: " + e.getMessage();
            System.err.println(msg);
            return ResponseEntity.status(500).body(Map.of("message", msg, "uploadId", saved.getId()));
        }

        Map<String, Object> resp = new HashMap<>();
        resp.put("message", "Upload saved and indexed for " + sp.getServiceName());
        resp.put("uploadId", saved.getId());
        resp.put("serviceName", saved.getServiceName());
        resp.put("serviceEmail", saved.getServiceEmail());
        resp.put("link", saved.getLink());
        resp.put("fileName", saved.getFileName());

        return ResponseEntity.ok(resp);
    }

    // --------------------------
    // GET ALL UPLOADS FOR A PROVIDER
    // --------------------------
    @GetMapping("/uploads")
    public ResponseEntity<?> getUploads(@RequestParam String email) {
        Optional<ServiceProvider> spOpt = serviceProviderRepository.findByEmail(email);
        if (spOpt.isEmpty()) {
            return ResponseEntity.status(404).body("Service provider not found");
        }
        List<Upload> uploads = uploadRepository.findByServiceEmail(email);
        if (uploads == null) {
            uploads = Collections.emptyList();
        }
        return ResponseEntity.ok(uploads);
    }

    // --------------------------
    // VIEW TICKETS
    // --------------------------
    @GetMapping("/tickets")
    public List<Ticket> getTickets(@RequestParam String email) {
        return ticketRepository.findByServiceEmail(email);
    }

    // --------------------------
    // ANSWER A TICKET + SEND EMAIL
    // --------------------------
    @PostMapping("/tickets/answer")
    public ResponseEntity<Ticket> answerTicket(@RequestParam String email,
                                               @RequestParam String ticketId,
                                               @RequestParam String answer) {
        Optional<Ticket> ticketOpt = ticketRepository.findByTicketId(ticketId);
        if (ticketOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Ticket ticket = ticketOpt.get();
        if (!ticket.getServiceEmail().equals(email)) {
            return ResponseEntity.status(403).build();
        }

        ticket.setAnswer(answer);
        ticket.setStatus("COMPLETED");
        ticketRepository.save(ticket);

        // Send email when ticket is marked completed
        sendTicketCompletionEmail(ticket);

        return ResponseEntity.ok(ticket);
    }

    // --------------------------
    // DELETE A SINGLE UPLOAD
    // --------------------------
    @DeleteMapping("/uploads")
    public ResponseEntity<?> deleteUpload(@RequestParam String email, @RequestParam String uploadId) {
        Optional<ServiceProvider> spOpt = serviceProviderRepository.findByEmail(email);
        if (spOpt.isEmpty()) {
            return ResponseEntity.status(404).body("Service provider not found");
        }

        Optional<Upload> uploadOpt = uploadRepository.findById(uploadId);
        if (uploadOpt.isEmpty()) {
            return ResponseEntity.status(404).body("Upload not found");
        }

        Upload upload = uploadOpt.get();
        if (!email.equalsIgnoreCase(upload.getServiceEmail())) {
            return ResponseEntity.status(403).body("Upload does not belong to the specified provider");
        }

        try {
            vectorStore.delete(List.of(upload.getId()));
        } catch (Exception e) {
            System.err.println("Failed to delete vector entry for uploadId=" + upload.getId() + " : " + e.getMessage());
        }

        uploadRepository.delete(upload);
        return ResponseEntity.ok("Upload deleted: " + uploadId);
    }

    // --------------------------
    // DELETE PROVIDER
    // --------------------------
    @DeleteMapping("")
    public ResponseEntity<?> deleteProvider(@RequestParam String email) {
        Optional<ServiceProvider> spOpt = serviceProviderRepository.findByEmail(email);
        if (spOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        ServiceProvider sp = spOpt.get();

        List<Ticket> tickets = ticketRepository.findByServiceEmail(email);
        if (tickets != null && !tickets.isEmpty()) {
            ticketRepository.deleteAll(tickets);
        }

        List<Upload> uploads = uploadRepository.findByServiceEmail(email);
        if (uploads != null && !uploads.isEmpty()) {
            for (Upload upload : uploads) {
                try {
                    vectorStore.delete(List.of(upload.getId()));
                } catch (Exception ex) {
                    System.err.println("Failed to delete vector entry for uploadId=" + upload.getId() + ": " + ex.getMessage());
                }
            }
            uploadRepository.deleteAll(uploads);
        }

        serviceProviderRepository.delete(sp);

        return ResponseEntity.ok("Service provider and all related data deleted for: " + email);
    }

    // --------------------------
    // DELETE ALL PROVIDERS
    // --------------------------
    @DeleteMapping("/all")
    public ResponseEntity<?> deleteAllProviders() {
        List<ServiceProvider> providers = serviceProviderRepository.findAll();
        Map<String, String> summary = new HashMap<>();

        for (ServiceProvider sp : providers) {
            String email = sp.getEmail();
            try {
                List<Ticket> tickets = ticketRepository.findByServiceEmail(email);
                if (tickets != null && !tickets.isEmpty()) {
                    ticketRepository.deleteAll(tickets);
                }

                List<Upload> uploads = uploadRepository.findByServiceEmail(email);
                if (uploads != null && !uploads.isEmpty()) {
                    for (Upload upload : uploads) {
                        try {
                            vectorStore.delete(List.of(upload.getId()));
                        } catch (Exception ex) {
                            System.err.println("Failed to delete vector entry for uploadId=" + upload.getId() + ": " + ex.getMessage());
                        }
                    }
                    uploadRepository.deleteAll(uploads);
                }

                serviceProviderRepository.delete(sp);
                summary.put(email, "deleted");
            } catch (Exception e) {
                summary.put(email, "failed: " + e.getMessage());
            }
        }

        return ResponseEntity.ok("Bulk delete completed. Summary: " + summary.toString());
    }

    // --------------------------
    // HELPER: Send Email Notification
    // --------------------------
    private void sendTicketCompletionEmail(Ticket ticket) {
        try {
            Optional<ServiceProvider> spOpt = serviceProviderRepository.findByEmail(ticket.getServiceEmail());
            String serviceName = spOpt.map(ServiceProvider::getServiceName).orElse("Support Team");

            SimpleMailMessage message = new SimpleMailMessage();
            message.setTo(ticket.getUserEmail());
            message.setSubject("🎉 Your Ticket Has Been Resolved - SmartRAG Solutions");

            String emailBody = String.format(
                    "Hi %s! 👋\n\n" +
                            "Great news! 🌟 Your support ticket has been successfully resolved.\n\n" +
                            "📋 Ticket Details:\n" +
                            "• Ticket ID: %s\n" +
                            "• Problem: %s\n" +
                            "• Service: %s\n" +
                            "• Status: ✅ COMPLETED\n\n" +
                            "💬 Solution Provided:\n%s\n\n" +
                            "We hope our solution helps! If you have any more questions, feel free to create a new ticket.\n\n" +
                            "Thank you for choosing SmartRAG Solutions! 🚀\n\n" +
                            "Best regards,\n" +
                            "The SmartRAG Team 💼\n" +
                            "📧 support@smartrag.com\n" +
                            "🌐 www.smartrag.com",
                    ticket.getUserEmail(),
                    ticket.getTicketId(),
                    ticket.getQuestion(),
                    serviceName,
                    ticket.getAnswer() != null ? ticket.getAnswer() : "Your issue has been resolved."
            );

            message.setText(emailBody);
            mailSender.send(message);
            System.out.println("✅ Email notification sent to " + ticket.getUserEmail());
        } catch (Exception e) {
            System.err.println("❌ Failed to send email to " + ticket.getUserEmail() + " : " + e.getMessage());
        }
    }

    // --------------------------
    // Helper: convert MultipartFile to File
    // --------------------------
    private File convertToFile(MultipartFile file) throws IOException {
        File convFile = File.createTempFile("upload_", "_" + file.getOriginalFilename());
        try (FileOutputStream fos = new FileOutputStream(convFile)) {
            fos.write(file.getBytes());
        }
        return convFile;
    }

    // --------------------------
    // Helper: extract text from image URL using OCR.space
    // --------------------------
    private String extractTextFromUrl(String imageUrl) throws IOException {
        if (OCR_API_KEY == null || OCR_API_KEY.isBlank()) {
            return "";
        }
        OkHttpClient client = new OkHttpClient();

        FormBody body = new FormBody.Builder()
                .add("apikey", OCR_API_KEY)
                .add("url", imageUrl)
                .build();

        Request request = new Request.Builder()
                .url(OCR_API_URL)
                .post(body)
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("OCR API request failed: " + response.code());
            }

            okhttp3.ResponseBody responseBody = response.body();
            if (responseBody == null) {
                throw new IOException("Empty response from OCR API");
            }

            String responseStr = responseBody.string();
            JSONObject json = new JSONObject(responseStr);

            if (json.has("ParsedResults") && json.getJSONArray("ParsedResults").length() > 0) {
                return json.getJSONArray("ParsedResults")
                        .getJSONObject(0)
                        .getString("ParsedText");
            } else {
                return "";
            }
        }
    }
}