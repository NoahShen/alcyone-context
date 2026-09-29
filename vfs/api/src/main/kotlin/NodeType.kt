package alcyone.vfs

/** 节点类型；文件和目录都可成为 Node，实际类型由虚拟命名空间或 Storage 确定。 */
enum class NodeType { FILE, DIRECTORY }
