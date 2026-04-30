package com.lepu;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.util.StringUtils;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CountDownLatch;

@SpringBootApplication
public class AskMindSparkApplication implements CommandLineRunner {

	@Value("${chatbot.api-base-url}")
	private String apiBaseUrl;

	@Value("${chatbot.auth-token}")
	private String authToken;

	@Value("${chatbot.question-file-path}")
	private String questionFilePath;

	@Value("${chatbot.min-wait-time:60}")
	private long minWaitTime;

	@Value("${chatbot.max-wait-time:180}")
	private long maxWaitTime;

	@Value("${chatbot.min-batch-wait-time:60}")
	private long minBatchWaitTime;

	@Value("${chatbot.max-batch-wait-time:120}")
	private long maxBatchWaitTime;

	private static final HttpClient client = HttpClient.newBuilder()
			.version(HttpClient.Version.HTTP_1_1)
			.connectTimeout(Duration.ofSeconds(15))
			.followRedirects(HttpClient.Redirect.NORMAL)
			.build();

	private static final Random random = new Random();
	private static final int MIN_BATCH_SIZE = 10;
	private static final int MAX_BATCH_SIZE = 20;

	private String sessionId;
	private long lastAnswerCompleteTime = 0;
	private int totalQuestions = 0;
	private int completedQuestions = 0;
	private int currentBatch = 0;

	public static void main(String[] args) {
		SpringApplication.run(AskMindSparkApplication.class, args);
	}

	@Override
	public void run(String... args) throws Exception {
		System.out.println("===== AskMindSpark 客户端启动 =====");
		System.out.printf("API地址: %s%n问题文件: %s%n", apiBaseUrl, questionFilePath);

		// 秒转毫秒
		minWaitTime *= 1000;
		maxWaitTime *= 1000;
		minBatchWaitTime *= 1000;
		maxBatchWaitTime *= 1000;

		List<String> questions = readQuestionsFromFile(questionFilePath);
		totalQuestions = questions.size();
		System.out.printf("成功读取 %d 个问题%n", totalQuestions);

		if (totalQuestions == 0) {
			System.out.println("没有可处理的问题，程序退出");
			return;
		}

		processQuestionsInBatches(questions);
		System.out.println("===== 所有问题处理完成 =====");
	}

	private void processQuestionsInBatches(List<String> questions) throws IOException, InterruptedException {
		int questionNumber = 1;

		while (!questions.isEmpty()) {
			int batchSize = Math.min(random.nextInt(MAX_BATCH_SIZE - MIN_BATCH_SIZE + 1) + MIN_BATCH_SIZE, questions.size());
			currentBatch++;
			System.out.printf("--- 开始处理第 %d 批次，批次大小: %d ---%n", currentBatch, batchSize);

			if (!createSession()) {
				System.out.println("会话创建失败，程序退出");
				return;
			}

			for (int i = 0; i < batchSize && !questions.isEmpty(); i++) {
				String question = questions.get(0);
				System.out.printf("处理问题 %d/%d: %s%n", questionNumber++, totalQuestions, question);

				sendQuestionAndGetAnswer(question);
				questions.remove(0);
				completedQuestions++;
				saveQuestionsToFile(questions, questionFilePath);
				System.out.printf("剩余 %d 个问题%n", questions.size());

				if (i < batchSize - 1 && !questions.isEmpty()) {
					waitRandom(minWaitTime, maxWaitTime, "问题间");
				}
			}

			lastAnswerCompleteTime = 0;
			System.out.printf("--- 第 %d 批次处理完成 ---%n", currentBatch);

			if (!questions.isEmpty()) {
				waitRandom(minBatchWaitTime, maxBatchWaitTime, "批次间");
			}
		}
	}

