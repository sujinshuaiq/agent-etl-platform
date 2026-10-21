package com.dataforge.etl.message;

import com.dataforge.etl.common.api.ApiResponse;
import com.dataforge.etl.common.api.PageResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Agent 通信接口:/api/messages
 */
@Tag(name = "Agent 通信")
@RestController
@RequestMapping("/api/messages")
public class MessageController {

    private final MessageBus messageBus;

    public MessageController(MessageBus messageBus) {
        this.messageBus = messageBus;
    }

    @Operation(summary = "消息分页查询(runId 可选;不传则全量,最新在前)")
    @GetMapping
    public ApiResponse<PageResult<AgentMessage>> list(@RequestParam(required = false) Long runId,
                                                      @RequestParam(defaultValue = "0") int page,
                                                      @RequestParam(defaultValue = "50") int size) {
        return ApiResponse.ok(PageResult.from(messageBus.page(runId, page, size)));
    }

    @Operation(summary = "通信统计(总量/交接数/广播数)")
    @GetMapping("/stats")
    public ApiResponse<Map<String, Object>> stats() {
        return ApiResponse.ok(messageBus.stats());
    }
}
