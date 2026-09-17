package com.yanglizi.docraptor.service;

import com.yanglizi.docraptor.common.BizException;
import com.yanglizi.docraptor.common.ErrorCode;

/** 分页参数归一化：page 从 1 开始，pageSize ∈ [1, 200]（契约 1）。 */
public final class Paging {

    public static final int MAX_PAGE_SIZE = 200;

    private Paging() {
    }

    /** @return [page, pageSize] */
    public static int[] normalize(Integer page, Integer pageSize) {
        int p = page == null ? 1 : page;
        int s = pageSize == null ? 20 : pageSize;
        if (p < 1) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "page 必须 ≥ 1");
        }
        if (s < 1 || s > MAX_PAGE_SIZE) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "pageSize 需在 [1, " + MAX_PAGE_SIZE + "] 之间");
        }
        return new int[]{p, s};
    }
}
