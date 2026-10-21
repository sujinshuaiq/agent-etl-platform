package com.dataforge.etl.agent.controller;

import com.dataforge.etl.agent.entity.Agent;
import com.dataforge.etl.agent.service.AgentService;
import com.dataforge.etl.common.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Agent 管理接口:/api/agents
 */
@Tag(name = "Agent 管理")
@RestController
@RequestMapping("/api/agents")
public class AgentController {

    private final AgentService agentService;

    public AgentController(AgentService agentService) {
        this.agentService = agentService;
    }

    @Operation(summary = "查询全部 Agent(含运行指标)")
    @GetMapping
    public ApiResponse<List<Agent>> list() {
        return ApiResponse.ok(agentService.listAll());
    }

    @Operation(summary = "Agent 详情")
    @GetMapping("/{id}")
    public ApiResponse<Agent> detail(@PathVariable Long id) {
        return ApiResponse.ok(agentService.getById(id));
    }

    @Operation(summary = "新增 Agent")
    @PostMapping
    public ApiResponse<Agent> create(@Valid @RequestBody Agent agent) {
        return ApiResponse.ok(agentService.create(agent));
    }

    @Operation(summary = "更新 Agent")
    @PutMapping("/{id}")
    public ApiResponse<Agent> update(@PathVariable Long id, @Valid @RequestBody Agent agent) {
        return ApiResponse.ok(agentService.update(id, agent));
    }

    @Operation(summary = "删除 Agent")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        agentService.delete(id);
        return ApiResponse.ok();
    }

    @Operation(summary = "启动 Agent")
    @PostMapping("/{id}/start")
    public ApiResponse<Agent> start(@PathVariable Long id) {
        return ApiResponse.ok(agentService.start(id));
    }

    @Operation(summary = "停止 Agent")
    @PostMapping("/{id}/stop")
    public ApiResponse<Agent> stop(@PathVariable Long id) {
        return ApiResponse.ok(agentService.stop(id));
    }

    @Operation(summary = "重启 Agent")
    @PostMapping("/{id}/restart")
    public ApiResponse<Agent> restart(@PathVariable Long id) {
        return ApiResponse.ok(agentService.restart(id));
    }
}
