# 铁路信号沙盘 · 离线联锁验证台

一个**完全离线**运行的信号工程师沙盘：描述轨道区段 / 道岔 / 信号机 / 进路，
用显式状态机驱动建立锁闭、占用、逐段释放与设备故障；再用显式状态空间
探索（BFS）交错并发操作，给出违反**互斥 / 侧向防护 / 释放顺序**等
不变量的**最短反例**，并逐步展示触发条件、效果与未满足的不变量。

不依赖任何外部网络服务；唯一的持久化是本地 H2 文件数据库（`./data/`）。

## 1. 环境要求

- JDK 17 及以上（开发机实测 JDK 24 可编译运行；字节码目标为 17）
- 无需预装 Maven：仓库自带 `./mvnw`（首次运行自动下载 Maven 发行版到 `~/.m2`）
- 浏览器打开静态页面即可，无前端构建步骤

## 2. 安装、测试、启动

```bash
# 构建（跳过测试）
./mvnw -q -DskipTests package

# 测试 + 启动（按要求的精确命令）
./mvnw -q test && ./mvnw -q spring-boot:run -Dspring-boot.run.arguments=--server.port=5215
```

页面地址：<http://127.0.0.1:5215>

首次启动时若数据库为空，`DataSeeder` 会播种一个演示车场（一个道岔、
正线/侧向/调车三条进路）、一套完整规则 `v1-safe`、安全场景与双调度员
竞争场景，并自动批准第一版。之后再次启动不会重复播种。

## 3. 模块地图

```
src/main/java/com/railway/sandbox/
├── model/           # Topology / RuleSet / Scenario / RuntimeState（纯数据，可克隆）
├── domain/
│   ├── TopologyAnalysis.java  # 图算法 + 悬空/方向/不可达/道岔几何校验
│   └── Machine.java           # 联锁状态机：每个操作的守卫、拒绝码、6 条不变量
├── verify/
│   ├── Verifier.java          # BFS 交错探索、显式上限、canonical 状态归并
│   ├── VerifyReport.java      # 结论 + 逐步轨迹（trigger/effect/violations/state）
│   └── JsonIO.java            # 全局确定性 ObjectMapper（指纹字节一致）
├── repo/            # JPA 实体：原始证据只追加；乐观锁 @Version；派生物带来源指纹
├── service/
│   ├── SandboxService.java    # 版本/批准/验证/对比/三路合并/导出重放
│   └── StepService.java       # 页面时间步进（无状态，状态随请求携带）
├── web/             # REST 控制器与 404/400/409 异常映射
└── config/          # DataSeeder（首启播种）+ SampleData
src/main/resources/static/     # index.html + app.js + app.css（无框架、无 CDN）
```

## 4. 状态机与不变量

进路状态：`FULLY_LOCKED → OCCUPIED → RELEASING → RELEASED`。

操作（每个操作都有稳定守卫与稳定拒绝码，便于跨版本统计）：

| 操作 | 含义 |
| --- | --- |
| `REQUEST_ROUTE` | 检查区段空闲/故障、道岔位置、互斥、侧向防护后一次锁闭 |
| `CLEAR_SIGNAL` | 仅在进路完全锁闭且无轨道故障时开放入口信号 |
| `ENTER_SECTION` | 列车进入下一个锁闭区段（单列模型，进入新区段自动腾空前一个） |
| `RELEASE_SECTION_NEXT` | 按入口→出口顺序释放最前方区段 |
| `FINISH_ROUTE` | 全部区段释放且无占用后彻底解锁 |
| `MOVE_SWITCH` | 扳动道岔；占用/锁闭/故障时被守卫拒绝 |
| `INJECT_*_FAULT` / `CLEAR_*_FAULT` | 插入与恢复区段/道岔设备故障 |

每次**接受的**迁移后评估不变量（与规则开关无关——弱化规则只会让危险
变得可达，从而产生反例）：

- `I1 MUTEX` 区段至多被一条活动进路锁闭
- `I2 OCCUPANCY` 占用区段必须被且仅被一条进路锁闭
- `I3 FLANK` 侧向接车占用时，侧向邻线区段必须空闲且未被他路锁闭
- `I4 RELEASE_ORDER` 只能按前缀顺序释放
- `I5 SIGNAL` 开放信号必须对应完全锁闭、无故障的进路
- `I6 SWITCH_LOCK` 锁闭中的道岔位置不能偏离进路要求

## 5. 验证器语义（务必区分三种结论）

- BFS 节点保存每个 actor 的程序游标；可执行动作按
  `(actor id, 操作类型, 目标)` **确定性排序**，拒绝的操作计入统计且不改变状态。
- 状态用 `RuntimeState.canonical()` 归并：列车只按“占用模式 + 数量”
  区分（对称列车互换是同一状态），保留首达路径，所以**归并可解释、
  反例仍可逐步回放**。
- 结论三选一，互不等价：
  - `SAFE`：队列穷尽，所有可达状态都满足不变量（在该上限内的证明）；
  - `LIMIT_REACHED`：达到显式上限后停止，**不是安全证明**；
  - `COUNTEREXAMPLE`：找到最短交错反例，轨迹含每步 trigger / effect /
    接受与否 / 拒绝原因 / 违反的不变量 / 该步后完整状态；
  - `TOPOLOGY_INVALID`：拓扑有悬空、方向或不可达问题，拒绝验证。

