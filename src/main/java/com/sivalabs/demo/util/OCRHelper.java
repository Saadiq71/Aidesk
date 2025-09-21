package com.sivalabs.demo.util;

import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Objects;

@Component
public class OCRHelper {

    private final String ocrApiKey;
    private static final String OCR_API_URL = "https://api.ocr.space/parse/image";

    public OCRHelper(@Value("${ocr.space.api.key}") String ocrApiKey) {
        this.ocrApiKey = ocrApiKey;
    }

    /**
     * Extract text from an image using OCR.space API.
     */
    public String extractText(MultipartFile file) throws IOException {
        OkHttpClient client = new OkHttpClient();

        MultipartBody.Builder bodyBuilder = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("apikey", ocrApiKey)
                .addFormDataPart("language", "eng")
                .addFormDataPart("OCREngine", "1")
                .addFormDataPart(
                        "file",
                        Objects.requireNonNull(file.getOriginalFilename()),
                        okhttp3.RequestBody.create(file.getBytes(),
                                okhttp3.MediaType.parse(file.getContentType()))
                );

        Request request = new Request.Builder()
                .url(OCR_API_URL)
                .post(bodyBuilder.build())
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("OCR API failed: " + response.code());
            }
            String responseStr = Objects.requireNonNull(response.body()).string();
            JSONObject json = new JSONObject(responseStr);
            if (json.has("ParsedResults") && json.getJSONArray("ParsedResults").length() > 0) {
                return json.getJSONArray("ParsedResults")
                        .getJSONObject(0)
                        .getString("ParsedText");
            }
            return "";
        }
    }
}
