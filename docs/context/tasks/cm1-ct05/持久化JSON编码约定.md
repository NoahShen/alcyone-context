# 持久化 JSON 编码约定

## 本次修改（2026-10-28，B2独立复核定稿）

本约定描述context.db的JSON列，不改变公开SDK模型。虽由Persistence内部实现，落库格式属于兼容约定；后续变更必须明确兼容读取或显式迁移，不得静默改名。

- messages_json：有序对象数组，每项role为ChatRole名称字符串，content为字符串。
- domains_json：有序字符串数组；空数组可用。
- sources_json：有序对象数组；空数组可用。ConversationTurnRef编码为type=conversation_turn及runtime_id、agent_id、session_id、session_turn_id五个字符串字段；RawMemoryRef编码为type=raw_memory及memory_id两个字符串字段。不得以SQLite内部Turn自增ID替换四元业务键。
- 必需字段类型严格为JSON字符串；数值、布尔、null、对象/数组、缺字段或未知type不隐式转换，映射安全PERSISTENCE_UNAVAILABLE。合法字符串数字/布尔文本保持原样。
- JSON转义由kotlinx.serialization承担，顺序保留；不在JSON中添加隐式外键。来源存在、可追溯与环检查由Core相关业务流程负责；Organized与Raw的权威关联仍是organized_memory_source表。

依据：CT05架构与技术复核第5节，ContextJsonCodecs与真实库往返/非法注入测试。
