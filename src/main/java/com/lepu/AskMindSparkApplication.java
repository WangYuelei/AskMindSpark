package com.lepu;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
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
import java.util.regex.Pattern;

@SpringBootApplication
public class AskMindSparkApplication implements CommandLineRunner {

	// API基础地址
	private static final String API_BASE_URL = "https://aiapi.lepumedical.com";

	// 配置文件路径（Windows桌面）
	private static final String CONFIG_FILE_PATH = "C:\\Users\\%s\\Desktop\\chatbot_config.txt";

	// SSE数据模式
	private static final Pattern DATA_PATTERN = Pattern.compile("^data: (.*)$");

	// HTTP客户端
	private static final HttpClient client = HttpClient.newBuilder()
			.version(HttpClient.Version.HTTP_1_1)
			.connectTimeout(Duration.ofSeconds(15))
			.followRedirects(HttpClient.Redirect.NORMAL)
			.build();

	// 随机数生成器
	private static final Random random = new Random();
	// 批次大小范围
	private static final int MIN_BATCH_SIZE = 10;
	private static final int MAX_BATCH_SIZE = 20;
	// 会话ID
	private static String sessionId;
	// 配置参数
	private static String QUESTION_FILE_PATH;
	private static String API_KEY;
	private static long MIN_WAIT_TIME; // 问题间最小等待时间（秒）
	private static long MAX_WAIT_TIME; // 问题间最大等待时间（秒）
	private static long MIN_BATCH_WAIT_TIME; // 批次间最小等待时间（秒）
	private static long MAX_BATCH_WAIT_TIME; // 批次间最大等待时间（秒）
	// 上次回答完成时间
	private static long lastAnswerCompleteTime = 0;

	// 统计信息
	private static int totalQuestions = 0;
	private static int completedQuestions = 0;
	private static int currentBatch = 0;

	public static void main(String[] args) {
		SpringApplication.run(AskMindSparkApplication.class, args);
	}

