# VFS 路径与文件操作语义 v0.1

## 本次修改（2026-09-29，取消命名空间白名单）

- 取消 API 的固定命名空间检查；命名空间与虚拟目录改为由后续配置决定，保留路径语法与规范化规则。涉及第 2、3 节。

## 本次修改（2026-09-28，恢复能力延后）

- 首版不实现文件操作中断恢复、自动补偿或重启修复；保留基本失败报告与状态事务。涉及第 1、4～12 节。
- 可靠事件重放与持久化消费进度列为后续优化，相关实现和验收不再阻塞首版。

日期：2026-09-28  
任务：T02  
状态：T02 审阅通过（2026-09-28）；未开始代码实现

依据：[T01 公共契约基线](VFS_公共契约草案_v0.1.md)、[技术设计](Alcyone_Virtual_File_System_VFS_技术设计_v0.2.md)、[核心用例](Alcyone_VFS_核心用例文档_v0.2.md)、[开发计划](../开发计划与进度.md)。

## 1. 审阅重点与范围

T01 已确认无调用身份的文件 API、VfsEntry / NodeInfo、Metadata 和 SDK 边界。本稿补充操作语义，不修改已确认的接口签名。用户已确认本稿无问题，下文规则作为 T02 实施基线；明确交给 T03 / T04 的设计和能力验证不视为已完成。

| 编号 | 建议 | 调用方看到的行为 |
| --- | --- | --- |
| S1 | 路径段拒绝 `.` / `..`，目录尾斜线不产生另一份身份 | 不猜测相对路径，不让不同 URI 表示同一路径却注册两次 |
| S2 | Mount 覆盖其子树，list 合并直接可见的挂载入口 | 嵌套挂载可被发现；不会把父后端同名内容混进子 Mount |
| S3 | write 和 move 的目标父目录缺失时自动创建 | Context System 无需为每层父目录单独发起操作 |
| S4 | 写入按 CREATE_NEW / REPLACE_EXISTING / UPSERT；覆盖保留 Node ID | 内容更新不丢 Metadata；首版 move 不覆盖已有目标 |
| S5 | 普通目录支持同 / 跨 Mount 移动，目录内嵌套 Mount 则拒绝整体移动 / 删除 | 不因递归操作意外迁移或删除其他挂载的数据 |
| S6 | 同一 Runtime 协调重叠路径的变更，不承诺抵抗绕过 VFS 的并发写入 | 明确文件语义与后端原子性的边界，不引入分布式锁 |

本轮不设计数据库事务（T03）或恢复日志（后续 E04）、依赖的实际能力与平台验证（T04）、大文件流式签名（T04）或 Git 同步配置（E1）。

## 2. URI 与逻辑路径

### 2.1 接受的逻辑空间

顶层命名空间由后续 Runtime 配置提供，以下以 memory / resources 为例；API 解析不检查配置成员资格：

| URI | VfsPath | 解释 |
| --- | --- | --- |
| `alcyone://` | `/` | 逻辑根目录，作为本稿补充的导航入口 |
| `alcyone://memory/` | `/memory` | Memory 命名空间根 |
| `alcyone://resources` | `/resources` | Resources 命名空间根 |
| `alcyone://resources/a/b.txt` | `/resources/a/b.txt` | 普通文件或目录路径 |

scheme 按不区分大小写识别，输出统一为 `alcyone`；所有路径段（包括第一段）保留大小写，不限制顶层目录名称。拒绝 userinfo、端口、query 和 fragment，不把其中任何一部分悄悄丢弃。

`alcyone://` 使用 VFS 自身的根表示，不要求通用 URL 的 host 解析结果完整表达本协议；`memory` / `resources` 必须保留为 VfsPath 的第一段。

### 2.2 规范化规则

1. 按 `/` 切分路径段；只允许末尾有一个可省略的分隔符，内部连续 `/` 拒绝。
2. 每段按严格 UTF-8 规则进行一次百分号解码；非法转义或非法字节序列拒绝。
3. 解码后的整段为 `.` 或 `..` 时拒绝；段内含 `/`、反斜线、NUL 或控制字符时拒绝。拒绝原始反斜线，避免平台间将其当分隔符。
4. 不进行文件名大小写折叠或 Unicode 归一化，不自动 trim 文件名；允许合法中文等 Unicode 字符。原始空格要求使用 `%20`，不猜测 URI 中的空白。
5. VfsPath 使用已解码的绝对逻辑路径；除 `/` 外不保留尾斜线。VfsUri 序列化时逐段编码，保留 unreserved 字符，其余按 UTF-8 百分号编码，十六进制大写；单段路径对应的 URI 输出尾斜线；`isNamespaceRoot` 仅表示这一结构，不证明配置中存在该命名空间。
6. URI → VfsPath → URI 的规范结果应幂等。Storage Adapter 接收已解码的 StoragePath，不得再次执行 URI 解码。

