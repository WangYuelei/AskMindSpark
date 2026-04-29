package com.lepu;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CountDownLatch;

public class ChatBotClientPro {
    private static String sessionId = null;
    private static String API_BASE_URL;
    private static String QUESTION_FILE_PATH;
    private static String AUTH_TOKEN;
    private static final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private static int totalQuestions = 0;
    private static int completedQuestions = 0;
    // 问题间等待时间(毫秒)
    private static final long QUESTION_DELAY = 5000; // 15秒

    // 配置文件路径（Windows桌面）
    private static final String CONFIG_FILE_PATH = System.getProperty("user.home") +
            "\\Desktop\\chatbot_config.txt";

    public static void main(String[] args) {
        try {
            // 读取配置文件
            loadConfig();

            System.out.println("===== ChatBot客户端 =====");
            System.out.printf("使用配置:%n - API地址: %s%n - 问题文件: %s%n - 认证令牌: %s%n",
                    API_BASE_URL, QUESTION_FILE_PATH, AUTH_TOKEN);

            // 读取问题文件
            List<String> questions = readQuestionsFromFile(QUESTION_FILE_PATH);
            totalQuestions = questions.size();
            System.out.printf("成功从文件读取 %d 个问题%n", totalQuestions);

            // 创建会话
            boolean sessionCreated = createSession();
            if (!sessionCreated) {
                System.out.println("创建会话失败，程序退出");
                return;
            }

            // 按顺序处理问题
            processQuestionsSequentially(questions);

        } catch (IOException e) {
            System.err.printf("文件操作错误: %s%n", e.getMessage());
        } catch (InterruptedException e) {
            System.err.printf("操作被中断: %s%n", e.getMessage());
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            System.err.printf("发生未知错误: %s%n", e.getMessage());
        }
    }

    // 从配置文件加载参数
    private static void loadConfig() throws IOException {
        System.out.println("正在读取配置文件: " + CONFIG_FILE_PATH);

        if (!Files.exists(Paths.get(CONFIG_FILE_PATH))) {
            throw new FileNotFoundException("配置文件不存在: " + CONFIG_FILE_PATH);
        }

        Map<String, String> configMap = new HashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(Paths.get(CONFIG_FILE_PATH))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) { // 跳过空行和注释
                    continue;
                }