	private boolean createSession() throws IOException, InterruptedException {
		System.out.println("正在创建会话...");
		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(apiBaseUrl + "/api/startChat"))
				.header("Authorization", authToken)
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
				System.err.println("响应中未找到有效的会话ID，实际响应: " + response.body());
			} catch (Exception e) {
				System.err.printf("解析JSON响应失败: %s%n", e.getMessage());
			}
		} else {
			System.err.printf("创建会话失败，状态码: %d，响应: %s%n", response.statusCode(), response.body());
		}
		return false;
	}

	private void sendQuestionAndGetAnswer(String question) throws IOException, InterruptedException {
		String requestBody = String.format(
				"{\"message\":\"%s\",\"chatId\":\"%s\",\"ifDeep\":false,\"ifWebSearch\":false,\"ifWebParse\":false}",
				question.replace("\\", "\\\\").replace("\"", "\\\""), sessionId);

		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(apiBaseUrl + "/flux/chat"))
				.header("Content-Type", "application/json")
				.header("Authorization", authToken)
				.POST(HttpRequest.BodyPublishers.ofString(requestBody))
				.build();

		CountDownLatch latch = new CountDownLatch(1);

		client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
				.thenAccept(response -> {
					if (response.statusCode() != 200) {
						System.err.printf("请求失败，状态码: %d%n", response.statusCode());
						latch.countDown();
						return;
					}
					try (BufferedReader reader = new BufferedReader(
							new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
						String line;
						StringBuilder dataBuilder = new StringBuilder();
						while ((line = reader.readLine()) != null) {
							line = line.trim();
							if (line.isEmpty() || !line.startsWith("data:")) continue;
							String data = line.substring("data:".length()).trim();
							dataBuilder.append(data);
							if (data.contains("\"stop\"")) {
								processSseData(dataBuilder.toString(), latch);
								dataBuilder.setLength(0);
							}
						}
					} catch (IOException e) {
						System.err.printf("读取响应失败: %s%n", e.getMessage());
					} finally {
						latch.countDown();
					}
				})
				.exceptionally(ex -> {
					System.err.printf("请求异常: %s%n", ex.getMessage());
					latch.countDown();
					return null;
				});

		latch.await();
	}

	private void processSseData(String data, CountDownLatch latch) {
		for (String jsonStr : splitJsonObjects(data)) {
			if (jsonStr.trim().isEmpty()) continue;
			try {
				JSONObject obj = JSONUtil.parseObj(jsonStr);
				String text = obj.getStr("text", "");
				if (!text.isEmpty()) System.out.print(text);
				if ("stop".equals(obj.getStr("event", ""))) {
					System.out.println("\n回答已完成");
					lastAnswerCompleteTime = System.currentTimeMillis();
					latch.countDown();
				}
			} catch (Exception ignored) {}
		}
	}

	private static String[] splitJsonObjects(String data) {
		List<String> result = new ArrayList<>();
		int depth = 0, start = 0;
		for (int i = 0; i < data.length(); i++) {
			char c = data.charAt(i);
			if (c == '{') { if (depth++ == 0) start = i; }
			else if (c == '}' && --depth == 0) result.add(data.substring(start, i + 1));
		}
		return result.toArray(new String[0]);
	}

	private List<String> readQuestionsFromFile(String filePath) throws IOException {
		List<String> questions = new ArrayList<>();
		try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
			String line;
			while ((line = reader.readLine()) != null) {
				if (StringUtils.hasText(line)) questions.add(line.trim());
			}
		}
		return questions;
	}

	private void saveQuestionsToFile(List<String> questions, String filePath) throws IOException {
		try (BufferedWriter writer = new BufferedWriter(new FileWriter(filePath))) {
			for (String q : questions) { writer.write(q); writer.newLine(); }
		}
	}

	private void waitRandom(long min, long max, String label) throws InterruptedException {
		long wait = random.nextLong(max - min + 1) + min;
		System.out.printf("%s等待 %d 秒...%n", label, wait / 1000);
		startCountdown(wait);
	}

	private void startCountdown(long ms) throws InterruptedException {
		for (long i = ms / 1000; i > 0; i--) {
			System.out.printf("\r倒计时: %d 秒", i);
			Thread.sleep(1000);
		}
		System.out.println("\r倒计时: 0 秒 - 继续执行");
	}
}