`/resources/a` 与 `/resources/a/` 是同一逻辑位置；尾斜线不负责指定类型，实际类型由虚拟命名空间或 Storage 确定。

### 2.3 例子

| 输入 | 结果 |
| --- | --- |
| `alcyone://notes/a` | 接受；解析不证明存在对应 Mount 或资源 |
| `alcyone://Resources/a` | 接受并保留大小写，与 `/resources/a` 不同 |
| `alcyone://notes` | 规范为 `alcyone://notes/`；path 为 `/notes` |
| `ALCYONE://resources/a/` | URI 规范为 `alcyone://resources/a`；path 为 `/resources/a` |
| `alcyone://resources/%61.txt` | 与 `alcyone://resources/a.txt` 相同 |
| `alcyone://resources/报告.md` | 接受；序列化为对应 UTF-8 百分号编码 |
| `alcyone://resources/a%20b.txt` | 文件名为 `a b.txt` |
| `alcyone://resources/a//b` | INVALID_URI |
| `alcyone://resources/../memory/a` | INVALID_URI |
| `alcyone://resources/%2e%2e/a` | INVALID_URI |
| `alcyone://resources/a%2Fb` | INVALID_URI，编码分隔符不能变成路径层级 |
| `alcyone://resources/%252e%252e` | 普通文件名 `%2e%2e`，不再解码为 `..` |
| `alcyone://resources/a?x=1` | INVALID_URI；文件名中的问号须编码为 `%3F` |
| `alcyone://resources:80/a` | INVALID_URI |

`VfsPath` 的直接构造输入是已解码的路径，仍执行相同的段边界检查，但不会解码字面百分号。不同输入入口不能用不同的路径穿越规则。

## 3. Mount 与命名空间

### 3.1 配置与路由

- Mount 路径先按 VfsPath 规范化，重复逻辑路径拒绝初始化；允许挂在配置的命名空间根或其子目录，不允许挂在 `/`。
- Mount 在 Runtime 存活期间固定；新增 / 移除 / 重挂载需要关闭后重新配置，跨重启如何迁移 Registry 由 T03 定义。
- 使用最长的完整路径段前缀匹配：`/resources/a` 匹配 `/resources/a/x`，不匹配 `/resources/abc`。
- 去掉匹配的 Mount 前缀得到 StoragePath；恰好访问 Mount 根时，Adapter 接收“后端根”，不将空字符串误当上级目录。
- 发现可识别的相同后端、相同或重叠物理根映射时拒绝配置，避免两个逻辑路径指向同一资源却注册不同 ID 或跨 Mount 移动误删目标。Local FS 可规范化物理根，WebDAV 按可识别的 endpoint / root 检查；服务器别名等无法识别的情况不承诺自动识别，上层应提供无别名映射。

### 3.2 虚拟目录与 Mount 覆盖

为使只配置深层 Mount 时仍能浏览，建议从命名空间配置与 Mount 配置推导必要目录，不另存完整目录树：

- `/` 与配置中的命名空间根是虚拟目录；即使没有对应 Mount，也能 stat / list。例如配置包含 memory / resources 时，才据此提供 `/memory`、`/resources`，不能仅凭 URI 解析成功生成虚拟目录。
- 某 Mount 的缺失逻辑祖先表现为虚拟目录。例如仅配置 `/resources/medical/ct` 时，`/resources/medical` 仍可列出 `ct`。
- Mount 路径作为目录入口，覆盖父后端同名文件 / 目录及其整个子树。父后端数据保留在原处，但通过该逻辑入口不可见。
- 必要祖先与父后端同名文件冲突时也以逻辑目录优先，不自动删除 / 改写那个物理文件。配置和诊断信息应能解释该覆盖关系。
- 没有匹配 Mount 且不是以上虚拟目录的路径返回 MOUNT_NOT_FOUND；已匹配 Mount 内不存在的普通资源返回 NOT_FOUND。

例如父 Mount 为 `/resources`，子 Mount 为 `/resources/medical`：访问 `/resources/medical/a` 只去子 Mount，不在失败时退回父后端的 `medical/a`。

