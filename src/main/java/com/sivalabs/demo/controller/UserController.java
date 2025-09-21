package com.sivalabs.demo.controller;

import com.sivalabs.demo.model.ServiceProvider;
import com.sivalabs.demo.model.Ticket;
import com.sivalabs.demo.model.User;
import com.sivalabs.demo.repository.ServiceProviderRepository;
import com.sivalabs.demo.repository.TicketRepository;
import com.sivalabs.demo.repository.UserRepository;
import com.sivalabs.demo.util.TicketIdGenerator;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.http.ResponseEntity;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final ChatClient chatClient;
    private final UserRepository userRepository;
    private final ServiceProviderRepository serviceProviderRepository;
    private final TicketRepository ticketRepository;
    private final VectorStore vectorStore;
    private final JavaMailSender mailSender;

    private static final int TOP_K = 5;
    private static final int MAX_CONTEXT_CHARS = 12_000;
    private static final String NO_INFO_REPLY =
            "I don't have information in my context to answer that. Would you like to create a support ticket so we can help you?";

    public UserController(ChatClient chatClient,
                          UserRepository userRepository,
                          ServiceProviderRepository serviceProviderRepository,
                          TicketRepository ticketRepository,
                          VectorStore vectorStore,
                          JavaMailSender mailSender) {
        this.chatClient = chatClient;
        this.userRepository = userRepository;
        this.serviceProviderRepository = serviceProviderRepository;
        this.ticketRepository = ticketRepository;
        this.vectorStore = vectorStore;
        this.mailSender = mailSender;
    }

    // --------------------------
    // REGISTER
    // --------------------------
    @PostMapping("/register")
    public ResponseEntity<User> register(@RequestParam String email,
                                         @RequestParam String password,
                                         @RequestParam(required = false) String name) {
        if (email == null || password == null) {
            return ResponseEntity.badRequest().build();
        }
        if (userRepository.findByEmail(email).isPresent()) {
            return ResponseEntity.status(409).build();
        }

        User user = new User();
        user.setEmail(email);
        user.setPassword(password);
        user.setName(name != null ? name : "");

        User saved = userRepository.save(user);

        // Send welcome email (best-effort)
        try {
            sendWelcomeEmail(saved);
        } catch (Exception e) {
            System.err.println("Failed to send welcome email to " + saved.getEmail() + " : " + e.getMessage());
        }

        return ResponseEntity.ok(saved);
    }

    // --------------------------
    // DELETE single user
    // --------------------------
    @DeleteMapping("")
    public ResponseEntity<?> deleteUser(@RequestParam String email) {
        Optional<User> userOpt = userRepository.findByEmail(email);
        if (userOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        List<Ticket> tickets = ticketRepository.findByUserEmail(email);
        if (tickets != null && !tickets.isEmpty()) {
            ticketRepository.deleteAll(tickets);
        }

        userRepository.delete(userOpt.get());
        return ResponseEntity.ok("User and their tickets deleted for: " + email);
    }

    // --------------------------
    // DELETE all users
    // --------------------------
    @DeleteMapping("/all")
    public ResponseEntity<?> deleteAllUsers() {
        List<User> users = userRepository.findAll();
        for (User u : users) {
            List<Ticket> userTickets = ticketRepository.findByUserEmail(u.getEmail());
            if (userTickets != null && !userTickets.isEmpty()) {
                ticketRepository.deleteAll(userTickets);
            }
        }
        userRepository.deleteAll();
        return ResponseEntity.ok("All users and their tickets have been deleted.");
    }

    // --------------------------
    // LOGIN
    // --------------------------
    @PostMapping("/login")
    public ResponseEntity<User> login(@RequestParam String email,
                                      @RequestParam String password) {
        if (email == null || password == null) {
            return ResponseEntity.badRequest().build();
        }
        return userRepository.findByEmail(email)
                .filter(u -> u.getPassword() != null && u.getPassword().equals(password))
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.status(401).build());
    }

    // --------------------------
    // ASK FLOW
    // --------------------------
    @PostMapping("/ask")
    public ResponseEntity<Map<String, Object>> ask(@RequestParam String userEmail,
                                                   @RequestParam String prompt) {
        Map<String, Object> resp = new HashMap<>();

        if (userEmail == null || prompt == null) {
            resp.put("status", "error");
            resp.put("message", "Invalid request: userEmail and prompt are required.");
            return ResponseEntity.badRequest().body(resp);
        }

        Optional<User> userOpt = userRepository.findByEmail(userEmail);
        if (userOpt.isEmpty()) {
            resp.put("status", "error");
            resp.put("message", "Unknown user.");
            return ResponseEntity.badRequest().body(resp);
        }

        List<ServiceProvider> services = serviceProviderRepository.findAll();
        if (services.isEmpty()) {
            resp.put("status", "error");
            resp.put("message", "Sorry, no services are currently registered on this platform.");
            return ResponseEntity.ok(resp);
        }

        String options = services.stream()
                .map(ServiceProvider::getServiceName)
                .collect(Collectors.joining(", ")) + ", Not Related";

        String classificationPrompt = """
                You are a strict classifier.
                Choose exactly ONE option from: [%s]
                Question: %s
                Only output the chosen option (no extra text).
                """.formatted(options, prompt);

        String predicted;
        try {
            predicted = chatClient.prompt()
                    .system("You are a strict classifier.")
                    .user(classificationPrompt)
                    .call()
                    .content()
                    .trim();
        } catch (Exception e) {
            resp.put("status", "error");
            resp.put("message", "Failed to classify the question: " + e.getMessage());
            return ResponseEntity.internalServerError().body(resp);
        }

        if (predicted != null) predicted = predicted.trim();
        resp.put("predictedServiceName", predicted);
        final String finalPredicted = predicted;

        boolean predictedRegistered = services.stream()
                .map(ServiceProvider::getServiceName)
                .anyMatch(s -> s.equalsIgnoreCase(finalPredicted));

        if ("Not Related".equalsIgnoreCase(finalPredicted) || !predictedRegistered) {
            resp.put("status", "not_related");
            resp.put("message", "Sorry, we don't have any registered service on this platform that can handle this question.");
            resp.put("answer", null);
            resp.put("explanation", null);
            return ResponseEntity.ok(resp);
        }

        SearchRequest searchRequest = SearchRequest.builder()
                .query(prompt)
                .topK(TOP_K)
                .build();

        List<Document> results;
        try {
            results = vectorStore.similaritySearch(searchRequest);
        } catch (Exception e) {
            System.err.println("Vector store search failed: " + e.getMessage());
            results = Collections.emptyList();
        }

        List<Document> serviceResults = new ArrayList<>();
        if (results != null) {
            for (Document d : results) {
                if (d == null) continue;
                Map<String, Object> meta = d.getMetadata();
                if (meta == null) continue;
                Object svc = meta.get("serviceName");
                if (svc == null) continue;
                String svcName = String.valueOf(svc).trim();
                if (svcName.equalsIgnoreCase(finalPredicted)) {
                    serviceResults.add(d);
                }
            }
        }

        StringBuilder contextBuilder = new StringBuilder();
        for (Document d : serviceResults) {
            String text = d.getText();
            if (text == null || text.isBlank()) continue;

            if (contextBuilder.length() + text.length() + 2 > MAX_CONTEXT_CHARS) {
                int remaining = MAX_CONTEXT_CHARS - contextBuilder.length();
                if (remaining > 50) {
                    contextBuilder.append(text, 0, Math.max(0, Math.min(text.length(), remaining)));
                }
                break;
            } else {
                if (contextBuilder.length() > 0) contextBuilder.append("\n\n");
                contextBuilder.append(text.trim());
            }
        }

        String context = contextBuilder.toString().trim();

        String assistantSystem = """
                You are a strict assistant that MUST answer user questions ONLY using the provided CONTEXT below.
                Do NOT invent facts. Do NOT reference or include any source metadata, upload IDs, filenames, or where the text came from.
                If the user's question cannot be answered using the CONTEXT, reply EXACTLY with: "%s"
                """.formatted(NO_INFO_REPLY);

        String assistantUserPrompt = """
                CONTEXT:
                %s

                USER QUESTION:
                %s

                INSTRUCTIONS:
                1) If the answer exists in the CONTEXT, respond ONLY with two sections, labeled exactly as:

                ANSWER:
                <concise factual answer>

                EXPLANATION:
                <very short, simple explanation written in a friendly 'service-person' tone — one or two sentences; do NOT mention sources or IDs>

                2) If the answer is NOT present in the CONTEXT, reply EXACTLY with: "%s"
                3) Do NOT include any other text, headers, or metadata.
                """.formatted(context.isBlank() ? "(no context available)" : context, prompt, NO_INFO_REPLY);

        String assistantResponse;
        try {
            assistantResponse = chatClient.prompt()
                    .system(assistantSystem)
                    .user(assistantUserPrompt)
                    .call()
                    .content()
                    .trim();
        } catch (Exception e) {
            System.err.println("Assistant call failed: " + e.getMessage());
            resp.put("status", "error");
            resp.put("message", "Assistant unavailable (quota or network issue). Please try again later or create a support ticket.");
            resp.put("answer", null);
            resp.put("explanation", null);
            return ResponseEntity.status(503).body(resp);
        }

        if (NO_INFO_REPLY.equals(assistantResponse)) {
            resp.put("status", "no_faq");
            resp.put("message", "We couldn't find an answer to your question within the service's FAQs. Would you like to create a support ticket?");
            resp.put("answer", null);
            resp.put("explanation", null);
            return ResponseEntity.ok(resp);
        }

        String answer = null;
        String explanation = null;
        String upper = assistantResponse.toUpperCase(Locale.ROOT);
        int idxAnswer = upper.indexOf("ANSWER:");
        int idxExplanation = upper.indexOf("EXPLANATION:");

        if (idxAnswer != -1 && idxExplanation != -1 && idxExplanation > idxAnswer) {
            try {
                String rawAnswer = assistantResponse.substring(idxAnswer + "ANSWER:".length(), idxExplanation).trim();
                String rawExplanation = assistantResponse.substring(idxExplanation + "EXPLANATION:".length()).trim();
                answer = rawAnswer.isBlank() ? null : rawAnswer;
                explanation = rawExplanation.isBlank() ? null : rawExplanation;
            } catch (Exception ex) {
                answer = assistantResponse;
                explanation = null;
            }
        } else {
            answer = assistantResponse;
            explanation = null;
        }

        if (answer == null || answer.isBlank()) {
            resp.put("status", "no_faq");
            resp.put("message", "We couldn't find an answer to your question within the service's FAQs. Would you like to create a support ticket?");
            resp.put("answer", null);
            resp.put("explanation", null);
        } else {
            resp.put("status", "answered");
            resp.put("message", "Answer provided by assistant (from service context).");
            resp.put("answer", answer);
            resp.put("explanation", explanation);
        }

        return ResponseEntity.ok(resp);
    }

    // --------------------------
    // TICKETS
    // --------------------------
    @PostMapping("/tickets")
    public ResponseEntity<?> createTicket(@RequestParam String userEmail,
                                          @RequestParam String question,
                                          @RequestParam(required = false) String description) {
        if (userEmail == null || question == null) {
            return ResponseEntity.badRequest().body("userEmail and problem description are required");
        }

        // 1. Verify user exists
        Optional<User> userOpt = userRepository.findByEmail(userEmail);
        if (userOpt.isEmpty()) {
            return ResponseEntity.badRequest().body("Unknown user");
        }

        // 2. Get available services
        List<ServiceProvider> services = serviceProviderRepository.findAll();
        if (services.isEmpty()) {
            return ResponseEntity.status(500).body("No services are currently registered");
        }

        String options = services.stream()
                .map(ServiceProvider::getServiceName)
                .collect(Collectors.joining(", ")) + ", Not Related";

        String classificationPrompt = """
            You are a strict classifier.
            Choose exactly ONE option from: [%s]
            Problem: %s
            Only output the chosen option (no extra text).
            """.formatted(options, question);

        String predictedService;
        try {
            predictedService = chatClient.prompt()
                    .system("You are a strict classifier.")
                    .user(classificationPrompt)
                    .call()
                    .content()
                    .trim();
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body("Service classification failed: " + e.getMessage());
        }

        if (predictedService == null || predictedService.equalsIgnoreCase("Not Related")) {
            return ResponseEntity.status(400).body("We couldn't classify this problem into any registered service.");
        }

        // 3. Find service provider by predicted service
        Optional<ServiceProvider> providerOpt = services.stream()
                .filter(sp -> sp.getServiceName().equalsIgnoreCase(predictedService))
                .findFirst();

        if (providerOpt.isEmpty()) {
            return ResponseEntity.status(404).body("No provider found for predicted service: " + predictedService);
        }

        ServiceProvider provider = providerOpt.get();

        // 4. Assign ticket details
        String ticketId = TicketIdGenerator.generate();
        Ticket ticket = new Ticket();
        ticket.setTicketId(ticketId);
        ticket.setUserEmail(userEmail);
        ticket.setServiceEmail(provider.getEmail());
        ticket.setServiceName(provider.getServiceName());
        ticket.setQuestion(question);
        ticket.setDescription(description);
        ticket.setStatus("OPEN");

        Ticket saved = ticketRepository.save(ticket);

        // 5. Return ticket with assigned service
        return ResponseEntity.ok(Map.of(
                "message", "Ticket created and assigned successfully",
                "ticketId", saved.getTicketId(),
                "serviceName", provider.getServiceName(),
                "serviceEmail", provider.getEmail(),
                "status", saved.getStatus()
        ));
    }

    @GetMapping("/tickets")
    public List<Ticket> getUserTickets(@RequestParam String email) {
        return ticketRepository.findByUserEmail(email);
    }

    // --------------------------
    // HELPER: send welcome email after registration
    // --------------------------
    private void sendWelcomeEmail(User user) {
        if (user == null || user.getEmail() == null) return;

        try {
            String recipient = user.getEmail();
            String name = (user.getName() != null && !user.getName().isBlank()) ? user.getName() : recipient;

            SimpleMailMessage message = new SimpleMailMessage();
            message.setTo(recipient);
            message.setSubject("Welcome to Smart help Desk Solutions 🎉");
            String body = String.format(
                    "Hi %s,\n\n" +
                            "Welcome to SmartRAG! We're happy to have you onboard. If you ever need help, ask the assistant or create a support ticket and our service providers will help you.\n\n" +
                            "Quick tips:\n" +
                            "• Use the 'Ask' feature to search service FAQs.\n" +
                            "• Create a ticket if the assistant can't find an answer.\n\n" +
                            "Thanks for joining us!\n\n" +
                            "Best,\n" +
                            "The SmartRAG Team\n" +
                            "support@smartrag.com", name);

            message.setText(body);

            mailSender.send(message);
            System.out.println("✅ Welcome email sent to " + recipient);
        } catch (Exception e) {
            System.err.println("Failed to send welcome email to " + user.getEmail() + " : " + e.getMessage());
        }
    }
}