package com.yanglizi.docraptor.domain.dto;

import lombok.Data;

/** 某层级的节点数量（树统计用）。 */
@Data
public class LevelCount {
    private Short level;
    private Integer count;
}
