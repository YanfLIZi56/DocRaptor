package com.yanglizi.docraptor.domain.dto;

import lombok.Data;

import java.util.UUID;

/** 节点 → 父节点 的轻量映射行，用于折叠树（scope=ALL_LEVELS）。 */
@Data
public class NodeParentPair {
    private UUID id;
    private UUID parentId;
}