### 3.3 查询虚拟目录

- list 只根据配置推导必要条目，nodeId 未注册时为空，storage 为空，不制造物理目录。
- stat 对纯虚拟目录可以懒注册 DIRECTORY Node，物理映射为空，storage 为空。这是已通过 T02 审阅的补充，扩展了 T01 对物理资源存在性的描述。
- 虚拟祖先若同时对应一个实际后端目录，合并其内容；实际 stat 属性可返回后端已知值。
- 恰好匹配 Mount 根时，stat 默认确认后端根是目录；后端不可用时返回错误，不以“配置中存在 Mount”伪装后端正常。`includeStorage=false` 对已有 Node 仍可仅返回逻辑记录。
- 命名空间根、Mount 根和承载其他 Mount 的祖先目录不可通过文件 API 移动、覆盖或删除。由配置决定的目录不等同于普通可删除文件。

## 4. 物理路径与后端限制

StoragePath 必须是挂载根内的相对路径；Adapter 拼接后仍须检查边界。URI 规范化只是第一步，不能单靠字符串前缀判断物理路径是否在根内。

Local FS 建议首版不跟随挂载根内部的符号链接：读取、写入、stat、列表遇到链接条目、移动或删除涉及链接时返回 UNSUPPORTED_OPERATION。配置根本身可在初始化时解析到确定物理目录；内部路径的检查和操作不能声称仅靠预检查就阻止外部进程并发替换链接。T04 验证 OpenDAL / 平台能否提供所需限制，无法保证时应阻止该配置或相关操作，而非悄悄放宽边界。

WebDAV 的 root、编码和后端目录类型由 Adapter 处理；服务端内部链接无法由 VFS 一概探测，实际边界依赖服务端配置，T04 要记录验证范围。

首版不增加可配置只读挂载开关。后端自身只读、拒绝访问、文件名不受支持、原生 move 缺失等，按真实能力与错误处理。逻辑路径区分大小写，但不承诺能在大小写不敏感的物理后端同时保存 `A` 与 `a`；遇到可识别的物理别名冲突须拒绝而非分配双重身份，检测策略由 T04 验证。

## 5. 查询与目录列表

### 5.1 read / stat / getNode

- read 只读取 FILE；目录返回 TYPE_MISMATCH，不隐式压缩目录，不注册 Node。
- stat 默认确认实际资源并获取可用属性；纯虚拟目录按第 3.3 节处理。普通路径没有实体时不凭旧 Registry 记录假装存在。
- getNode(id) 与 `stat(includeStorage=false)` 对已注册 Node 返回逻辑记录，不保证物理文件此刻仍存在。
- 外部文件删除 / 改型等与 Registry 冲突时，不在查询中自动删除 Metadata 或换 Node ID。类型冲突返回 CONFLICT；相同路径被外部删除再重建但无法辨识时，不承诺识别其世代。修复机制留到后续 E04。

### 5.2 list 的合并算法

1. 确认目标在逻辑空间是目录；普通文件返回 TYPE_MISMATCH。
2. 有实际后端目录时调用其 list，收集直接子项；纯虚拟目录不访问不存在的后端位置。
3. 根据后代 Mount 推导本层必须可见的直接目录入口，覆盖父后端同名条目。深层挂载只贡献它在这一层的第一段。被逻辑目录遮蔽的同名物理文件不参与列表，不将其作为 TYPE_MISMATCH 报错；普通未被遮蔽文件仍按第 1 步处理。
4. 按规范化后的直接子路径去重，从 Registry 补充已注册 Node ID；不注册新 Node，不额外对每一项调用 stat 补全属性。
5. 返回 List<VfsEntry>；无排序保证，测试按集合比较，不保证结果是并发变更下的一致快照。

父目录列表不要求连接每个子 Mount，所以即使子后端离线，入口仍可见；进入子 Mount 后按实际后端报错。若本次需要读取的父后端 list 失败，则整个 list 失败，不仅返回挂载入口来伪装完整结果；NOT_FOUND 仅在路径确为配置推导目录且物理部分缺失时可视为空物理列表。

首版不公开分页。后端分页由 Adapter 在内部完整遍历；若内部资源限额使完整结果无法形成，应报 LIMIT_EXCEEDED，不能静默截断。具体限额在 T04 确定。

## 6. 创建与写入

### 6.1 模式和资源身份

