package com.lepu;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class ChatBotClient {

    private static String sessionId = null;
    private static final String API_BASE_URL = "https://aiapi.lepumedical.com";
    private static final String AUTH_TOKEN = "Bearer eyJhbGciOiJIUzUxMiJ9.eyJ1c2VyX2lkIjoyNzE0LCJ1c2VyX25hbWUiOiIxMzEwNjUyMDEzNyIsInVzZXJfa2V5IjoiMzM4M2Q0MzctNDM3My00MTZmLWI4OGYtOTJmYzFmOGMxNGIxIn0.d687lLmawOfu-tfJ_0KyUeBGiVrVUitcBIQegeFAqkeuD5qKNfIiJckVwI4w2Snt5SEZRYSO6FRbaALUqWbR2A";
    private static final String QUESTION_FILE_PATH = "C:\\Users\\wyl\\Desktop\\q.txt";
    private static final Random random = new Random();

    private static final int QUESTIONS_PER_SESSION = 5;
    // 单个问题最长等待时间（秒）
    private static final int REQUEST_TIMEOUT_SECONDS = 120;

    private static final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private static int totalQuestions = 0;

    public static void main(String[] args) {
        try {
            List<String> questions = readQuestionsFromFile(QUESTION_FILE_PATH);
            totalQuestions = questions.size();
            System.out.printf("成功从文件读取 %d 个问题%n", totalQuestions);
            askQuestionsRandomly(questions);
        } catch (IOException e) {
            System.err.printf("文件操作错误: %s%n", e.getMessage());
        } catch (InterruptedException e) {
            System.err.printf("操作被中断: %s%n", e.getMessage());
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            System.err.printf("发生未知错误: %s%n", e.getMessage());
        }
    }

    private static List<String> readQuestionsFromFile(String filePath) throws IOException {
        List<String> questions = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(filePath))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty()) {
                    questions.add(line);
                }
            }
        }
        return questions;
    }

    private static boolean createSession() throws IOException, InterruptedException {
        System.out.println("正在创建会话...");
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_BASE_URL + "/api/startChat"))
                .header("Authorization", AUTH_TOKEN)
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 200) {
            try {
                JSONObject jsonObj = JSONUtil.parseObj(response.body());
                if (jsonObj.containsKey("body")) {
                    JSONObject bodyObj = jsonObj.getJSONObject("body");
                    if (bodyObj != null && bodyObj.containsKey("id")) {
                        sessionId = bodyObj.getStr("id");
                        System.out.printf("会话创建成功，ID: %s%n", sessionId);
                        return true;
                    }
                }
                System.err.println("响应中未找到有效的会话ID");
                return false;
            } catch (Exception e) {
                System.err.printf("解析JSON响应失败: %s%n", e.getMessage());
                return false;
            }
        } else {
            System.err.printf("创建会话失败，状态码: %d，响应: %s%n", response.statusCode(), response.body());
            return false;
        }
    }

    private static void sendMessage(String message) throws IOException, InterruptedException {
        System.out.printf("正在发送问题: %s%n", message);
        String requestBody = String.format(
                "{\"message\":\"%s\",\"chatId\":\"%s\",\"ifDeep\":false,\"ifWebSearch\":false,\"ifWebParse\":false}",
                message.replace("\\", "\\\\").replace("\"", "\\\""),
                sessionId);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_BASE_URL + "/flux/chat"))
                .header("Content-Type", "application/json")
                .header("Authorization", AUTH_TOKEN)
                .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        CountDownLatch responseComplete = new CountDownLatch(1);

        client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
                .thenAccept(response -> {
                    if (response.statusCode() != 200) {
                        System.err.printf("请求失败，状态码: %d%n", response.statusCode());
                        responseComplete.countDown();
                        return;
                    }
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            if (!line.startsWith("data:")) continue;
                            String data = line.substring("data:".length()).trim();
                            if (data.isEmpty()) continue;

                            // 逐个解析 data 行中可能包含的多个 JSON 对象
                            for (String jsonStr : splitMultipleJsonObjects(data)) {
                                if (jsonStr.trim().isEmpty()) continue;
                                try {
                                    JSONObject jsonObj = JSONUtil.parseObj(jsonStr);
                                    String text = jsonObj.getStr("text", "");
                                    if (!text.isEmpty()) {
                                        System.out.print(text);
                                    }
                                    if ("stop".equals(jsonObj.getStr("event", ""))) {
                                        System.out.println("\n回答已完成");
                                        responseComplete.countDown();
                                        return;
                                    }
                                } catch (Exception e) {
                                    // 忽略单个解析失败，继续处理后续数据
                                }
                            }
                        }
                        // 流正常结束但未收到 stop 事件
                        System.out.println("\n流已结束");
                    } catch (IOException e) {
                        System.err.printf("读取响应失败: %s%n", e.getMessage());
                    } finally {
                        responseComplete.countDown();
                    }
                })
                .exceptionally(ex -> {
                    System.err.printf("请求异常: %s%n", ex.getMessage());
                    responseComplete.countDown();
                    return null;
                });

        // 超时保护，防止永久阻塞
        boolean finished = responseComplete.await(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!finished) {
            System.err.println("等待响应超时，跳过当前问题");
        }
        System.out.println("问题处理完成，准备发送下一个问题");
    }

    private static String[] splitMultipleJsonObjects(String data) {
        List<String> jsonObjects = new ArrayList<>();
        int braceCount = 0;
        int startIndex = 0;
        for (int i = 0; i < data.length(); i++) {
            char c = data.charAt(i);
            if (c == '{') {
                if (braceCount == 0) startIndex = i;
                braceCount++;
            } else if (c == '}') {
                braceCount--;
                if (braceCount == 0) {
                    jsonObjects.add(data.substring(startIndex, i + 1));
                }
            }
        }
        return jsonObjects.toArray(new String[0]);
    }

    private static void askQuestionsRandomly(List<String> questions) throws IOException, InterruptedException {
        if (questions == null || questions.isEmpty()) {
            System.out.println("没有问题可发送");
            return;
        }

        List<String> shuffledQuestions = new ArrayList<>(questions);
        Collections.shuffle(shuffledQuestions);
        System.out.printf("准备随机发送 %d 个问题(每 %d 个问题创建一次会话)%n",
                shuffledQuestions.size(), QUESTIONS_PER_SESSION);

        for (int i = 0; i < shuffledQuestions.size(); i++) {
            if (i % QUESTIONS_PER_SESSION == 0) {
                boolean sessionCreated = createSession();
                if (!sessionCreated) {
                    System.err.println("创建会话失败，跳过剩余问题");
                    break;
                }
            }
            System.out.printf("(%d/%d) ", i + 1, shuffledQuestions.size());
            sendMessage(shuffledQuestions.get(i));
        }
        System.out.println("所有问题发送完毕");
    }
}