## 6. 版本、证据与并发编辑规则

- **原始证据不可原地改写**：拓扑导入、场景保存（含“插入故障”按钮）都
  产生带新 id 与父版本指针的新行；旧行永不更新。
- **批准即冻结**：批准行同时固化拓扑 id+指纹、规则 id+指纹、场景 id+
  指纹；之后编辑产生新版本，不回写已批准的指向。
- **派生物可溯源**：每份 `ReportEntity` 记录规则版本号、规则/拓扑/场景
  三个 SHA-256 指纹和反例长度。
- **两个浏览器基于同一旧版本提交**：规则保存携带 `baseVersion`
  （JPA `@Version`），后到方收到 **409**，响应体同时给出他的内容与
  服务器当前内容；页面弹出冲突框，可用“三路合并提交”生成合并候选：
  只有自己改 → 取自己；只有当前改 → 取当前；双方把同一数值参数
  （如 `maxShuntSpeed`）改成不同值 → 记为冲突、优先当前值并把
  base/yours/current 全部列出，可调整后再次合并。

## 7. 确定性验证包（跨机器同一最小反例）

`POST /api/export` 下载单个 JSON：全部输入、上限、三个指纹、期望报告、
包指纹。另一台机器 `POST /api/import-replay` 时**不信任包内报告**，
而是用包内输入重新执行 BFS，并逐步比较结论、反例长度与每个 trigger。
由于探索顺序、HashMap 遍历（全部使用 TreeMap/TreeSet）和指纹算法都
确定，两台机器会选出**同一条字典序最小的最短反例**。

## 8. API 速查

```
GET/POST /api/topologies[/...]      # 拓扑版本、结构问题与摘要
GET/POST /api/rules                 # 候选列表 / 新建
POST     /api/rules/{id}            # 乐观锁保存（body 带 baseVersion）
POST     /api/rules/{id}/merge      # 三路合并
GET/POST /api/scenarios[/...]       # 场景版本
POST     /api/scenarios/{id}/faults # 插入/恢复故障 -> 新版本
GET/POST /api/approval              # 查看/执行冻结批准
POST     /api/verify[?bound=n]      # 任意三版本组合验证
POST     /api/verify/approved       # 验证当前批准版
GET      /api/reports[/{id}]        # 历史报告
POST     /api/compare[?bound=n]     # 同场景：候选 vs 批准
POST     /api/step/initial|one      # 时间步进
POST     /api/export|import-replay  # 验证包
```

## 9. 新维护者：跟做一次“故障恢复”演练

1. 启动并确认浏览器总览里有 ★ 批准版本。
2. 打开“场景/时间步进”，选择 `v1` 安全场景，点 **⏮ 复位到初始状态**。
3. 依次单步执行：`REQUEST_ROUTE R1`、`CLEAR_SIGNAL SigR1`、
   `ENTER_SECTION S0`，观察状态框里占用变为 `S0`、进路变为 `OCCUPIED`。
4. 执行 `INJECT_SECTION_FAULT / S2`：看到守卫类操作之后会因
   `TRACK_FAULT` 被拒绝（例如继续 ENTER 到 S2 时）。
5. 点 **把当前故障固化为新场景版本**（或直接对该场景 POST
   `/faults`）：得到 `v2`，父版本指向 `v1`；回到版本列表确认 `v1`
   的载荷里**没有** S2 故障（原始证据未被改写）。
6. 在“验证与反例”页对 `v2` 跑验证，复现拒绝统计；再执行
   `CLEAR_SECTION_FAULT / S2` 并固化 `v3`，验证恢复到安全。
7. 想验证弱化规则的后果：在“规则版本”里新建候选关闭
   `enforceRouteMutex`，对“竞争”场景验证——应立即得到 2 步
   `I1 MUTEX` 反例，逐步点开每一步查看 trigger 与最终坏状态。
8. 导出该验证包，删掉浏览器下载后重新导入，或把包拷到另一台机器
   `POST /api/import-replay`，确认 `replayMatch=true` 且反例 trigger
   序列逐字相同。

### 数据库损坏时的恢复

H2 文件位于 `./data/sandbox.mv.db`。若文件损坏（例如磁盘写满）：
停止进程，把 `./data/` 挪走备份后重启，应用会以空库重新播种；
之前导出的验证包可以随时重新导入重放以核对结论。

## 10. 测试

```bash
./mvnw -q test
```

- `TopologyAnalysisTest`：悬空连接、方向不一致、道岔几何不符、不可达。
- `VerifierTest`：安全证明、守卫拒绝、弱化规则产生 I1 反例、
  反例确定性与最短性、`SAFE` 与 `LIMIT_REACHED` 结论分离、
  拓扑无效拒绝验证、道岔故障阻止锁闭。
- `SandboxServiceIntegrationTest`（内存 H2）：批准冻结不被后续编辑回写、
  409 冲突内容、三路合并独立改动/真冲突、故障产生新版本且旧证据不变、
  导出包重放一致、报告带来源指纹、候选对比输出。

测试不访问网络；Maven 依赖来自本地 `~/.m2` 或 Maven Central。
