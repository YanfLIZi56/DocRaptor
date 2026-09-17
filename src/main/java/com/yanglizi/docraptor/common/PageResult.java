package com.yanglizi.docraptor.common;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 分页返回结构 {list, total, page, pageSize}，见 docs/03-api-contract.md 1.2。
 * 空集合输出 []，不输出 null。
 */
@Data
public class PageResult<T> {

    private List<T> list = new ArrayList<>();
    private long total;
    private int page;
    private int pageSize;

    public static <T> PageResult<T> of(List<T> list, long total, int page, int pageSize) {
        PageResult<T> p = new PageResult<>();
        p.list = list == null ? new ArrayList<>() : list;
        p.total = total;
        p.page = page;
        p.pageSize = pageSize;
        return p;
    }
}