| 目标状态 | CREATE_NEW | REPLACE_EXISTING | UPSERT（默认） |
| --- | --- | --- | --- |
| 文件不存在 | 创建 | NOT_FOUND | 创建 |
| 已存在普通文件 | ALREADY_EXISTS | 覆盖 | 覆盖 |
| 已存在目录 | TYPE_MISMATCH | TYPE_MISMATCH | TYPE_MISMATCH |
| 命名空间 / Mount 根或配置推导目录 | 不允许变更，见第 8 节边界 | 同左 | 同左 |

覆盖只改变内容，保留同一个已注册 Node ID 和 Metadata。不允许把目录写成文件。已存在但未注册的文件在成功写入后建立身份；事件按实际文件存在性判定 FILE_CREATED / FILE_WRITTEN，不以 Registry 是否已有记录来猜测。

### 6.2 父目录与空目录

write 和 move 建议自动创建缺失的目标父目录，无需新增公共参数：

- 完成路径、Mount、类型 / 模式和已知后端能力检查后才创建父目录；REPLACE_EXISTING 目标不存在或 CREATE_NEW 目标已存在时不产生父目录副作用。
- 仅在目标所属 Mount 内创建物理目录，不通过该动作创建 / 改写其他 Mount 配置。
- 某个实际父路径为文件时返回 TYPE_MISMATCH；配置推导目录按第 3 节规则处理。
- 自动创建父目录不批量注册 Node，也不引入单独的公开目录创建事件；事件中是否记录受影响父路径由 T03 定义。
- 写入失败时允许留下此次创建的空父目录，但不能回删已经被其他操作使用的目录；错误 effect 须反映实际副作用，不声称 NONE。

首版不增加 mkdir API。独立创建空目录暂不提供公共操作；可通过写入文件生成父目录，删除该文件后目录仍可保留。跨 Mount 目录移动必须保留已有的空子目录，因此 Storage Port 需要内部创建目录的能力；没有该能力的组合返回 UNSUPPORTED_OPERATION，不能悄悄丢弃空目录。

## 7. 移动与重命名

### 7.1 基础规则

- 源必须存在，目标必须不存在；目标为文件或目录都不覆盖，不将目标目录自动解释为“放入其中”。
- 目标父目录按第 6.2 节处理；逻辑 URI 规范化后源与目标相同，返回 INVALID_ARGUMENT，不发变更事件。
- 目录不能移入自身子树；源 / 目标属于受保护的配置目录时按第 8 节拒绝。
- 同 Mount 优先使用满足语义的原生 move。若不支持，则可按 read / write / delete 回退，只在所需能力齐全且满足目标确认与源删除顺序时启用；否则明确不支持。
- 不同 Mount 统一执行复制内容 → 确认目标成功 → 删除源 → 更新同一 Node 映射，实际状态记录时序交由 T03。

### 7.2 目录移动

普通目录支持同 Mount 与跨 Mount 移动，包含空目录。递归处理所有实际条目，保持已注册根 / 子 Node 的 ID 与 Metadata；未注册子项不因移动而批量注册，只有源根需要按既有用例建立身份。

目标目录必须全新，不合并两棵目录树。跨 Mount 应先完成整个源树的目标复制和确认，再开始删除源，不能采用“复制一项就删一项”使后续复制失败时源树已缺失。删除源时仍可能部分失败，不承诺目录级原子性。

目录内存在嵌套 Mount，或目标路径会包含已配置 Mount 时，整个 move 在物理变更前拒绝。普通目录跨两个 Mount 移动仍受支持；“不能搬动挂载边界”不等于“不能跨后端搬文件”。

复制完整性至少核对成功写入和能获取的长度 / 条目集合，不将长度相等声称为内容哈希一致。具体确认在实现中明确，断点续做和崩溃恢复延后至 E04。

## 8. 删除与配置边界

### 8.1 删除规则

- 删除普通文件不要求 recursive；缺失路径返回 NOT_FOUND，不默认幂等成功。
- `recursive=false` 可删除空普通目录，非空返回 DIRECTORY_NOT_EMPTY。
- `recursive=true` 可递归删除普通目录，不自动清理目标路径之外的空父目录。
- 物理操作完成后处理已注册 Node / Metadata，具体删除记录和历史保留策略由 T03 定义。无需注册未注册文件后再删除。
- 递归删除不是全局原子事务；失败时允许部分内容已删除，但必须报告实际部分完成情况，不自动恢复。

### 8.2 不允许的结构性变更

