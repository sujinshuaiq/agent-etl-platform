package com.dataforge.etl.alert.controller;

import com.dataforge.etl.alert.entity.Alert;
import com.dataforge.etl.alert.service.AlertService;
import com.dataforge.etl.common.api.ApiResponse;
import com.dataforge.etl.common.api.PageResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

/**
 * 告警接口:/api/alerts
 */
@Tag(name = "告警中心")
@RestController
@RequestMapping("/api/alerts")
public class AlertController {

    private final AlertService alertService;

    public AlertController(AlertService alertService) {
        this.alertService = alertService;
    }

    @Operation(summary = "告警分页查询(read 可选过滤)")
    @GetMapping
    public ApiResponse<PageResult<Alert>> list(@RequestParam(required = false) Boolean read,
                                               @RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(PageResult.from(alertService.page(read, page, size)));
    }

    @Operation(summary = "标记已读")
    @PostMapping("/{id}/read")
    public ApiResponse<Alert> markRead(@PathVariable Long id) {
        return ApiResponse.ok(alertService.markRead(id));
    }

    @Operation(summary = "全部标记已读")
    @PostMapping("/read-all")
    public ApiResponse<Integer> markAllRead() {
        return ApiResponse.ok(alertService.markAllRead());
    }

    @Operation(summary = "删除告警")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        alertService.delete(id);
        return ApiResponse.ok();
    }
}
