#!/usr/bin/env node
/**
 * DocRaptor 端到端验收脚本（Lead 集成件）
 *
 * 覆盖验收标准：
 *   A. 导入一篇 5000 字以上文档，系统自动完成分块、聚类、摘要树构建
 *   B. 召回测试输入查询，返回带层级信息的召回结果
 *   C. 切换检索模式（VECTOR / BM25 / HYBRID），召回结果有明显差异
 *   D. 混合检索 Recall@5 > 60%
 *
 * 用法： node scripts/acceptance.mjs
 * 输出： 控制台摘要 + docs/acceptance-run.txt（UTF-8 完整报告）
 */

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(__dirname, '..');
const BASE = process.env.DOCRAPTOR_API || 'http://localhost:8080/api';
const SAMPLE = path.join(ROOT, 'docs', 'samples', 'raptor-guide.md');
const REPORT = path.join(ROOT, 'docs', 'acceptance-run.txt');

const KB_NAME = 'RAPTOR 验收知识库';
const lines = [];
const log = (s = '') => { lines.push(s); console.log(s); };
const j = (o) => JSON.stringify(o, null, 2);

/** 测试用例：expectedChunkIds 由「源文档小节标题/锚点短语」推导，与检索系统无关（非自证） */
const CASES = [
  { name: '长文档检索困境', query: '为什么固定长度切分会让跨段落的宏观问题答不好？',
    anchors: ['长文档检索面临的真实困境', '只包含两百字的文本块'] },
  { name: 'RAPTOR核心思想', query: 'RAPTOR 是怎么把文档组织成语义树的？',
    anchors: ['RAPTOR 的核心思想', 'Recursive Abstractive Processing'] },
  { name: '分块策略取舍', query: '中文文档分块时块长度和重叠度取多少比较合适？',
    anchors: ['分块策略的设计取舍', '块长度需要结合嵌入模型'] },
  { name: 'UMAP参数', query: 'UMAP 的 n_neighbors 和 min_dist 参数分别控制什么？',
    anchors: ['UMAP 降维的原理与作用', 'n_neighbors 控制局部与全局'] },
  { name: 'GMM簇数', query: 'GMM 聚类时最大簇数应该怎么限制？',
    anchors: ['GMM 聚类与簇数选择', '簇数上限应当与当前层的节点数挂钩'] },
  { name: '递归摘要与树', query: '摘要提示词为什么要严格约束？递归什么时候停止？',
    anchors: ['递归摘要与树结构', '摘要提示词必须严格约束'] },
  { name: '混合检索RRF', query: 'RRF 融合的平滑常数取多少，它的作用是什么？',
    anchors: ['三种检索模式的差异', '平滑常数，通常取六十'] },
  { name: '评估指标', query: 'Recall@K、Hit Rate@K 和 MRR 分别衡量什么？',
    anchors: ['评估指标的定义与意义', '平均倒数排名关注的是排序质量'] },
  { name: '异步与可观测性', query: '文档导入和建树为什么要做成异步任务？',
    anchors: ['工程落地中的异步与可观测性', '请求进来立刻返回任务标识'] },
];

async function call(method, url, body, isForm = false) {
  const opt = { method, headers: {} };
  if (body !== undefined) {
    if (isForm) { opt.body = body; }
    else { opt.headers['Content-Type'] = 'application/json'; opt.body = JSON.stringify(body); }
  }
  const res = await fetch(BASE + url, opt);
  const text = await res.text();
  let json;
  try { json = JSON.parse(text); } catch { throw new Error(`${method} ${url} -> HTTP ${res.status} 非 JSON: ${text.slice(0, 300)}`); }
  if (json.code !== 0) throw new Error(`${method} ${url} -> code=${json.code} message=${json.message}`);
  return json.data;
}

async function waitForApi(maxMs = 60_000) {
  const t0 = Date.now();
  while (Date.now() - t0 < maxMs) {
    try { await call('GET', '/knowledge-bases?page=1&pageSize=1'); return Date.now() - t0; }
    catch { await new Promise((r) => setTimeout(r, 1500)); }
  }
  throw new Error(`后端在 ${maxMs}ms 内未就绪：${BASE}`);
}

