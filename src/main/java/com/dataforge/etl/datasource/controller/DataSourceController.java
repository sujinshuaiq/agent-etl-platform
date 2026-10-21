package com.dataforge.etl.datasource.controller;

import com.dataforge.etl.common.api.ApiResponse;
import com.dataforge.etl.datasource.entity.DataSource;
import com.dataforge.etl.datasource.service.DataSourceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 数据源管理接口:/api/datasources
 */
@Tag(name = "数据源管理")
@RestController
@RequestMapping("/api/datasources")
public class DataSourceController {

    private final DataSourceService dataSourceService;

    public DataSourceController(DataSourceService dataSourceService) {
        this.dataSourceService = dataSourceService;
    }

    @Operation(summary = "数据源列表")
    @GetMapping
    public ApiResponse<List<DataSource>> list() {
        return ApiResponse.ok(dataSourceService.listAll());
    }

    @Operation(summary = "数据源详情")
    @GetMapping("/{id}")
    public ApiResponse<DataSource> detail(@PathVariable Long id) {
        return ApiResponse.ok(dataSourceService.getById(id));
    }

    @Operation(summary = "新增数据源")
    @PostMapping
    public ApiResponse<DataSource> create(@Valid @RequestBody DataSource ds) {
        return ApiResponse.ok(dataSourceService.create(ds));
    }

    @Operation(summary = "更新数据源")
    @PutMapping("/{id}")
    public ApiResponse<DataSource> update(@PathVariable Long id, @Valid @RequestBody DataSource ds) {
        return ApiResponse.ok(dataSourceService.update(id, ds));
    }

    @Operation(summary = "删除数据源")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        dataSourceService.delete(id);
        return ApiResponse.ok();
    }

    @Operation(summary = "测试连接")
    @PostMapping("/{id}/test")
    public ApiResponse<Map<String, Object>> test(@PathVariable Long id) {
        return ApiResponse.ok(dataSourceService.testConnection(id));
    }

    @Operation(summary = "查看数据源表清单")
    @GetMapping("/{id}/tables")
    public ApiResponse<List<Map<String, Object>>> tables(@PathVariable Long id) {
        return ApiResponse.ok(dataSourceService.listTables(id));
    }
}
