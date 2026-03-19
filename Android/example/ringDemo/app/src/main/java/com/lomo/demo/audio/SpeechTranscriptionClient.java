package com.lomo.demo.audio;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class SpeechTranscriptionClient {
    private static final String OPENAI_URL = "https://api.openai.com/v1/audio/transcriptions";
    private static final String GEMINI_UPLOAD_URL = "https://generativelanguage.googleapis.com/upload/v1beta/files?key=%s";
    private static final String GEMINI_GENERATE_URL = "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent?key=%s";
    private static final MediaType MEDIA_TYPE_WAV = MediaType.parse("audio/wav");
    private static final MediaType MEDIA_TYPE_JSON = MediaType.parse("application/json; charset=utf-8");

    private final OkHttpClient client;

    public SpeechTranscriptionClient() {
        client = new OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(120, TimeUnit.SECONDS)
                .build();
    }

    public String transcribeWithOpenAi(File audioFile, String apiKey, String model) throws IOException {
        RequestBody fileBody = RequestBody.create(audioFile, MEDIA_TYPE_WAV);
        RequestBody requestBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", audioFile.getName(), fileBody)
                .addFormDataPart("model", model)
                .addFormDataPart("language", "zh")
                .build();
        Request request = new Request.Builder()
                .url(OPENAI_URL)
                .header("Authorization", "Bearer " + apiKey)
                .post(requestBody)
                .build();
        try (Response response = client.newCall(request).execute()) {
            String body = readBody(response.body());
            ensureSuccessful(response, body);
            try {
                JSONObject jsonObject = new JSONObject(body);
                return jsonObject.optString("text");
            } catch (JSONException e) {
                throw new IOException("OpenAI 返回解析失败: " + body, e);
            }
        }
    }

    public String transcribeWithGemini(File audioFile, String apiKey, String model) throws IOException {
        UploadedGeminiFile uploadedFile = uploadGeminiFile(audioFile, apiKey);
        JSONObject body = new JSONObject();
        try {
            JSONObject fileData = new JSONObject()
                    .put("mime_type", uploadedFile.mimeType)
                    .put("file_uri", uploadedFile.fileUri);
            JSONObject userPart = new JSONObject()
                    .put("text", "请将这段音频逐字转写为简体中文，只返回转写文本，不要添加说明。");
            JSONObject filePart = new JSONObject().put("file_data", fileData);
            JSONArray parts = new JSONArray().put(userPart).put(filePart);
            JSONObject content = new JSONObject()
                    .put("role", "user")
                    .put("parts", parts);
            body.put("contents", new JSONArray().put(content));
        } catch (JSONException e) {
            throw new IOException("构建 Gemini 请求失败", e);
        }

        Request request = new Request.Builder()
                .url(String.format(GEMINI_GENERATE_URL, model, apiKey))
                .post(RequestBody.create(body.toString(), MEDIA_TYPE_JSON))
                .build();
        try (Response response = client.newCall(request).execute()) {
            String responseBody = readBody(response.body());
            ensureSuccessful(response, responseBody);
            return parseGeminiTranscript(responseBody);
        }
    }

    private UploadedGeminiFile uploadGeminiFile(File audioFile, String apiKey) throws IOException {
        Request startRequest = new Request.Builder()
                .url(String.format(GEMINI_UPLOAD_URL, apiKey))
                .header("X-Goog-Upload-Protocol", "resumable")
                .header("X-Goog-Upload-Command", "start")
                .header("X-Goog-Upload-Header-Content-Length", String.valueOf(audioFile.length()))
                .header("X-Goog-Upload-Header-Content-Type", "audio/wav")
                .post(RequestBody.create("{\"file\":{\"display_name\":\"" + audioFile.getName() + "\"}}", MEDIA_TYPE_JSON))
                .build();
        String uploadUrl;
        try (Response response = client.newCall(startRequest).execute()) {
            String responseBody = readBody(response.body());
            ensureSuccessful(response, responseBody);
            uploadUrl = response.header("X-Goog-Upload-URL");
            if (uploadUrl == null || uploadUrl.trim().isEmpty()) {
                throw new IOException("Gemini 未返回上传地址");
            }
        }

        Request uploadRequest = new Request.Builder()
                .url(uploadUrl)
                .header("Content-Length", String.valueOf(audioFile.length()))
                .header("X-Goog-Upload-Offset", "0")
                .header("X-Goog-Upload-Command", "upload, finalize")
                .post(RequestBody.create(audioFile, MEDIA_TYPE_WAV))
                .build();
        try (Response response = client.newCall(uploadRequest).execute()) {
            String responseBody = readBody(response.body());
            ensureSuccessful(response, responseBody);
            try {
                JSONObject jsonObject = new JSONObject(responseBody).getJSONObject("file");
                return new UploadedGeminiFile(
                        jsonObject.optString("uri"),
                        jsonObject.optString("mimeType", "audio/wav")
                );
            } catch (JSONException e) {
                throw new IOException("Gemini 文件上传结果解析失败: " + responseBody, e);
            }
        }
    }

    private static String parseGeminiTranscript(String responseBody) throws IOException {
        try {
            JSONObject jsonObject = new JSONObject(responseBody);
            JSONArray candidates = jsonObject.optJSONArray("candidates");
            if (candidates == null || candidates.length() == 0) {
                return "";
            }
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < candidates.length(); i++) {
                JSONObject candidate = candidates.optJSONObject(i);
                if (candidate == null) {
                    continue;
                }
                JSONObject content = candidate.optJSONObject("content");
                if (content == null) {
                    continue;
                }
                JSONArray parts = content.optJSONArray("parts");
                if (parts == null) {
                    continue;
                }
                for (int j = 0; j < parts.length(); j++) {
                    JSONObject part = parts.optJSONObject(j);
                    if (part == null) {
                        continue;
                    }
                    String text = part.optString("text");
                    if (text != null && !text.trim().isEmpty()) {
                        if (builder.length() > 0) {
                            builder.append('\n');
                        }
                        builder.append(text.trim());
                    }
                }
            }
            return builder.toString();
        } catch (JSONException e) {
            throw new IOException("Gemini 返回解析失败: " + responseBody, e);
        }
    }

    private static void ensureSuccessful(Response response, String responseBody) throws IOException {
        if (!response.isSuccessful()) {
            throw new IOException("HTTP " + response.code() + ": " + responseBody);
        }
    }

    private static String readBody(ResponseBody responseBody) throws IOException {
        return responseBody == null ? "" : responseBody.string();
    }

    private static final class UploadedGeminiFile {
        private final String fileUri;
        private final String mimeType;

        private UploadedGeminiFile(String fileUri, String mimeType) {
            this.fileUri = fileUri;
            this.mimeType = mimeType;
        }
    }
}