                String[] parts = line.split("=", 2);
                if (parts.length == 2) {
                    configMap.put(parts[0].trim(), parts[1].trim());
                }
            }
        }

        // 从配置中获取参数
        API_BASE_URL = "https://aiapi.lepumedical.com";
        QUESTION_FILE_PATH = "C:\\Users\\wyl\\Desktop\\q.txt";
        AUTH_TOKEN = "eyJhbGciOiJIUzUxMiJ9.eyJ1c2VyX2lkIjoyNzE0LCJ1c2VyX25hbWUiOiIxMzEwNjUyMDEzNyIsInVzZXJfa2V5IjoiNGI0NzgwNDgtNjE2Yy00ODQ5LTlmZjYtYWJlZDJhZjM4NzdkIn0.AcYb3D-Ao82WKCqCp6m6mhnIoPERsEWAS79b3IIAu4VnO2WZ9FDO3B1o35IY4-RnVWL_lNCu7gdQ2BQzHv1h1g";

        if (API_BASE_URL == null || API_BASE_URL.isEmpty()) {
            throw new IllegalArgumentException("配置文件中未找到API_BASE_URL");
        }
        if (QUESTION_FILE_PATH == null || QUESTION_FILE_PATH.isEmpty()) {
            throw new IllegalArgumentException("配置文件中未找到QUESTION_FILE_PATH");
        }
        if (AUTH_TOKEN == null || AUTH_TOKEN.isEmpty()) {
            throw new IllegalArgumentException("配置文件中未找到AUTH_TOKEN");
        }

        // 如果TOKEN不是以Bearer开头，添加Bearer前缀
        if (!AUTH_TOKEN.startsWith("Bearer ")) {
            AUTH_TOKEN = "Bearer " + AUTH_TOKEN;
        }

        System.out.println("配置文件加载成功");
    }

    // 其余方法保持不变...
    // 从文件读取问题
    private static List<String> readQuestionsFromFile(String filePath) throws IOException {
        List<String> questions = new ArrayList<>();
        File file = new File(filePath);
        if (!file.exists() || file.isDirectory()) {
            throw new FileNotFoundException("文件不存在或路径不正确: " + filePath);
        }

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

    // 创建新会话
    private static boolean createSession() throws IOException, InterruptedException {
        System.out.println("正在创建会话...");

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_BASE_URL + "/api/startChat"))
                .header("Authorization", AUTH_TOKEN)
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
            System.err.printf("创建会话失败，状态码: %d%n", response.statusCode());
            System.err.println("响应内容: " + response.body());
            return false;
        }
    }

    // 按顺序处理问题
    private static void processQuestionsSequentially(List<String> questions) throws IOException, InterruptedException {
        if (questions == null || questions.isEmpty()) {
            System.out.println("没有问题可发送");
            return;
        }

        System.out.println("开始按顺序处理问题...");
        int questionNumber = 1;

        // 使用while循环处理问题，确保删除后正确遍历
        while (!questions.isEmpty()) {
            String question = questions.get(0); // 取第一个问题
            System.out.printf("处理问题 %d/%d: %s%n", questionNumber++, questions.size(), question);

            // 发送问题并等待回答完成
            sendMessage(question, questions);

            // 问题处理完成后从列表中删除
            if (!questions.isEmpty() && questions.contains(question)) {
                questions.remove(question);
                completedQuestions++;
                System.out.printf("问题 %d 处理完成，累计完成 %d 个问题%n", completedQuestions, completedQuestions);

                // 更新文件，删除已处理的问题
                saveQuestionsToFile(questions, QUESTION_FILE_PATH);
                System.out.printf("已删除问题，剩余 %d 个问题%n", questions.size());

                // 等待15秒再处理下一个问题
                if (!questions.isEmpty()) {
                    System.out.printf("等待 %d 秒后处理下一个问题...%n", QUESTION_DELAY / 1000);
                    Thread.sleep(QUESTION_DELAY);
                }
            }
        }

        System.out.println("所有问题处理完毕");
    }

    // 发送提问并处理响应（单线程顺序执行）
    private static void sendMessage(String message, List<String> questions) throws IOException, InterruptedException {
        System.out.printf("正在发送问题: %s%n", message);

        String requestBody = String.format("{\"message\":\"%s\",\"chatId\":\"%s\",\"ifDeep\":false,\"ifWebSearch\":false,\"ifWebParse\":false}",
                message.replace("\"", "\\\""), sessionId);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_BASE_URL + "/flux/chat"))
                .header("Content-Type", "application/json")
                .header("Authorization", AUTH_TOKEN)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        StringBuilder fullResponse = new StringBuilder();
        CountDownLatch responseComplete = new CountDownLatch(1);

        client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
                .thenAccept(response -> {
                    if (response.statusCode() != 200) {
                        System.err.printf("请求失败，状态码: %d，问题: %s%n", response.statusCode(), message);
                        responseComplete.countDown();
                        return;
                    }

                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {

                        String line;
                        StringBuilder dataBuilder = new StringBuilder();

                        while ((line = reader.readLine()) != null && responseComplete.getCount() > 0) {
                            line = line.trim();
                            if (line.isEmpty() || !line.startsWith("data:")) {
                                continue;
                            }

                            String data = line.substring("data:".length()).trim();
                            dataBuilder.append(data);

                            if (data.contains("event") && data.contains("stop")) {
                                processSseData(dataBuilder.toString(), fullResponse, responseComplete);
                                dataBuilder.setLength(0);
                            }
                        }
                    } catch (IOException e) {
                        System.err.printf("读取响应失败: %s，问题: %s%n", e.getMessage(), message);
                        responseComplete.countDown();
                    }
                })
                .exceptionally(ex -> {
                    System.err.printf("请求异常: %s，问题: %s%n", ex.getMessage(), message);
                    responseComplete.countDown();
                    return null;
                });

        // 等待回答完成
        responseComplete.await();
    }

    // 处理SSE数据
    private static int processSseData(String data, StringBuilder fullResponse, CountDownLatch latch) {
        int totalLength = 0;
        try {
            String[] jsonObjects = splitMultipleJsonObjects(data);
            for (String jsonStr : jsonObjects) {
                if (jsonStr.trim().isEmpty()) {
                    continue;
                }

                JSONObject jsonObj = JSONUtil.parseObj(jsonStr);
                String text = jsonObj.getStr("text", "");
                if (!text.isEmpty()) {
                    fullResponse.append(text);
                    System.out.print(text);
                    totalLength += text.length();
                }

                String event = jsonObj.getStr("event", "");
                if ("stop".equals(event)) {
                    System.out.println("\n回答已完成");
                    latch.countDown();
                }
            }
        } catch (Exception e) {
            System.err.printf("解析SSE数据失败: %s%n", e.getMessage());
        }
        return totalLength;
    }

    // 分割多个JSON对象
    private static String[] splitMultipleJsonObjects(String data) {
        List<String> jsonObjects = new ArrayList<>();
        int braceCount = 0;
        int startIndex = 0;

        for (int i = 0; i < data.length(); i++) {
            char c = data.charAt(i);
            if (c == '{') {
                if (braceCount == 0) {
                    startIndex = i;
                }
                braceCount++;
            } else if (c == '}') {
                braceCount--;
                if (braceCount == 0 && i > startIndex) {
                    jsonObjects.add(data.substring(startIndex, i + 1));
                }
            }
        }
        return jsonObjects.toArray(new String[0]);
    }

    // 保存问题到文件
    private static void saveQuestionsToFile(List<String> questions, String filePath) throws IOException {
        File file = new File(filePath);
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(file))) {
            for (String question : questions) {
                bw.write(question);
                bw.newLine();
            }
        }
    }
}