	/**
	 * 处理SSE数据
	 */
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
					// 记录回答完成时间
					lastAnswerCompleteTime = System.currentTimeMillis();
					latch.countDown();
				}
			}
		} catch (Exception e) {
			System.err.printf("解析SSE数据失败: %s%n", e.getMessage());
		}
		return totalLength;
	}

	/**
	 * 分割多个JSON对象
	 */
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

	@Override
	public void run(String... args) throws Exception {
		System.out.println("===== AskMindSpark 客户端启动 =====");

		// 加载配置
		loadConfiguration();

		// 打印配置信息
		printConfiguration();

		// 读取问题列表
		List<String> questions = readQuestionsFromFile(QUESTION_FILE_PATH);
		totalQuestions = questions.size();
		System.out.printf("成功读取 %d 个问题%n", totalQuestions);

		if (totalQuestions == 0) {
			System.out.println("没有可处理的问题，程序退出");
			return;
		}

		// 处理问题（分批处理）
		processQuestionsInBatches(questions);

		System.out.println("===== 所有问题处理完成 =====");
	}

	/**
	 * 按批次处理问题
	 */
	private void processQuestionsInBatches(List<String> questions) throws IOException, InterruptedException {
		if (questions == null || questions.isEmpty()) {
			System.out.println("没有问题可处理");
			return;
		}

		System.out.println("开始按批次处理问题...");
		int questionNumber = 1;
		int remainingQuestions = questions.size();

		while (!questions.isEmpty()) {
			// 计算当前批次大小（10-20的随机数）
			int batchSize = getRandomBatchSize();
			batchSize = Math.min(batchSize, remainingQuestions);

			currentBatch++;
			System.out.printf("--- 开始处理第 %d 批次，批次大小: %d ---%n", currentBatch, batchSize);

			// 创建新会话
			if (!createSession()) {
				System.out.println("会话创建失败，程序退出");
				return;
			}

			// 处理当前批次的问题
			for (int i = 0; i < batchSize && !questions.isEmpty(); i++) {
				String question = questions.get(0);
				System.out.printf("处理问题 %d/%d (批次 %d/%d): %s%n",
						questionNumber++, totalQuestions, i + 1, batchSize, question);

				// 发送问题并获取回答
				sendQuestionAndGetAnswer(question);

				// 从列表中移除已处理问题
				questions.remove(question);
				completedQuestions++;

				// 更新问题文件
				saveQuestionsToFile(questions, QUESTION_FILE_PATH);
				remainingQuestions = questions.size();
				System.out.printf("剩余 %d 个问题%n", remainingQuestions);

				// 批次内问题间的等待（从配置文件获取）
				if (i < batchSize - 1 && !questions.isEmpty()) {
					waitRandomTimeAfterAnswer();
				}
			}

			// 批次处理完成，重置回答时间
			lastAnswerCompleteTime = 0;

			System.out.printf("--- 第 %d 批次处理完成，剩余 %d 个问题 ---%n", currentBatch, remainingQuestions);

			// 批次间的等待（从配置文件获取）
			if (!questions.isEmpty()) {
				waitRandomTimeBetweenBatches();
			}
		}
	}

	/**
	 * 生成10-20的随机批次大小
	 */
	private int getRandomBatchSize() {
		return random.nextInt(MAX_BATCH_SIZE - MIN_BATCH_SIZE + 1) + MIN_BATCH_SIZE;
	}

	/**
	 * 加载配置文件
	 */
	private void loadConfiguration() throws IOException {
		String userName = System.getProperty("user.name");
		String configPath = String.format(CONFIG_FILE_PATH, userName);
		File configFile = new File(configPath);

		if (!configFile.exists()) {
			createDefaultConfig(configPath);
		}

		try (BufferedReader reader = new BufferedReader(new FileReader(configFile))) {
			String line;
			while ((line = reader.readLine()) != null) {
				line = line.trim();
				if (line.isEmpty() || line.startsWith("#")) {
					continue;
				}

				String[] parts = line.split("=", 2);
				if (parts.length == 2) {
					String key = parts[0].trim();
					String value = parts[1].trim();

					switch (key) {
						case "QUESTION_FILE_PATH":
							QUESTION_FILE_PATH = value;
							break;
						case "API_KEY":
							API_KEY = value;
							break;
						case "MIN_WAIT_TIME":
							try {
								MIN_WAIT_TIME = Long.parseLong(value) * 1000; // 秒转毫秒
							} catch (NumberFormatException e) {
								MIN_WAIT_TIME = 60000; // 默认60秒
								System.err.println("MIN_WAIT_TIME配置无效，使用默认值60秒");
							}
							break;
						case "MAX_WAIT_TIME":
							try {
								MAX_WAIT_TIME = Long.parseLong(value) * 1000; // 秒转毫秒
							} catch (NumberFormatException e) {
								MAX_WAIT_TIME = 180000; // 默认180秒
								System.err.println("MAX_WAIT_TIME配置无效，使用默认值180秒");
							}
							break;
						case "MIN_BATCH_WAIT_TIME":
							try {
								MIN_BATCH_WAIT_TIME = Long.parseLong(value) * 1000; // 秒转毫秒
							} catch (NumberFormatException e) {
								MIN_BATCH_WAIT_TIME = 60000; // 默认60秒
								System.err.println("MIN_BATCH_WAIT_TIME配置无效，使用默认值60秒");
							}
							break;
						case "MAX_BATCH_WAIT_TIME":
							try {
								MAX_BATCH_WAIT_TIME = Long.parseLong(value) * 1000; // 秒转毫秒
							} catch (NumberFormatException e) {
								MAX_BATCH_WAIT_TIME = 120000; // 默认120秒
								System.err.println("MAX_BATCH_WAIT_TIME配置无效，使用默认值120秒");
							}
							break;
					}
				}
			}

			// 验证必填配置
			if (!StringUtils.hasText(QUESTION_FILE_PATH) || !StringUtils.hasText(API_KEY)) {
				throw new IllegalStateException("配置文件缺少必要参数：QUESTION_FILE_PATH 或 API_KEY");
			}

			// 确保MAX不小于MIN
			if (MAX_WAIT_TIME < MIN_WAIT_TIME) {
				long temp = MIN_WAIT_TIME;
				MIN_WAIT_TIME = MAX_WAIT_TIME;
				MAX_WAIT_TIME = temp;
				System.out.println("警告：MAX_WAIT_TIME小于MIN_WAIT_TIME，已自动交换两者值");
			}

			if (MAX_BATCH_WAIT_TIME < MIN_BATCH_WAIT_TIME) {
				long temp = MIN_BATCH_WAIT_TIME;
				MIN_BATCH_WAIT_TIME = MAX_BATCH_WAIT_TIME;
				MAX_BATCH_WAIT_TIME = temp;
				System.out.println("警告：MAX_BATCH_WAIT_TIME小于MIN_BATCH_WAIT_TIME，已自动交换两者值");
			}

			System.out.println("配置加载成功");
		}
	}

	/**
	 * 创建默认配置文件
	 */
	private void createDefaultConfig(String configPath) throws IOException {
		System.out.println("配置文件不存在，创建默认配置文件");
		File configFile = new File(configPath);
		try (BufferedWriter writer = new BufferedWriter(new FileWriter(configFile))) {
			writer.write("# AskMindSpark 客户端配置文件");
			writer.newLine();
			writer.write("# 问题文件路径（每行一个问题）");
			writer.newLine();
			writer.write("QUESTION_FILE_PATH=C:\\Users\\" + System.getProperty("user.name") + "\\Desktop\\q.txt");
			writer.newLine();
			writer.write("# MindSpark API密钥（bear空格之后的字符串）");
			writer.newLine();
			writer.write("API_KEY=your_api_key_here");
			writer.newLine();
			writer.write("# 问题间最小等待时间（秒），默认60秒");
			writer.newLine();
			writer.write("MIN_WAIT_TIME=60");
			writer.newLine();
			writer.write("# 问题间最大等待时间（秒），默认180秒");
			writer.newLine();
			writer.write("MAX_WAIT_TIME=180");
			writer.newLine();
			writer.write("# 批次间最小等待时间（秒），默认60秒");
			writer.newLine();
			writer.write("MIN_BATCH_WAIT_TIME=60");
			writer.newLine();
			writer.write("# 批次间最大等待时间（秒），默认120秒");
			writer.newLine();
			writer.write("MAX_BATCH_WAIT_TIME=120");
		}
		System.out.printf("默认配置文件已创建至: %s%n", configPath);
		System.out.println("请修改配置文件中的参数后重新运行");
		System.exit(1);
	}

	/**
	 * 打印配置信息
	 */
	private void printConfiguration() {
		System.out.println("\n--- 配置信息 ---");
		System.out.printf("API地址: %s%n", API_BASE_URL);
		System.out.printf("问题文件: %s%n", QUESTION_FILE_PATH);
		System.out.printf("API密钥: %s%n", API_KEY.startsWith("sk-") ? API_KEY.substring(0, 6) + "***" : "***");
		System.out.printf("问题间最小等待时间: %d 秒 (%d 毫秒)%n",
				MIN_WAIT_TIME / 1000, MIN_WAIT_TIME);
		System.out.printf("问题间最大等待时间: %d 秒 (%d 毫秒)%n",
				MAX_WAIT_TIME / 1000, MAX_WAIT_TIME);
		System.out.printf("批次间最小等待时间: %d 秒 (%d 毫秒)%n",
				MIN_BATCH_WAIT_TIME / 1000, MIN_BATCH_WAIT_TIME);
		System.out.printf("批次间最大等待时间: %d 秒 (%d 毫秒)%n",
				MAX_BATCH_WAIT_TIME / 1000, MAX_BATCH_WAIT_TIME);
		System.out.printf("批次大小范围: %d-%d 个问题%n", MIN_BATCH_SIZE, MAX_BATCH_SIZE);
		System.out.println("--- 配置信息结束 ---\n");
	}

	/**
	 * 从文件读取问题列表
	 */
	private List<String> readQuestionsFromFile(String filePath) throws IOException {
		List<String> questions = new ArrayList<>();
		File file = new File(filePath);

		if (!file.exists() || file.isDirectory()) {
			throw new FileNotFoundException("问题文件不存在或路径不正确: " + filePath);
		}

		try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
			String line;
			while ((line = reader.readLine()) != null) {
				line = line.trim();
				if (StringUtils.hasText(line)) {
					questions.add(line);
				}
			}
		}

		return questions;
	}

	/**
	 * 创建会话
	 */
	private boolean createSession() throws IOException, InterruptedException {
		System.out.println("正在创建会话...");

		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(API_BASE_URL + "/api/startChat"))
				.header("Authorization", "Bearer " + API_KEY)
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
	/**
	 * 发送问题并获取回答（处理SSE流式响应）
	 */
	private void sendQuestionAndGetAnswer(String question) throws IOException, InterruptedException {
		System.out.printf("发送问题: %s%n", question);

		String requestBody = String.format("{\"message\":\"%s\",\"chatId\":\"%s\",\"ifDeep\":false,\"ifWebSearch\":false,\"ifWebParse\":false}",
				question.replace("\"", "\\\""), sessionId);

		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(API_BASE_URL + "/flux/chat"))
				.header("Content-Type", "application/json")
				.header("Authorization", "Bearer " + API_KEY)
				.POST(HttpRequest.BodyPublishers.ofString(requestBody))
				.build();

		StringBuilder fullResponse = new StringBuilder();
		CountDownLatch responseComplete = new CountDownLatch(1);

		client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
				.thenAccept(response -> {
					if (response.statusCode() != 200) {
						System.err.printf("请求失败，状态码: %d，问题: %s%n", response.statusCode(), question);
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
						System.err.printf("读取响应失败: %s，问题: %s%n", e.getMessage(), question);
						responseComplete.countDown();
					}
				})
				.exceptionally(ex -> {
					System.err.printf("请求异常: %s，问题: %s%n", ex.getMessage(), question);
					responseComplete.countDown();
					return null;
				});

		// 等待回答完成
		responseComplete.await();
	}
	/**
	 * 保存剩余问题到文件
	 */
	private void saveQuestionsToFile(List<String> questions, String filePath) throws IOException {
		File file = new File(filePath);
		try (BufferedWriter writer = new BufferedWriter(new FileWriter(file))) {
			for (String q : questions) {
				writer.write(q);
				writer.newLine();
			}
		}
	}
	/**
	 * 问题间的随机等待（带倒计时）
	 */
	private void waitRandomTimeAfterAnswer() throws InterruptedException {
		long currentTime = System.currentTimeMillis();
		long waitTime = random.nextLong(MAX_WAIT_TIME - MIN_WAIT_TIME + 1) + MIN_WAIT_TIME;
		long actualWaitTime = Math.max(0, lastAnswerCompleteTime + waitTime - currentTime);

		if (actualWaitTime > 0) {
			System.out.printf("问题间等待 %d 秒后处理下一个问题...", actualWaitTime / 1000);
			startCountdown(actualWaitTime);
		} else {
			System.out.println("无需等待，立即处理下一个问题");
		}
	}
	/**
	 * 批次间的随机等待（带倒计时）
	 */
	private void waitRandomTimeBetweenBatches() throws InterruptedException {
		long waitTime = random.nextLong(MAX_BATCH_WAIT_TIME - MIN_BATCH_WAIT_TIME + 1) + MIN_BATCH_WAIT_TIME;
		System.out.printf("批次间等待 %d 秒后开始下一批处理...", waitTime / 1000);
		startCountdown(waitTime);
	}
	/**
	 * 倒计时显示
	 */
	private void startCountdown(long milliseconds) throws InterruptedException {
		long seconds = milliseconds / 1000;
		for (long i = seconds; i > 0; i--) {
			System.out.printf("\r倒计时: %d 秒", i);
			Thread.sleep(1000);
		}
		System.out.println("\r倒计时: 0 秒 - 继续执行");
	}
}