package com.dataforge.etl.quality.controller;

import com.dataforge.etl.common.api.ApiResponse;
import com.dataforge.etl.common.api.PageResult;
import com.dataforge.etl.quality.entity.QualityResult;
import com.dataforge.etl.quality.entity.QualityRule;
import com.dataforge.etl.quality.service.QualityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 数据质量接口:/api/quality
 */
@Tag(name = "数据质量")
@RestController
@RequestMapping("/api/quality")
public class QualityController {

    private final QualityService qualityService;

    public QualityController(QualityService qualityService) {
        this.qualityService = qualityService;
    }

    @Operation(summary = "质量规则列表")
    @GetMapping("/rules")
    public ApiResponse<List<QualityRule>> listRules() {
        return ApiResponse.ok(qualityService.listRules());
    }

    @Operation(summary = "新增质量规则")
    @PostMapping("/rules")
    public ApiResponse<QualityRule> createRule(@Valid @RequestBody QualityRule rule) {
        return ApiResponse.ok(qualityService.createRule(rule));
    }

    @Operation(summary = "更新质量规则")
    @PutMapping("/rules/{id}")
    public ApiResponse<QualityRule> updateRule(@PathVariable Long id, @Valid @RequestBody QualityRule rule) {
        return ApiResponse.ok(qualityService.updateRule(id, rule));
    }

    @Operation(summary = "删除质量规则")
    @DeleteMapping("/rules/{id}")
    public ApiResponse<Void> deleteRule(@PathVariable Long id) {
        qualityService.deleteRule(id);
        return ApiResponse.ok();
    }

    @Operation(summary = "检查结果分页查询")
    @GetMapping("/results")
    public ApiResponse<PageResult<QualityResult>> results(@RequestParam(required = false) Long ruleId,
                                                          @RequestParam(defaultValue = "0") int page,
                                                          @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(PageResult.from(qualityService.pageResults(ruleId, page, size)));
    }
}
