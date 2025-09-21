//package com.sivalabs.demo;
//
//import jakarta.validation.Valid;
//import jakarta.validation.constraints.NotBlank;
//import org.json.JSONObject;
//import org.slf4j.Logger;
//import org.slf4j.LoggerFactory;
//import org.springframework.ai.chat.client.ChatClient;
//import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
//import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
//import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
//import org.springframework.ai.chat.memory.ChatMemory;
//import org.springframework.ai.document.Document;
//import org.springframework.ai.vectorstore.VectorStore;
//import org.springframework.beans.factory.annotation.Value;
//import org.springframework.data.mongodb.core.MongoTemplate;
//import org.springframework.data.mongodb.core.query.Criteria;
//import org.springframework.data.mongodb.core.query.Query;
//import org.springframework.http.ResponseEntity;
//import org.springframework.web.bind.annotation.*;
//import org.springframework.web.multipart.MultipartFile;
//import okhttp3.MultipartBody;
//import okhttp3.OkHttpClient;
//import okhttp3.Request;
//import okhttp3.Response;
//
//import java.io.IOException;
//import java.util.*;
//
//@RestController
//@RequestMapping("/api")
//public class ChatController {
//
//    private static final Logger log = LoggerFactory.getLogger(ChatController.class);
//    private static final long MAX_FILE_SIZE = 4 * 1024 * 1024; // 4MB
//
//    private final ChatClient chatClient;
//    private final VectorStore vectorStore;
//    private final MongoTemplate mongoTemplate;
//
//    @Value("${ocr.space.api.key}")
//    private String ocrSpaceApiKey;
//
//    // Customer-service style system prompt
//    private static final String SYSTEM_PROMPT = """
//        You are a professional customer service representative. Please follow these guidelines when responding:
//
//        1. Always be polite, respectful, and helpful
//        2. Use a warm, friendly, yet professional tone
//        3. Break down answers into simple, clear steps
//        4. Use numbered or bulleted lists when needed
//        5. Start with a greeting, end with an offer of further assistance
//        6. If something fails, apologize and explain alternatives
//        """;
//
//    public ChatController(ChatClient.Builder builder,
//                          ChatMemory chatMemory,
//                          VectorStore vectorStore,
//                          MongoTemplate mongoTemplate) {
//        this.vectorStore = vectorStore;
//        this.mongoTemplate = mongoTemplate;
//        this.chatClient = builder
//                .defaultAdvisors(
//                        MessageChatMemoryAdvisor.builder(chatMemory).build(),
//                        QuestionAnswerAdvisor.builder(vectorStore).build(),
//                        new SimpleLoggerAdvisor()
//                )
//                .build();
//    }
//
//    // -------------------
//    // 1. Upload + OCR
//    // -------------------
//    @PostMapping("/upload")
//    public ResponseEntity<StructuredOutput> uploadImage(@RequestParam("file") MultipartFile file) {
//        try {
//            if (file.getSize() > MAX_FILE_SIZE) {
//                return ResponseEntity.badRequest().body(
//                        new StructuredOutput("error", "File too large",
//                                "The file size exceeds the 4MB limit. Please try with a smaller image.",
//                                System.currentTimeMillis(), null)
//                );
//            }
//            if (file.getContentType() == null || !file.getContentType().startsWith("image/")) {
//                return ResponseEntity.badRequest().body(
//                        new StructuredOutput("error", "Invalid file type",
//                                "Only image files are supported. Please upload a valid image file.",
//                                System.currentTimeMillis(), null)
//                );
//            }
//
//            String extractedText = extractTextFromImage(file);
//            if (extractedText == null || extractedText.isBlank()) {
//                return ResponseEntity.ok(new StructuredOutput(
//                        "success", "No text found",
//                        "We processed your image but could not find any readable text.",
//                        System.currentTimeMillis(), null
//                ));
//            }
//
//            Document doc = new Document(extractedText, Map.of(
//                    "source", "image-upload",
//                    "filename", file.getOriginalFilename(),
//                    "uploadTime", System.currentTimeMillis(),
//                    "fileSize", file.getSize()
//            ));
//            vectorStore.add(List.of(doc));
//
//            // Return document ID so you can delete later
//            return ResponseEntity.ok(new StructuredOutput(
//                    "success", "Image processed",
//                    "The text has been successfully extracted and stored for future searches.",
//                    System.currentTimeMillis(), doc.getId()
//            ));
//        } catch (Exception e) {
//            log.error("OCR error", e);
//            return ResponseEntity.internalServerError().body(
//                    new StructuredOutput("error", "Processing failed",
//                            "I apologize, but something went wrong while processing your image. Please try again.",
//                            System.currentTimeMillis(), null)
//            );
//        }
//    }
//
//    // -------------------
//    // 2. Ask (Chat with knowledge base)
//    // -------------------
//    @PostMapping("/ask")
//    public ResponseEntity<Output> ask(@RequestBody @Valid Input input) {
//        try {
//            String response = chatClient
//                    .prompt()
//                    .system(SYSTEM_PROMPT)
//                    .user(input.prompt())
//                    .call()
//                    .content();
//            return ResponseEntity.ok(new Output(response));
//        } catch (Exception e) {
//            log.error("Chat error", e);
//            return ResponseEntity.internalServerError()
//                    .body(new Output("I’m sorry, but I’m experiencing technical difficulties. " +
//                            "Please try again shortly, and if the issue continues, kindly contact our support team."));
//        }
//    }
//
//    // -------------------
//    // 3. Delete
//    // -------------------
//    @DeleteMapping("/delete/{id}")
//    public ResponseEntity<StructuredOutput> deleteById(@PathVariable String id) {
//        try {
//            Query q = new Query(Criteria.where("_id").is(id));
//            var res = mongoTemplate.remove(q, "documents");
//            if (res.getDeletedCount() > 0) {
//                return ResponseEntity.ok(new StructuredOutput(
//                        "success", "Document deleted",
//                        "The requested document has been removed successfully.",
//                        System.currentTimeMillis(), id
//                ));
//            }
//            return ResponseEntity.status(404).body(new StructuredOutput(
//                    "error", "Not found",
//                    "We could not find a document with the provided ID.",
//                    System.currentTimeMillis(), id
//            ));
//        } catch (Exception e) {
//            log.error("Delete error", e);
//            return ResponseEntity.internalServerError().body(
//                    new StructuredOutput("error", "Deletion failed",
//                            "I wasn’t able to delete the document due to an error. Please try again.",
//                            System.currentTimeMillis(), id)
//            );
//        }
//    }
//
//    @DeleteMapping("/deleteAll")
//    public ResponseEntity<StructuredOutput> deleteAll() {
//        try {
//            var res = mongoTemplate.remove(new Query(), "documents");
//            return ResponseEntity.ok(new StructuredOutput(
//                    "success", "All documents deleted",
//                    "All stored documents have been removed. You may now upload new ones if you like.",
//                    System.currentTimeMillis(), null
//            ));
//        } catch (Exception e) {
//            log.error("Bulk delete error", e);
//            return ResponseEntity.internalServerError().body(
//                    new StructuredOutput("error", "Deletion failed",
//                            "I wasn’t able to delete all documents. Please try again later.",
//                            System.currentTimeMillis(), null)
//            );
//        }
//    }
//
//    // -------------------
//    // OCR helper
//    // -------------------
//    private String extractTextFromImage(MultipartFile file) throws IOException {
//        OkHttpClient client = new OkHttpClient();
//        MultipartBody.Builder bodyBuilder = new MultipartBody.Builder()
//                .setType(MultipartBody.FORM)
//                .addFormDataPart("apikey", ocrSpaceApiKey)
//                .addFormDataPart("language", "eng")
//                .addFormDataPart("OCREngine", "1")
//                .addFormDataPart(
//                        "file",
//                        Objects.requireNonNull(file.getOriginalFilename()),
//                        okhttp3.RequestBody.create(file.getBytes(), okhttp3.MediaType.parse(file.getContentType()))
//                );
//
//        Request request = new Request.Builder()
//                .url("https://api.ocr.space/parse/image")
//                .post(bodyBuilder.build())
//                .build();
//
//        try (Response response = client.newCall(request).execute()) {
//            if (!response.isSuccessful()) throw new IOException("OCR API failed: " + response.code());
//            String responseStr = Objects.requireNonNull(response.body()).string();
//            JSONObject json = new JSONObject(responseStr);
//            if (json.has("ParsedResults") && json.getJSONArray("ParsedResults").length() > 0) {
//                return json.getJSONArray("ParsedResults").getJSONObject(0).getString("ParsedText");
//            }
//            return "";
//        }
//    }
//
//    // -------------------
//    // Records
//    // -------------------
//    public record Input(@NotBlank String prompt) {}
//    public record Output(String content) {}
//    public record StructuredOutput(
//            String status,
//            String message,
//            String content,
//            long timestamp,
//            String documentId
//    ) {}
//}
