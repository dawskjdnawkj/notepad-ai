# pgvector 迁移对比报告

> 执行日期：2026-09-19
> 对应交接文档第三阶段
> 数据集：210 个向量片段 / 41 篇笔记 / 1 个活跃用户 / 1024 维

## 1. 结论摘要

| 配置 | 通过率 | Top-K 命中率 | 正确拒答率 | 错误引用 | 平均检索耗时（3 轮中位） |
| --- | --- | --- | --- | --- | --- |
| `simple`（进程内 SimpleVectorStore） | 100% | 100% | 100% | 0 | **445 ms** |
| `pgvector` + `PgIndexType.NONE` | 100% | 100% | 100% | 0 | **465 ms** |
| `pgvector` + `PgIndexType.HNSW` | 100% | 100% | 100% | 0 | **641 ms** |

**三条结论：**

1. **检索质量完全等价。** 三种配置都是 14/14 满分，且**逐用例的命中笔记集合完全一致**（3 轮累计 0 处不一致），`topScore` 最大偏差 `9.311e-07`。
2. **pgvector 在当前规模下没有性能收益。** `NONE` 档比 `simple` 慢约 4.5%，`HNSW` 档慢约 44%。
3. **HNSW 在 210 行这个量级是净负担**：索引本身 1704 kB（是数据表的 9 倍），总占用从 1408 kB 涨到 3096 kB，而准确率没有任何提升。

**因此建议：保持 `simple` 为默认值。** pgvector 已完整接入并验证可用，作为配置可选项保留（切一个环境变量即可切换），但**没有理由在 0.8 MB 的数据规模上承担一个额外数据库进程**。

---

## 2. 为什么这次迁移在性能上不会有收益

这不是猜测，是结构性的：

- 210 个向量 × 1024 维 = **0.8 MB** 纯向量数据
- `SimpleVectorStore` 的检索是内存里的暴力余弦，215K 次乘加 —— **结构上不可能超过 1 ms**
- 实测端到端检索平均 445 ms，**绝大部分是调用百炼 Embedding 的网络往返**

pgvector 的 ANN 索引优化的是那不到 1 ms 的部分。要让它体现出价值，数据量需要高好几个数量级。

本次迁移的价值在别处：**验证了架构可以平滑切到专业向量库**（接缝只用了 5 处单行分支，第一阶段的备份恢复成果零改动复用），以及**拿到了一份可复现的等价性证据**。

---

## 3. 实验方法

### 3.1 向量对齐（关键）

对比必须把差异隔离在**检索层**，而不是 Embedding 层。所以两边的向量必须逐位相同：

- **没有**用 `indexAllNotes()` 重新 Embedding —— 那样无法证明两次 Embedding 返回的 float 逐位相同，一旦有差异，检索结果的变化就**无法归因**是存储层还是 Embedding 层
- 改为：simple 模式下做快照 → 切 pgvector → **从同一份快照恢复**

这也顺带证明了一件事：**数据迁移不需要任何专门的迁移工具**，它就是一次 restore。

### 3.2 索引档位

- **`NONE`**：与 SimpleVectorStore 一样是精确暴力检索，算法等价，差异只可能来自存储与 SQL 层。**这是判断「迁移是否等价」的那一组。**
- **`HNSW`**：展示 ANN 索引在这个规模上的实际代价。
- **跳过 `IVFFlat`**：Spring AI 生成的 DDL 是 `CREATE INDEX ... USING ivfflat (embedding vector_cosine_ops)`，**不带 `WITH (lists = ...)`**，走 pgvector 默认 `lists=100`。210 行分到 100 个 list 平均每 list 2 条，再配 `ivfflat.probes=1`，召回会很差。那不是「存储的差异」而是「索引参数没调」，放进对比表只会制造噪音。

### 3.3 采样

每档跑 3 轮取中位。查询要跨网调用百炼 Embedding，单次抖动大，单轮不可比。

### 3.4 运行环境

- MySQL 8（3306）业务库；PostgreSQL 16 + pgvector 0.8.6（Docker，5433）向量库
- `notepad.ai.vector-store.pgvector.index-type` 切换档位需重启
- 原始数据留存在 `backend/data/experiments/{simple,pgvector-NONE,pgvector-HNSW}-{1,2,3}.json`，每份都是完整的回归报告（含逐用例明细），可回溯到 `ai_rag_eval_run` 表

---

## 4. 迁移正确性验证

### 4.1 迁移前后规模一致

```sql
SELECT count(*), count(DISTINCT metadata->>'noteId'),
       min(vector_dims(embedding)), max(vector_dims(embedding))
FROM vector_store;
-- 210 | 41 | 1024 | 1024
```

### 4.2 语义 round-trip 比对