| 操作对象 | 行为 |
| --- | --- |
| `/`、`/memory`、`/resources` | read 为 TYPE_MISMATCH；write / move / delete 为 UNSUPPORTED_OPERATION |
| 任一 Mount 根 | 允许 stat / getNode / list，read 目录为 TYPE_MISMATCH；物理目录不能被 write / move / delete 替换或删除 |
| 包含其他 Mount 的祖先目录 | 允许查询；整体移动 / 删除为 UNSUPPORTED_OPERATION，即使 recursive=true |
| move 的目标路径包含已配置 Mount | UNSUPPORTED_OPERATION，不能通过目标创建覆盖配置子树 |
| 普通目录中的普通文件 | 按所属 Mount 正常操作，不受上述整树限制影响 |

这些限制仅保护 Mount 结构，不是调用者差异规则。比如 `/resources/work/medical` 是子 Mount，可以删 `/resources/work/note.md`，但不能整体删 `/resources/work`；进入子 Mount 后，其普通子文件仍可正常删除。

校验优先拒绝纯参数错误和已知配置结构冲突，然后检查路由 / 存储状态。stat / list 对实际 Mount 根的错误按第 3.3 节处理，不承诺未知后端状态在初始化时已全部验证。

## 9. 后端能力、错误与并发边界

### 9.1 能力与错误

| 情况 | 对外行为 |
| --- | --- |
| URI 格式、越界路径、同路径 move、自包含 move | INVALID_URI 或 INVALID_ARGUMENT，具体入口分类保持一致 |
| 无 Mount，且不是配置推导目录 | MOUNT_NOT_FOUND |
| 已有 Mount 内目标不存在 | NOT_FOUND |
| 已存在目标阻止创建 / 移动 | ALREADY_EXISTS |
| 文件 / 目录类型不匹配 | TYPE_MISMATCH |
| 后端明确报告只读 | READ_ONLY |
| 后端拒绝访问或凭据失败 | STORAGE_ACCESS_DENIED |
| 缺少所需能力，无法正确回退 | UNSUPPORTED_OPERATION |
| 无法确认后端是否完成、或只完成一部分 | 按 T03 的 effect 报告，不能无条件重试 |

不能为凑齐错误码猜测后端原因；无法可靠区分只读和拒绝访问时使用已知的具体错误类别，只有普通网络 / I/O 失败才归 STORAGE_ERROR。

### 9.2 保证范围

建议首版以一个 Runtime 管理一个状态库及其 Mount，不支持多个 Runtime / 进程同时变更相同状态库和物理资源。Runtime 内重叠路径的写入、移动和删除须协调，目录操作的锁定范围包含子树；变更串行化由 T03 定义。

CREATE_NEW / REPLACE_EXISTING 的存在性检查与执行对同一 Runtime 的竞争调用保持一致。对绕过 VFS 的外部并发修改不承诺条件写原子性；只有实际后端支持时才能提供更强保证。如果业务需要抵抗外部竞争，必须另行定义条件接口并通过 T04 验证，不能把先查后写包装成原子操作。

read / list 不承诺目录事务快照。Context System 应避免外部程序同时修改正在进行跨 Mount 复制的源树；检测到已知变化时返回冲突并报告已发生的副作用。直接外部写入不保证产生 VFS Event。

## 10. Metadata 及 T03 衔接

Metadata 查询 / 替换只针对有效 Node ID，不依赖物理文件可写性；后端只读时仍能维护逻辑 Metadata。状态库自身写入失败返回 STATE_ERROR，逻辑更新按事务回滚。整体替换沿用 T01，不新增字段级 patch 或 owner 语义。

T03 接收以下要求：

- 有效规范化 VfsPath 唯一；同一物理资源的可识别别名不能分配多个有效身份。
- 虚拟目录可以拥有无物理映射的稳定 Node ID；配置变更后的身份迁移须明确。
- 内容覆盖与同 / 跨 Mount 移动保留已注册 ID、Metadata；目录操作更新完整的已注册子树。
- 自动创建父目录、部分复制 / 删除、外部变化和事件记录失败均纳入故障矩阵。
- 普通操作未完成不能产生表示全操作成功的事件；成功提交后响应丢失或 Consumer 失败不回滚核心结果。

## 11. 验收场景清单

以下为设计验收样例，尚未写成或执行 SDK 测试。首版验证正常路径、拒绝路径与失败报告；中断与重启恢复延后。

