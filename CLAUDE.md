# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run

```bash
./mvnw package                          # build JAR
./mvnw spring-boot:run                  # run directly
./mvnw test                             # run tests
java -jar target/askMindSpark-0.0.1-SNAPSHOT.jar  # run built JAR
```

## Configuration

Before running, set `api.base-url` in `src/main/resources/application.properties`.

Runtime config is read from `C:\Users\<username>\Desktop\chatbot_config.txt`:

```
QUESTION_FILE_PATH=C:\Users\<user>\Desktop\q.txt
API_KEY=your_api_key_here
MIN_WAIT_TIME=60
MAX_WAIT_TIME=180
MIN_BATCH_WAIT_TIME=60
MAX_BATCH_WAIT_TIME=120
```

## Architecture

Single-class Spring Boot CLI app (no web layer). `AskMindSparkApplication` implements `CommandLineRunner` — all logic runs in `run()`.

**Flow:**
1. Load config from `chatbot_config.txt`
2. Read questions from the file at `QUESTION_FILE_PATH` (one per line)
3. Process in randomized batches of 10–20:
   - `GET /api/startChat` → get session ID
   - `POST /flux/chat` per question → consume SSE stream line-by-line
   - Random delay between questions (`MIN_WAIT_TIME`–`MAX_WAIT_TIME` seconds)
   - Random delay between batches (`MIN_BATCH_WAIT_TIME`–`MAX_BATCH_WAIT_TIME` seconds)
4. Question file is updated in-place after each question (crash-resume support)

Uses `java.net.http.HttpClient` directly (no Spring WebClient). SSE responses are parsed manually from the response `InputStream`.
