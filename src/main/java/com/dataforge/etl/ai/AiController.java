package com.dataforge.etl.ai;

import com.dataforge.etl.ai.entity.AiChatMessage;
import com.dataforge.etl.ai.nlp.IntentType;
import com.dataforge.etl.ai.nlp.SlotFiller;
import com.dataforge.etl.common.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 助手接口:/api/ai
 */
@Tag(name = "AI 助手")
@RestController
@RequestMapping("/api/ai")
public class AiController {

    private final AiAssistantService aiAssistantService;

    public AiController(AiAssistantService aiAssistantService) {
        this.aiAssistantService = aiAssistantService;
    }

    @Operation(summary = "AI 助手状态(含模拟模式标记)")
    @GetMapping("/status")
    public ApiResponse<Map<String, Object>> status() {
        return ApiResponse.ok(aiAssistantService.status());
    }

    @Operation(summary = "对话(支持多轮槽位补全)")
    @PostMapping("/chat")
    public ApiResponse<Map<String, Object>> chat(@RequestBody Map<String, String> body) {
        String message = body.get("message");
        String sessionId = body.get("sessionId");
        return ApiResponse.ok(aiAssistantService.chat(message, sessionId));
    }

    @Operation(summary = "会话历史")
    @GetMapping("/chat/history")
    public ApiResponse<List<Map<String, Object>>> history(@RequestParam String sessionId) {
        List<Map<String, Object>> history = new ArrayList<>();
        for (AiChatMessage msg : aiAssistantService.history(sessionId)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("role", msg.getRole());
            item.put("content", msg.getContent());
            item.put("intent", msg.getIntent());
            item.put("createdAt", msg.getCreatedAt());
            history.add(item);
        }
        return ApiResponse.ok(history);
    }

    @Operation(summary = "检索调试(混合检索三项分数)")
    @PostMapping("/retrieve")
    public ApiResponse<Map<String, Object>> retrieve(@RequestBody Map<String, Object> body) {
        String query = body.get("query") == null ? null : body.get("query").toString();
        Integer topK = body.get("topK") == null ? null : Integer.parseInt(body.get("topK").toString());
        return ApiResponse.ok(aiAssistantService.retrieveDebug(query, topK));
    }

    @Operation(summary = "运行诊断")
    @PostMapping("/diagnose/{runId}")
    public ApiResponse<Map<String, Object>> diagnose(@PathVariable Long runId) {
        return ApiResponse.ok(aiAssistantService.diagnose(runId));
    }

    @Operation(summary = "自然语言生成管道(DRAFT)")
    @PostMapping("/generate-pipeline")
    public ApiResponse<Map<String, Object>> generatePipeline(@RequestBody Map<String, String> body) {
        return ApiResponse.ok(aiAssistantService.generatePipeline(body.get("description")));
    }

    @Operation(summary = "意图清单(含槽位定义与示例)")
    @GetMapping("/intents")
    public ApiResponse<List<Map<String, Object>>> intents() {
        Map<IntentType, List<SlotFiller.SlotDef>> defs = aiAssistantService.intentDefinitions();
        Map<IntentType, String> descriptions = Map.ofEntries(
                Map.entry(IntentType.QUERY_OVERVIEW, "查询平台总览统计"),
                Map.entry(IntentType.QUERY_FAILED_RUNS, "查询失败运行"),
                Map.entry(IntentType.QUERY_RUN_DETAIL, "查询某次运行详情"),
                Map.entry(IntentType.QUERY_AGENTS, "查询 Agent 状态"),
                Map.entry(IntentType.QUERY_QUALITY, "查询数据质量通过率"),
                Map.entry(IntentType.TRIGGER_PIPELINE, "触发管道运行(危险操作)"),
                Map.entry(IntentType.DIAGNOSE_RUN, "诊断运行根因"),
                Map.entry(IntentType.GENERATE_PIPELINE, "自然语言生成管道"),
                Map.entry(IntentType.KNOWLEDGE_QA, "平台知识问答(RAG)"),
                Map.entry(IntentType.HELP, "帮助"),
                Map.entry(IntentType.UNKNOWN, "未识别"));
        Map<IntentType, List<String>> examples = Map.ofEntries(
                Map.entry(IntentType.QUERY_OVERVIEW, List.of("平台现在怎么样", "给我看看总览")),
                Map.entry(IntentType.QUERY_FAILED_RUNS, List.of("最近有失败的运行吗", "近 48 小时失败情况")),
                Map.entry(IntentType.QUERY_RUN_DETAIL, List.of("运行 123 的详情")),
                Map.entry(IntentType.QUERY_AGENTS, List.of("Agent 都在线吗")),
                Map.entry(IntentType.QUERY_QUALITY, List.of("质量通过率怎么样")),
                Map.entry(IntentType.TRIGGER_PIPELINE, List.of("触发管道 1", "运行一下用户数据日更管道")),
                Map.entry(IntentType.DIAGNOSE_RUN, List.of("诊断运行 123", "帮我诊断一下")),
                Map.entry(IntentType.GENERATE_PIPELINE, List.of("帮我创建一个每天同步订单数据并质检的管道")),
                Map.entry(IntentType.KNOWLEDGE_QA, List.of("什么是数据质量", "管道失败了怎么排查")),
                Map.entry(IntentType.HELP, List.of("你会什么", "帮助")),
                Map.entry(IntentType.UNKNOWN, List.of()));
        List<Map<String, Object>> result = new ArrayList<>();
        for (IntentType intent : IntentType.values()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("intent", intent.name());
            item.put("description", descriptions.get(intent));
            List<SlotFiller.SlotDef> slots = defs.getOrDefault(intent, List.of());
            item.put("requiredSlots", slots.stream().filter(SlotFiller.SlotDef::required)
                    .map(SlotFiller.SlotDef::name).toList());
            item.put("optionalSlots", slots.stream().filter(s -> !s.required())
                    .map(SlotFiller.SlotDef::name).toList());
            item.put("examples", examples.get(intent));
            result.add(item);
        }
        return ApiResponse.ok(result);
    }
}
