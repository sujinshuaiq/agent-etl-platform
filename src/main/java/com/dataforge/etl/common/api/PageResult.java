package com.dataforge.etl.common.api;

import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/**
 * 统一分页结果:{"content":[...],"totalElements":n,"totalPages":n,"page":p,"size":s}。
 * page 从 0 开始,与前端契约一致。
 */
public class PageResult<T> {

    private List<T> content;
    private long totalElements;
    private int totalPages;
    private int page;
    private int size;

    public PageResult() {
    }

    public PageResult(List<T> content, long totalElements, int totalPages, int page, int size) {
        this.content = content;
        this.totalElements = totalElements;
        this.totalPages = totalPages;
        this.page = page;
        this.size = size;
    }

    /** 由 Spring Data Page 直接转换 */
    public static <T> PageResult<T> from(Page<T> page) {
        return new PageResult<>(page.getContent(), page.getTotalElements(),
                page.getTotalPages(), page.getNumber(), page.getSize());
    }

    /** 由 Spring Data Page 转换,并对元素做 DTO 映射 */
    public static <T, R> PageResult<R> from(Page<T> page, Function<T, R> mapper) {
        return new PageResult<>(page.getContent().stream().map(mapper).toList(),
                page.getTotalElements(), page.getTotalPages(), page.getNumber(), page.getSize());
    }

    public List<T> getContent() {
        return content;
    }

    public void setContent(List<T> content) {
        this.content = content;
    }

    public long getTotalElements() {
        return totalElements;
    }

    public void setTotalElements(long totalElements) {
        this.totalElements = totalElements;
    }

    public int getTotalPages() {
        return totalPages;
    }

    public void setTotalPages(int totalPages) {
        this.totalPages = totalPages;
    }

    public int getPage() {
        return page;
    }

    public void setPage(int page) {
        this.page = page;
    }

    public int getSize() {
        return size;
    }

    public void setSize(int size) {
        this.size = size;
    }
}