| ID | 场景 | 预期 |
| --- | --- | --- |
| P01 | URI 为 `ALCYONE://resources/a/` | 规范化为同一位置，尾斜线不生成新 Node |
| P02 | 编码文件名 `%61.txt` 与 `a.txt` | 同一规范路径 |
| P03 | `..`、编码点段、编码分隔符、反斜线 | 解析失败，无 Storage 调用 |
| P04 | `%252e%252e` | 作为字面文件名，只解码一次 |
| P05 | 中文、空格、大小写不同文件名 | 按第 2 节规范编码和比较，不盲目大小写折叠 |
| M01 | Mount `/resources/a`，访问 `/resources/abc/x` | 不命中该 Mount |
| M02 | 两个规范化后同路径的 Mount | 配置失败 |
| M03 | 只挂 `/resources/medical/ct` | 从根 list 可逐级发现 medical / ct；list 不注册 Node |
| M04 | 父后端存在 medical，配置子 Mount medical | 逻辑入口只看子 Mount，不混合两后端子树 |
| M05 | 子 Mount 离线 | 父 list 可显示入口；进入后的实际 I/O 报错，无父后端回退 |
| M06 | 无覆盖 Mount 的普通路径 | MOUNT_NOT_FOUND；命名空间根仍可查询 |
| Q01 | list 普通未注册目录内容 | VfsEntry ID 可空，Registry 不批量增长 |
| Q02 | stat 未注册物理文件 / 纯虚拟目录 | 按各自存在依据注册稳定 Node，第二次 ID 相同 |
| Q03 | list 所需后端失败或分页中途失败 | 整体失败，不返回伪完整结果 |
| Q04 | getNode 指向外部已删除文件 | 返回逻辑记录；默认 stat(uri) 返回 NOT_FOUND |
| W01 | 三种写模式分别遇到缺失 / 已存在文件 | 符合第 6.1 节矩阵 |
| W02 | 覆盖已注册文件 | 内容更新，Node ID / Metadata 保持 |
| W03 | 写入到缺失的多层父目录 | 在目标 Mount 内补齐父目录，不注册每层 Node |
| W04 | 父路径为实际文件、目标为目录 | TYPE_MISMATCH，无文件内容覆盖 |
| W05 | 模式检查失败 | 不创建父目录或成功事件 |
| V01 | move 到已存在文件或目录 | ALREADY_EXISTS，不隐式移入目录 |
| V02 | move 到自己或自己子树 | INVALID_ARGUMENT，无变更 |
| V03 | 同 / 跨 Mount 移动普通目录，包含空目录 | 树内容保留，已注册子 Node ID / Metadata 保持 |
| V04 | 跨 Mount 复制中失败 | 不提前删除源；报告残留目标，不自动恢复 |
| V05 | 目录内含子 Mount，或目标包含已配置 Mount | 整体变更前 UNSUPPORTED_OPERATION |
| D01 | 非递归删除空目录 / 非空目录 | 前者成功，后者 DIRECTORY_NOT_EMPTY |
| D02 | 递归删除普通目录 | 清理其资源和有效逻辑记录，不自动删除外部父目录 |
| D03 | 删除根、Mount 根或 Mount 的祖先 | UNSUPPORTED_OPERATION |
| B01 | Local FS 路径遇到符号链接 | 不跟随；按能力验证结果明确拒绝 |
| B02 | 后端只读 / 凭据失败 / 缺失目录创建能力 | 对应错误，不能报告成功或丢失空目录 |
| C01 | 同一 Runtime 两个 CREATE_NEW 竞争同一路径 | 恰有一个成功，另一个 ALREADY_EXISTS |
| C02 | 只读物理文件的 Metadata 更新 | 状态库可写时成功，不访问物理 Storage |

## 12. 与已通过 T01 的关系及下一步

本稿延续现有 API，不新增身份、分页或 mkdir 参数。以下补充已通过审阅，但不代表代码已经实现：

1. 根与配置推导虚拟目录的 stat / list 规则，允许虚拟目录 Node 没有物理映射。
2. 写入 / 移动自动创建父目录；独立 mkdir 暂不进入首版。
3. 含子 Mount 的目录禁止整体迁移 / 删除，普通跨 Mount 目录操作仍保留。
4. 原生 move 不支持时允许按确认顺序进行复制删除回退；实际能力、符号链接 / 物理别名保障在 T04 验证。
5. 一个 Runtime 内协调变更，外部并发写入不作为首版强原子保证范围。

T02 已于 2026-09-28 获用户确认，进入 [T03 状态与事务设计](VFS_状态与恢复设计_v0.1.md)。当前只完成文档检查，未运行后端测试；后端能力由 T04 验证，实现细节可在开发中明确。
