# 铁路信号离线沙盘

面向信号工程师的本地 Web 应用，用显式状态机描述轨道区段、道岔、信号、进路建立/占用/释放/故障，并用有界 BFS 探索并发操作交错。应用使用 Spring Boot、内嵌 H2 文件库和无外部 CDN 的原生前端，测试全部可离线运行。

## 构建与启动

要求：JDK 17+。首次构建若本机 Maven 缓存没有依赖，`mvnw` 会通过 Maven Wrapper 拉取；之后可完全离线工作。

```bash
./mvnw -q -DskipTests package
./mvnw -q test && ./mvnw -q spring-boot:run -Dspring-boot.run.arguments=--server.port=5215
```

打开：

```text
http://127.0.0.1:5215
```

默认 H2 数据文件位于 `data/rail-sandbox.mv.db`。首次启动会写入一个示例拓扑、三条规则版本、一个场景，并冻结初始批准版本。

## 领域模型与检查

- `Section`：轨道区段，声明一个或多个物理端口。
- `SwitchDevice`：道岔，有公共端口和两个分支端口，运行时位置为 `NORMAL` 或 `REVERSE`。
- `TrackLink`：连接两个 `设备#端口`，方向为 `FORWARD`、`REVERSE` 或 `BOTH`。
- `RouteDefinition`：入口信号、有序区段、路径道岔位置和侧向防护道岔位置。

拓扑导入时检查：

- 重复 ID、悬空信号/区段/道岔/连接引用；
- 道岔公共端口和两个分支端口是否接入；
- 一个物理端口是否被多条连接占用；
- 进路相邻区段是否存在与方向一致的不跨区段路径；
- 入口信号是否位于首区段；
- 从任一入口信号是否能到达所有区段。

错误不会被静默接受；API 返回每个问题的 `code` 和中文 `message`，页面在“拓扑导入检查”中逐项展示。

## 显式状态机

进路状态：

```text
IDLE -> RESERVED -> ACTIVE -> RELEASED
IDLE / RESERVED / ACTIVE + 设备故障 -> FAULTED 或故障安全撤销
RESERVED + 无车取消 -> IDLE
FAULTED + 设备恢复：有车 -> ACTIVE，无车 -> IDLE
```

每次迁移都记录：

- 每个 guard 的名称、是否满足和证据文本；
- 被拒绝时的稳定错误码与原因；
- 接受后修改的锁、占用、设备故障和状态效果；
- 迁移前后完整状态快照。

安全不变量包括互斥占用、道岔/侧向防护互斥、侧向防护位置与健康、入口信号防护、故障安全以及释放顺序。`SAFE` 仅表示在给定深度和状态上限内完整穷举通过；`BOUND_REACHED` 明确表示没有证明安全。

## 并发验证和反例

验证器保留每个进程的程序计数，只允许执行该进程的下一个动作，因此探索的是程序内有序、进程间任意交错。BFS 按稳定进程 ID 排序扩展，保证报告中的反例是排序最短且跨机器可复现的一条。

反例报告不是单个坏状态，而是逐步时间线：

1. 进程和动作；
2. 触发该迁移所需的 guard；
3. guard 为什么满足；
4. 迁移效果；
5. 第一次违反的不变量和详细对象；
6. 前后区段占用、锁、道岔位置、故障设备和已释放区段。

被拒绝的迁移也会按“进程/动作/错误码”聚合并统计出现次数，因此可以区分“某条交错非法”和“合法交错进入冲突状态”。

状态归并的 key 是：进程计数 + 进路状态 + 区段占用/锁 + 道岔位置/锁 + 已释放区段 + 故障集合。当前实现不把不同进路 ID 做无依据重命名；只有资源和进路结构完全一致、且不被场景动作引用的等价空闲进路才允许解释性归并。示例中所有进路都被动作引用，因此报告明确说明只做精确合并，归并仍保留 BFS 父链，可完整回放。

## 规则候选与批准比较

内置版本：

- `safe-interlocking-v1`：完整批准规则；
- `weak-flank-v1`：关闭侧向防护 guard，用于产生侧向防护反例；
- `weak-release-v1`：允许乱序释放迁移，用于观察释放顺序反例。

页面选择候选规则并设置“深度上限/状态上限”后，可同时看到批准版与候选版的：