从 pgvector 再导出一份快照，与 simple 的快照做解析后比对（**不能直接 diff**：`jsonb` 会重排 key、去空格，语义相同但文本不同）：

```
src entries = 210
dst entries = 210
id sets equal = True
text mismatches = 0
metadata mismatches = 0
embedding mismatches = 0
```

**向量逐位无损。** pgvector 的 `vector(1024)` 列是 float4 存储，PostgreSQL 的 `float4out` 输出能唯一还原该 float4 的最短十进制表示，Java `Float.parseFloat` 再精确还原，往返无损失。

### 4.3 逐用例等价性（比比率更细的证据）

只看通过率是不够的 —— 指标相同不代表引用了同一批笔记。所以逐条比对了 `matchedNoteIds` 与 `topScore`：

| 判据 | 阈值 | 实测 |
| --- | --- | --- |
| `caseId` 集合 | 相等 | ✓ |
| 每条用例的 `matchedNoteIds` 集合 | 完全相等 | **0 处不一致**（14 用例 × 3 轮） |
| 每条用例的 `topScore` 差 | ≤ 1e-6 | 最大 `9.311e-07`，中位 `3.450e-07` |

`topScore` 的微小偏差来自计算精度：SimpleVectorStore 在 Java 里用 **double** 算余弦，pgvector 在数据库里用 **float4** 算。1e-6 量级符合预期，不影响任何一条用例的判定。

**`minScore` 与检索阈值未做任何调整** —— 两边用的是同一套值。

---

## 5. 存储占用对比

| 载体 | 大小 |
| --- | --- |
| `simple`：`data/simple-vector-store.json` | **3,018 kB**（3,090,054 字节） |
| `pgvector` + `NONE`：表 192 kB + 索引 16 kB，`pg_total_relation_size` | **1,408 kB** |
| `pgvector` + `HNSW`：表 192 kB + 索引 1,704 kB | **3,096 kB** |

两点值得记录：

- pgvector 的实际占用只比 JSON **小 2.1 倍**，不是预想的一个数量级。原因是 `json` 类型的元数据与 TOAST 开销；纯向量部分 210 × 1024 × 4 = 860 kB 才是理论下界。
- **HNSW 索引 1,704 kB，是数据表的 9 倍。** 在 210 行这个量级，索引本身比它要加速的数据大一个数量级。

---

## 6. 已知的语义边界

- **阈值含端不同**：SimpleVectorStore 是 `score >= 0.30`（含），pgvector 是 `distance < 1 - 0.30` 即 `cosine > 0.30`（不含）。浮点上等值几乎不可能，本次 14 个用例未出现临界翻面。若将来出现，正确做法是子类化 `PgVectorStore` 覆盖 `doSimilaritySearch`，**而不是调阈值**。
- **元数据必须写成 JSON 字符串**：pgvector 的 jsonpath 过滤是类型敏感的字符串比较（`$.userId == "8"`）。若某条元数据的 `userId` 被写成数字，检索会**永远不命中且不报错**（fail-closed，不会泄露数据，但结果恒为空）。已在快照校验的恢复入口加了强制检查。

---

## 7. 最终建议

**保持 `simple` 为默认值，pgvector 作为配置可选项。**

理由：

1. 检索质量完全等价，性能无收益（`NONE` 慢 4.5%，`HNSW` 慢 44%）
2. 切默认意味着**所有部署环境都要多跑一个 PostgreSQL 进程**，而收益在当前规模下为零
3. 交接文档要求「保留 SimpleVectorStore 作为可回退方案」—— 默认值站在现状一侧，回退成本才是最低的

**什么情况下应该切换：**

- 向量规模进入 10⁵–10⁶ 量级，内存暴力检索开始成为瓶颈
- 需要多实例共享向量库（`SimpleVectorStore` 的多实例并发写会互相覆盖，这是它的固有限制）
- 需要按元数据的 SQL 级过滤与联合查询

切换方式：`NOTEPAD_VECTOR_STORE_TYPE=pgvector` + PostgreSQL 连接配置，重启即可。数据通过「快照备份 → 恢复」迁移，无需额外工具。

---

## 8. 复现步骤

```bash
# 1) simple 模式下做快照
NOTEPAD_USER=... NOTEPAD_PASSWORD=... bash backend/scripts/verify-index-backup.sh   # 顺带确认基线
curl -X POST .../api/ai/notes/index/backups

# 2) 切 pgvector 并迁移
NOTEPAD_VECTOR_STORE_TYPE=pgvector NOTEPAD_PGVECTOR_PASSWORD=... mvn spring-boot:run
curl -X POST .../api/ai/notes/index/backups/<快照文件名>/restore

# 3) 每档跑 3 轮回归，落盘到 backend/data/experiments/
# 4) 比对逐用例 matchedNoteIds 与 topScore
```