async function pollTask(taskId, timeoutMs = 600_000) {
  const t0 = Date.now();
  let last = '';
  while (Date.now() - t0 < timeoutMs) {
    const t = await call('GET', `/async-tasks/${taskId}`);
    const sig = `${t.status}/${t.currentStage}/${t.progress}`;
    if (sig !== last) { last = sig; log(`    [task] ${sig} ${t.progressMessage || ''}`); }
    if (['SUCCESS', 'PARTIAL_SUCCESS', 'FAILED', 'CANCELED'].includes(t.status)) return t;
    await new Promise((r) => setTimeout(r, 1000));
  }
  throw new Error('任务超时: ' + taskId);
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function main() {
  log('='.repeat(78));
  log('DocRaptor 端到端验收');
  log('时间: ' + new Date().toISOString());
  log('API : ' + BASE);
  log('='.repeat(78));

  // ---------- 0. 就绪 ----------
  const bootMs = await waitForApi();
  log(`\n[0] 后端就绪，耗时 ${bootMs}ms`);

  // ---------- 1. 知识库 ----------
  let kb;
  const list = await call('GET', `/knowledge-bases?page=1&pageSize=200`);
  kb = list.list.find((k) => k.name === KB_NAME);
  if (kb) {
    log(`\n[1] 复用已有知识库: ${kb.id} (${kb.name})`);
  } else {
    kb = await call('POST', '/knowledge-bases', {
      name: KB_NAME,
      description: '端到端验收：RAPTOR 递归摘要检索技术指南',
      chunkSize: 600, chunkOverlap: 80, chunkStrategy: 'RECURSIVE',
    });
    log(`\n[1] 新建知识库: ${kb.id}`);
  }
  log(`    chunkSize=${kb.chunkSize} overlap=${kb.chunkOverlap} strategy=${kb.chunkStrategy} dim=${kb.embeddingDimension}`);

  // ---------- 2. 导入 5000+ 字文档 ----------
  const docText = fs.readFileSync(SAMPLE, 'utf8');
  const cjk = (docText.match(/[\u4e00-\u9fff]/g) || []).length;
  log(`\n[2] 导入文档 ${path.basename(SAMPLE)}：总字符 ${docText.length}，中文字符 ${cjk}`);

  const existingDocs = await call('GET', `/documents?knowledgeBaseId=${kb.id}&page=1&pageSize=200`);
  let doc = existingDocs.list.find((d) => d.fileName === path.basename(SAMPLE));
  let importTask = null;
  if (doc) {
    log(`    已存在同名文档，跳过上传: ${doc.id}`);
  } else {
    const form = new FormData();
    form.append('file', new Blob([docText], { type: 'text/markdown' }), path.basename(SAMPLE));
    form.append('knowledgeBaseId', kb.id);
    form.append('buildTree', 'true');
    form.append('maxLevel', '3');
    const up = await call('POST', '/documents/upload', form, true);
    log(`    上传成功 documentId=${up.documentId} taskId=${up.taskId} fileType=${up.fileType} size=${up.fileSize}`);
    importTask = await pollTask(up.taskId);
    log(`    导入任务终态: ${importTask.status} progress=${importTask.progress}`);
    if (importTask.status === 'FAILED') {
      log('    !! 导入失败，终止: ' + j(importTask));
      throw new Error('导入失败');
    }
    doc = await call('GET', `/documents/${up.documentId}`);
  }
  log(`    文档状态: embedStatus=${doc.embedStatus} treeStatus=${doc.treeStatus} chunkCount=${doc.chunkCount}`);

  // ---------- 3. 分块校验 ----------
  const chunks = [];
  for (let page = 1; ; page++) {
    const c = await call('GET', `/chunks?documentId=${doc.id}&page=${page}&pageSize=200&withContent=true`);
    chunks.push(...c.list);
    if (chunks.length >= c.total || c.list.length === 0) break;
  }
  log(`\n[3] 分块结果：共 ${chunks.length} 块`);
  const missing = chunks.filter((c) => !c.documentId || c.chunkIndex === null || c.chunkIndex === undefined || !c.charCount);
  log(`    来源文档ID/块序号/字符数 三要素缺失的块数: ${missing.length}  ${missing.length === 0 ? '✔' : '✘'}`);
  chunks.forEach((c) => log(`      #${String(c.chunkIndex).padStart(3)} doc=${c.documentId.slice(0, 8)}… chars=${String(c.charCount).padStart(4)} embed=${c.hasEmbedding} parent=${c.parentId ? c.parentId.slice(0, 8) + '…' : 'null'}`));

  // ---------- 4. 摘要树 ----------
  let treeOk = true, stats = null, treeLayered = false;
  try {
    stats = await call('GET', `/raptor/trees/${doc.id}/stats`);
    log(`\n[4] RAPTOR 树统计: ${j(stats)}`);
  } catch (e) { treeOk = false; log(`\n[4] 树统计获取失败: ${e.message}`); }

  let tree = null;
  try { tree = await call('GET', `/raptor/trees/${doc.id}`); }
  catch (e) { log(`    树结构获取失败: ${e.message}`); }
  if (tree) {
    // 契约 5.2 的响应是「嵌套树 + 扁平节点」；实测 data.root 是嵌套结构（children），
    // data.nodes 默认是 null（只有请求 format=flat 时才给扁平列表），所以要自己递归展开。
    const flat = [];
    const walk = (n) => {
      if (!n || typeof n !== 'object') return;
      flat.push(n);
      (n.children || []).forEach(walk);
    };
    if (Array.isArray(tree.nodes) && tree.nodes.length > 0) flat.push(...tree.nodes);
    else if (Array.isArray(tree.flatNodes) && tree.flatNodes.length > 0) flat.push(...tree.flatNodes);
    else walk(tree.root);

    const byLevel = {};
    flat.forEach((n) => { byLevel[n.level] = (byLevel[n.level] || 0) + 1; });
    log(`    扁平节点数=${flat.length} 各层节点数=${j(byLevel)}`);
    log(`    接口自报: nodeCount=${tree.nodeCount} leafNodeCount=${tree.leafNodeCount} summaryNodeCount=${tree.summaryNodeCount} actualDepth=${tree.actualDepth} rootNodeId=${tree.rootNodeId}`);
    const summaries = flat.filter((n) => n.nodeType === 'SUMMARY');
    log(`    展开得到摘要节点数=${summaries.length}`);
    summaries.slice(0, 4).forEach((n) => log(`      L${n.level} [${n.startChunkIndex}~${n.endChunkIndex}] ${String(n.summary || n.content || '').slice(0, 80)}…`));
    if (summaries.length === 0) treeOk = false;

    // ⚠️ 关键断言：检索 SQL 用 `JOIN document d ON d.id = s.document_id AND d.enabled = TRUE`，
    // 该 INNER JOIN 会静默丢弃 document_id 为 NULL 的节点（而 DDL 允许 SUMMARY.document_id 为 NULL）。
    // 若摘要节点没有 document_id，上层摘要将永远召回不到 → RAPTOR 的分层检索形同虚设。
    const orphanSummaries = summaries.filter((n) => !n.documentId);
    log(`    摘要节点中 document_id 为空的个数 = ${orphanSummaries.length} ${orphanSummaries.length === 0 ? '✔' : '✘ 会被检索 SQL 静默丢弃'}`);
    if (orphanSummaries.length > 0) treeOk = false;

    // ⚠️ 层次性断言：若 UMAP 降维被跳过（nNeighbors ≥ n）或维度超出 2d+3 < n，
    // GMM 会在 1536 维上全部拟合失败 → 单簇 → 全部叶子被合并成一个父节点，
    // 树退化为「N 叶子 + 1 根」，没有中间层。需求要求的是「递归摘要树」，这里必须检出。
    const summaryLevels = [...new Set(summaries.map((n) => n.level))].sort((a, b) => a - b);
    const leafCount = flat.filter((n) => n.nodeType === 'LEAF').length;
    // 以「接口自报的 summaryNodeCount」为准做兜底（避免因响应结构变化而误判）
    const summaryCount = Math.max(summaries.length, tree.summaryNodeCount || 0);
    treeLayered = summaryCount >= 2 && leafCount >= 2;
    log(`    摘要层级 = [${summaryLevels.join(', ')}]，叶子 ${leafCount} 个，摘要 ${summaryCount} 个，actualDepth=${tree.actualDepth}`);
    if (!treeLayered) {
      log(`    ✘ 退化：只有 ${summaryCount} 个摘要节点（${leafCount} 叶子全被合并成一个父节点），没有真正的语义层次。`);
      log(`      根因通常是 UMAP 未生效（nNeighbors ≥ 节点数，或目标维度不满足 2d+3 < n）→ GMM 在 1536 维上拟合失败 → 单簇。`);
      log(`      建议：n-neighbors ≤ 5 且 n-components = 2（详见 docs/00-environment-facts.md 第 10 节实测表）。`);
    }
    if (!treeLayered) treeOk = false;
  }

  // ---------- 5. 三种检索模式对比 ----------
  // 用两条查询做对比：q1 含专有名词（利于 BM25），q2 纯口语化改写（利于向量），
  // 这样「三模式结果有差异」才是有说服力的证据，而不是靠单一查询的偶然排序。
  const demoQueries = [
    'RAPTOR 如何用 UMAP 降维和 GMM 聚类构建递归摘要树？',
    '那套把文档层层浓缩成树的做法，到底是怎么一步步做出来的？',
  ];
  const results = {};
  const modeDiff = [];
  for (const demoQuery of demoQueries) {
    log(`\n[5] 三模式检索对比，查询: 「${demoQuery}」`);
    const perMode = {};
    for (const m of ['vector', 'bm25', 'hybrid']) {
      const r = await call('POST', `/retrieval/${m}`, {
        knowledgeBaseId: kb.id, query: demoQuery, topK: 5,
        similarityThreshold: 0, hybridRatio: 0.5, bm25Weight: 1.0, rrfK: 60,
        scope: 'ALL_LEVELS', withContent: true, withScoreBreakdown: true,
      });
      perMode[m] = r;
      log(`\n    --- ${m.toUpperCase()} (costMs=${r.costMs}, totalHits=${r.totalHits}, collapsed=${r.collapsedCount}) ---`);
      r.hits.forEach((h) => {
        const sb = h.scoreBreakdown || {};
        log(`      #${h.finalRank} score=${Number(h.finalScore).toFixed(6)} L${h.level} ${h.nodeType} vRank=${sb.vectorRank ?? '-'} vScore=${sb.vectorRawScore != null ? Number(sb.vectorRawScore).toFixed(4) : '-'} bRank=${sb.bm25Rank ?? '-'} bScore=${sb.bm25RawScore != null ? Number(sb.bm25RawScore).toFixed(4) : '-'} :: ${String(h.content || '').replace(/\s+/g, ' ').slice(0, 60)}`);
      });
    }
    results[demoQuery] = perMode;
    const idsOf = (m) => perMode[m].hits.map((h) => h.nodeId);
    const sameOrder = j(idsOf('vector')) === j(idsOf('bm25'));
    const sameSet = j([...idsOf('vector')].sort()) === j([...idsOf('bm25')].sort());
    const ovVB = idsOf('vector').filter((i) => idsOf('bm25').includes(i)).length;
    const ovVH = idsOf('vector').filter((i) => idsOf('hybrid').includes(i)).length;
    const ovBH = idsOf('bm25').filter((i) => idsOf('hybrid').includes(i)).length;
    log(`\n    重叠: V∩B=${ovVB}/5  V∩H=${ovVH}/5  B∩H=${ovBH}/5`);
    log(`    向量与BM25 顺序完全相同: ${sameOrder} / 集合完全相同: ${sameSet}`);
    const hasLevelInfo = perMode.hybrid.hits.every((h) => h.level !== undefined && h.nodeType);
    log(`    召回结果携带层级信息(level/nodeType): ${hasLevelInfo ? '✔' : '✘'}`);
    modeDiff.push({ demoQuery, sameOrder, sameSet, ovVB, ovVH, ovBH, hasLevelInfo });
  }
  const hasLevelInfo = modeDiff.every((d) => d.hasLevelInfo);
  const modesDiffer = modeDiff.some((d) => !d.sameOrder || d.ovVB < 5);
  const allIdentical = modeDiff.every((d) => d.sameOrder && d.sameSet);

  // 取出用于结论的默认查询结果
  const demoQuery = demoQueries[0];
  results.hybrid = results[demoQuery].hybrid;

  // ---------- 6. 召回率评估 ----------
  log(`\n[6] 召回率评估（期望块由源文档小节锚点推导，非检索自证）`);
  // 锚点匹配做了三层退化，避免分块边界把标题切断导致用例被跳过：
  //   ① 原样锚点 → ② 去掉「一、」这类中文序号前缀 → ③ 锚点中最长的中文/英文连续片段
  const normalize = (s) => s.replace(/\s+/g, '');
  const keysOf = (anchor) => {
    const out = new Set([anchor]);
    const noPrefix = anchor.replace(/^[一二三四五六七八九十]+[、.．]\s*/, '');
    if (noPrefix) out.add(noPrefix);
    const runs = anchor.match(/[\u4e00-\u9fff]{4,}|[A-Za-z@][A-Za-z0-9@._-]{6,}/g) || [];
    runs.forEach((r) => out.add(r));
    if (noPrefix.length > 8) out.add(noPrefix.slice(0, 8));
    return [...out];
  };
  const byAnchor = (anchor) => {
    for (const key of keysOf(anchor)) {
      const hit = chunks.filter((c) => normalize(c.content || '').includes(normalize(key)));
      if (hit.length > 0) return hit.map((c) => c.nodeId);
    }
    return [];
  };
  const caseIds = [];
  const caseReport = [];
  for (const spec of CASES) {
    const expected = [...new Set(spec.anchors.flatMap(byAnchor))];
    if (expected.length === 0) { log(`    ⚠ 跳过「${spec.name}」：锚点未匹配到任何块`); continue; }
    caseReport.push({ ...spec, expected });
    const exist = await call('GET', `/eval/cases?knowledgeBaseId=${kb.id}&page=1&pageSize=200`);
    const dup = exist.list.find((c) => c.name === spec.name);
    if (dup) { caseIds.push(dup.id); log(`    用例「${spec.name}」已存在，复用 ${dup.id.slice(0, 8)}… 期望块 ${expected.length} 个`); continue; }
    const created = await call('POST', '/eval/cases', {
      knowledgeBaseId: kb.id, name: spec.name, queryText: spec.query, expectedChunkIds: expected,
      remark: 'anchors: ' + spec.anchors.join(' | '),
    });
    caseIds.push(created.id);
    log(`    用例「${spec.name}」创建 ${created.id.slice(0, 8)}… 期望块 ${expected.length} 个`);
  }
  log(`    有效用例数: ${caseIds.length} / ${CASES.length}`);

  const evalOut = {};
  for (const mode of ['VECTOR', 'BM25', 'HYBRID']) {
    for (const scope of ['LEAF_ONLY', 'ALL_LEVELS']) {
      const r = await call('POST', '/eval/run', {
        knowledgeBaseId: kb.id, caseIds, topK: 10, kList: [1, 3, 5, 10], mode,
        similarityThreshold: 0, hybridRatio: 0.5, bm25Weight: 1.0, rrfK: 60,
        scope, levels: null, async: false,
      });
      evalOut[`${mode}/${scope}`] = r;
      log(`\n    --- ${mode} / ${scope} : 评估 ${r.evaluatedCases} 用例, 平均耗时 ${r.avgLatencyMs}ms ---`);
      log('        K    Recall   HitRate   MRR');
      r.metrics.forEach((m) => log(`        ${String(m.k).padEnd(4)} ${m.recall.toFixed(4).padStart(7)} ${m.hitRate.toFixed(4).padStart(9)} ${m.mrr.toFixed(4).padStart(7)}`));
    }
  }

  // ---------- 7. 结论 ----------
  const hybLeaf = evalOut['HYBRID/LEAF_ONLY'];
  const recall5 = hybLeaf.metrics.find((m) => m.k === 5);
  const passD = recall5 && recall5.recall > 0.60;
  log('\n' + '='.repeat(78));
  log('验收结论');
  log('='.repeat(78));
  const verdict = [
    ['A 导入 5000+ 字文档并自动分块/向量化/构建有层次的摘要树', cjk >= 5000 && chunks.length > 0 && treeOk && treeLayered],
    ['B 召回结果携带层级信息', hasLevelInfo && results.hybrid.hits.length > 0],
    ['C 三模式召回结果有差异', modesDiffer && !allIdentical],
    ['D 混合检索 Recall@5 > 60% (LEAF_ONLY)', passD],
  ];
  verdict.forEach(([k, v]) => log(`  ${v ? '✔ PASS' : '✘ FAIL'}  ${k}`));
  log(`\n  混合检索 LEAF_ONLY Recall@5 = ${recall5 ? (recall5.recall * 100).toFixed(1) + '%' : 'N/A'} (阈值 60%)`);
  for (const mode of ['VECTOR', 'BM25', 'HYBRID']) {
    const m = evalOut[`${mode}/LEAF_ONLY`].metrics.find((x) => x.k === 5);
    const a = evalOut[`${mode}/ALL_LEVELS`].metrics.find((x) => x.k === 5);
    log(`  ${mode.padEnd(6)} Recall@5  LEAF_ONLY=${(m.recall * 100).toFixed(1)}%  ALL_LEVELS=${(a.recall * 100).toFixed(1)}%`);
  }
  log(`\n  全部通过: ${verdict.every(([, v]) => v) ? '是' : '否'}`);

  log('\n用例明细（期望块 → 是否命中）:');
  for (const mode of ['VECTOR', 'BM25', 'HYBRID']) {
    const r = evalOut[`${mode}/LEAF_ONLY`];
    log(`  [${mode}]`);
    r.perQuery.forEach((p) => log(`    ${p.hitNodeIds.length > 0 ? '命中' : '未中'} firstRank=${p.firstHitRank ?? '-'} 「${p.name}」 ${p.query}`));
  }

  fs.writeFileSync(REPORT, lines.join('\n'), 'utf8');
  console.log(`\n完整报告已写入: ${REPORT}`);
  process.exit(verdict.every(([, v]) => v) ? 0 : 2);
}

main().catch(async (e) => {
  log('\n!! 验收脚本异常终止: ' + e.message);
  log(e.stack || '');
  try { fs.writeFileSync(REPORT, lines.join('\n'), 'utf8'); } catch {}
  process.exit(1);
});