- 结论：`SAFE`、`COUNTEREXAMPLE`、`BOUND_REACHED`；
- 探索状态数、迁移数、剩余队列；
- 拒绝原因和出现次数；
- 最短反例长度；
- 可时间步进的完整轨迹。

“批准”会冻结拓扑、规则和场景三个 revision ID 及 SHA-256 指纹。后续编辑只生成新版本，不更新旧批准记录。

## 证据、派生版本和冲突

`raw_evidence` 只插入，不提供更新或删除接口。收到的原始拓扑、规则和场景 JSON 保存后，通过规范化 JSON 的 SHA-256 生成来源指纹。

派生 revision 保存：

- `baseRevisionId`；
- `sourceFingerprint`；
- 场景派生的 `topologyFingerprint`；
- 验证记录中的规则版本、场景版本和报告指纹；
- 手工故障场景的 `faultOfRevisionId`。

两个浏览器基于同一旧版本编辑时，后到请求携带旧 `baseRevisionId`，服务端发现 head 已变化即返回 HTTP 409，内容包含：

- incoming/current fingerprint；
- 当前最新 revision；
- 冲突实体和可重新合并的提示。

正确流程是读取当前版本，合并第一方和第二方意图，再以当前 head 作为新的 `baseRevisionId` 提交。页面“两浏览器旧版本冲突演示”会制造这一场景并展示 409 内容。

## 一次故障恢复复现

1. 启动应用并打开 `http://127.0.0.1:5215`。
2. 在“规则候选与批准版本比较”中选择 `rule-weak-flank-v1`，运行比较，观察批准版 `SAFE`、候选版长度 2 的侧向防护反例。
3. 在“手工设备故障”中选择 `scenario-crossing-trains-v1`，设备填 `T3`，插入序号填 `1`，提交。
4. 页面生成 `scenario-crossing-trains-v2`，其 `baseRevisionId` 和 `faultOfRevisionId` 都指向 v1，旧批准不变。
5. 在版本浏览器选择“场景”，查看 v2 原始 payload 中新增的 `DEVICE_FAULT T3` 和来源链。
6. 若要模拟恢复，可在规则编辑器或后续 API 中追加 `DEVICE_REPAIR T3`；状态机规则是有车进路 `FAULTED -> ACTIVE`，无车授权进路撤销为 `IDLE`。
7. 在“规则版本”中选择具体版本并点击“冻结为新批准版本”，即可形成新的拓扑/规则/场景指纹三元组。

命令行也可以插入故障：

```bash
curl -X POST http://127.0.0.1:5215/api/scenarios/scenario-crossing-trains-v1/fault \
  -H 'Content-Type: application/json' \
  -d '{"deviceId":"T3","afterActionIndex":1,"source":"recovery-drill"}'
```

## 换机验证包

页面“导出验证包”生成 zip：

- `manifest.json`；
- `topology.json`、`baseline-rule.json`、`candidate-rule.json`、`scenario.json`；
- 两份完整 verification report；
- 包内 README。

另一台机器使用同一应用版本导入 zip 时，会重新执行 BFS，而不是信任报告文本。重算出的报告 SHA-256 必须与 manifest 相同；相同输入和稳定排序会选出同一条排序最小反例。指纹不一致时导入被拒绝。

## 测试

```bash
./mvnw -q test
```

测试覆盖：

- 悬空端口、不可达区段和有界结论；
- 强规则安全拒绝与弱侧向防护最短反例；
- 乱序释放、占用顺序和完成释放；
- 有车/无车故障安全迁移与恢复；
- H2 内存库中的原始证据追加和 stale base 409 冲突。

## 主要代码位置

- 领域类型：`src/main/java/dev/railsandbox/domain/Models.java`
- 拓扑诊断：`src/main/java/dev/railsandbox/verify/TopologyValidator.java`
- 状态机：`src/main/java/dev/railsandbox/verify/RouteEngine.java`
- 不变量：`src/main/java/dev/railsandbox/verify/InvariantChecker.java`
- 有界 BFS：`src/main/java/dev/railsandbox/verify/Verifier.java`
- 版本与证据：`src/main/java/dev/railsandbox/persistence/RevisionStore.java`
- 验证包：`src/main/java/dev/railsandbox/service/PackageService.java`
- 页面：`src/main/resources/static/index.html`
