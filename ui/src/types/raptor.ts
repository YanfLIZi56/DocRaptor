import type { GmmCovarianceType, NodeType, StepStatus, TaskStatus } from './enums'

/** 树节点的 metadata（契约 5.2 示例；根节点带 umap / gmm 实际使用参数，用于追溯） */
export interface RaptorNodeMetadata {
  buildId?: string
  isRoot?: boolean
  umap?: {
    nNeighbors?: number
    minDist?: number
  }
  gmm?: {
    nComponents?: number
    covarianceType?: string
  }
  [key: string]: unknown
}

/** RAPTOR 树节点（契约 5.2，nested 与 flat 共用同一节点结构） */
export interface RaptorTreeNode {
  nodeId: string
  nodeType: NodeType
  /** 叶子=0，摘要节点 >=1 */
  level: number
  parentId: string | null
  /** 仅 LEAF 节点返回 */
  chunkIndex?: number | null
  /** 覆盖块范围起 */
  startChunkIndex: number
  /** 覆盖块范围止 */
  endChunkIndex: number
  charCount: number
  /** 摘要内容（LEAF 为 null） */
  summary: string | null
  /** 正文（withContent=false 时截断到 200 字符） */
  content: string | null
  clusterLabel: number | null
  clusterSize: number | null
  childCount: number
  /** 契约示例中仅根节点返回；其余节点可能缺省 */
  documentId?: string
  hasEmbedding: boolean
  metadata: RaptorNodeMetadata
  children: RaptorTreeNode[]
}

/** 树查询返回格式：nested=嵌套 children 树；flat=扁平数组 + parentId */
export type RaptorTreeFormat = 'nested' | 'flat'

/** 树结构响应（契约 5.2） */
export interface RaptorTreeData {
  documentId: string
  documentName: string
  knowledgeBaseId: string
  maxLevel: number
  actualDepth: number
  rootNodeId: string | null
  nodeCount: number
  summaryNodeCount: number
  leafNodeCount: number
  builtAt: number | null
  /** format=nested 时存在 */
  root?: RaptorTreeNode | null
  /** format=flat 时存在（替代 root） */
  nodes?: RaptorTreeNode[]
}

/** 树结构查询入参（契约 5.2） */
export interface RaptorTreeQuery {
  /** 默认 nested */
  format?: RaptorTreeFormat
  /** false 时 content 截断到 200 字符，默认 true */
  withContent?: boolean
  /** false 时只返回 SUMMARY 节点，默认 true */
  includeLeaves?: boolean
}

/** 触发建树入参（契约 5.1） */
export interface RaptorBuildRequest {
  documentId: string
  /** 树深上限，[1, 10]，默认 3 */
  maxLevel?: number
  /** true 时删除已有 SUMMARY 节点并重建（叶子块与向量不重建） */
  forceRebuild?: boolean
  /** UMAP n_neighbors，[2, 100]，默认 10 */
  umapNNeighbors?: number
  /** UMAP min_dist，[0.0, 1.0]，默认 0.1 */
  umapMinDist?: number
  /** GMM 聚类数量上限，[2, 64]，默认 8 */
  gmmMaxClusters?: number
  /** null 表示用配置默认 */
  gmmCovarianceType?: GmmCovarianceType | null
  /** 覆盖摘要 Prompt 模板（调试用）；null 用配置的正式模板 */
  summaryPrompt?: string | null
}

/** 触发建树响应（契约 5.1） */
export interface RaptorBuildResult {
  taskId: string
  documentId: string
  status: TaskStatus
}

/** 每层节点数（契约 5.3） */
export interface RaptorLevelCount {
  level: number
  count: number
}

/** 树统计（契约 5.3） */
export interface RaptorTreeStats {
  documentId: string
  treeStatus: StepStatus
  actualDepth: number
  maxLevel: number
  rootCount: number
  /** false 表示建树未收敛（异常态），前端应提示用更大 maxLevel + forceRebuild 重建 */
  hasUniqueRoot: boolean
  levelCounts: RaptorLevelCount[]
  avgClusterSize: number
  /** LLM 摘要重试失败后降级为原文拼接的节点数 */
  degradedSummaryCount: number
  forcedRoot: boolean
  builtAt: number | null
  buildDurationMs: number | null
}
