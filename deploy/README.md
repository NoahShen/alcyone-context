# Alcyone Context Standalone 部署样例

## 本次修改（2026-11-11，B3 R1：修正 YAML 路径解析说明）

- Context/VFS Loader 以 **YAML 文件所在目录**解析 YAML 内相对路径，不以 systemd WorkingDirectory 或进程 CWD 为基准。
- 样例数据库与 storage 改为 `/var/lib/alcyone-context/...` 绝对路径，避免错误写入 `/etc`；仅修文档，不改 Loader/生产。

## 历史修改（2026-11-11，CT29 B3 静态样例）

- 新增 systemd 与 Caddy 最小样例，对应 Gradle `installDist` 唯一 Standalone 入口。
- 仅做静态配置说明；macOS arm64 的真实 JVM/进程测试已通过，**Linux systemd、Caddy、真实证书和生产部署均 not-run**。

## 1. 产物与目录

构建并复制完整分发目录，不只复制启动脚本：

```bash
./scripts/dev gradle :context:server:installDist
sudo install -d -m 0755 /opt/alcyone-context
sudo cp -a context/server/build/install/alcyone-context/. /opt/alcyone-context/
sudo groupadd --system alcyone-context
sudo useradd --system --gid alcyone-context --home-dir /var/lib/alcyone-context \
  --shell /usr/sbin/nologin alcyone-context
sudo install -d -o alcyone-context -g alcyone-context -m 0700 \
  /var/lib/alcyone-context/data /var/lib/alcyone-context/storage /var/lib/alcyone-context/backups
sudo install -d -o root -g alcyone-context -m 0750 /etc/alcyone-context
```

安装 `/etc/alcyone-context/config.yaml`，替换全部示例秘密，权限为 `root:alcyone-context`、`0640`。不要把真实 Key 写入仓库、service、Caddyfile 或命令行：

```yaml
context:
  database:
    path: /var/lib/alcyone-context/data/context.sqlite
vfs:
  database:
    path: /var/lib/alcyone-context/data/vfs.sqlite
  namespaces: [resources]
  mounts:
    - vfsPath: /resources
      storageKey: local
      backend:
        type: local
        root: /var/lib/alcyone-context/storage
server:
  host: 127.0.0.1
  port: 8080
  auth:
    readKey: REPLACE_WITH_READ_SECRET
    adminKey: REPLACE_WITH_ADMIN_SECRET
```

YAML 内的相对路径以 **YAML 文件所在目录**解析，不以 systemd `WorkingDirectory` 或进程当前目录为基准；例如 `/etc/alcyone-context/config.yaml` 中写 `./data/context.sqlite` 会指向 `/etc/alcyone-context/data/context.sqlite`，并被样例的 `ProtectSystem=strict` 拒绝。因此本例统一使用 `/var/lib/alcyone-context/...` 绝对路径。手工命令中的 `cd` 仅保持操作目录一致，不能改变 YAML 路径解析规则。

## 2. init、upgrade 与 run

首次必须显式初始化，普通 `run` 不会隐式建库：

```bash
cd /var/lib/alcyone-context
sudo -u alcyone-context /opt/alcyone-context/bin/alcyone-context init \
  --config /etc/alcyone-context/config.yaml
sudo -u alcyone-context /opt/alcyone-context/bin/alcyone-context run \
  --config /etc/alcyone-context/config.yaml
```

独立升级命令要求指定已存在且可写的备份目录：

```bash
cd /var/lib/alcyone-context
sudo -u alcyone-context /opt/alcyone-context/bin/alcyone-context upgrade \
  --config /etc/alcyone-context/config.yaml --backup-dir /var/lib/alcyone-context/backups
```

当前只有 Schema v1，`upgrade` 先创建独立校验的备份，再输出 `NO_OP schema_version=1 backup_id=<文件名>`。备份采用不覆盖目标，失败退出非零；保留备份文件及其异地/离线副本并限制访问。**当前没有自动 Schema 迁移、自动恢复或已验证的回滚路径；NO_OP 不是迁移成功。**

退出码：`0` 成功，`2` 参数/配置错误，`3` init/upgrade 失败，`4` run/启动/关闭失败。SIGTERM/SIGINT 正常关闭为 0，关闭失败为 4。macOS arm64 已用真实 installDist 验证信号、4 MiB 在途响应 drain 和 SQLite 重开。

## 3. systemd

安装 `systemd/alcyone-context.service` 为 `/etc/systemd/system/alcyone-context.service`，检查路径和发行版指令兼容性后：

```bash
sudo systemd-analyze verify /etc/systemd/system/alcyone-context.service
sudo systemctl daemon-reload
sudo systemctl enable --now alcyone-context
sudo systemctl status alcyone-context
journalctl -u alcyone-context
sudo systemctl restart alcyone-context
```

样例使用专用低权限用户、只读程序/配置、可写持久化状态目录、`Restart=on-failure`、SIGTERM 和 60 秒停止上限。日志进入 journal；不得在日志命令或 unit 中放入 Key。systemd 实机启动、重启和权限检查在本环境 **not-run**。

## 4. Caddy

将 `caddy/Caddyfile` 中 `context.example.com` 替换为已解析到主机的域名，按 Caddy 安装方式部署并验证：

```bash
caddy fmt --diff /etc/caddy/Caddyfile
caddy validate --config /etc/caddy/Caddyfile
sudo systemctl reload caddy
curl -i https://context.example.com/health/live
```

Caddy 仅 TLS 反代到 `127.0.0.1:8080`。`/health/live` 无 Key；`/health/ready`、Read 和 Admin 仍执行应用鉴权，代理不提供免 Key 路径。Authorization 原样转发，应用不使用客户端 Forwarded/X-Forwarded-* 做授权。Caddy、DNS、防火墙、真实证书签发及外部客户端测试均 **not-run**。
