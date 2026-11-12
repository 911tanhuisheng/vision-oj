# 数据库脚本说明

默认数据库名：**`tan`**（与 `application-prod.yml` 的默认连接一致；如设置 `MYSQL_URL`，以实际配置为准）。

## 安装

```bash
mysql -u root -p < schema.sql
```

或在支持 `DELIMITER` 的 MySQL 客户端中执行 `schema.sql` 全文。此文件包含完整表结构、管理员账号（`admin`/`123456`）、40 道题和一场示例比赛。`CREATE TABLE IF NOT EXISTS`、缺列检查及 `INSERT IGNORE` 不会覆盖已有记录；导入已有库前仍应先备份并核对实际连接的库名。

项目只保留这一份 SQL 文件。`schema.sql` 已包含个人模型配置表和调用统计表；已有数据库启动时，后端也会尝试补建这两张缺失的表。

| 项 | 值 |
|----|-----|
| 管理员账号 | `admin` |
| 管理员密码 | `123456` |

> 仅当 `app.security.pepper` 为空（默认）时密码有效；生产环境请尽快改密。

配置好数据库连接（`.env` 或 `application-local.yml`）后：

1. 在 **Linux + Docker** 机器上启动 **Sandbox**（判题必需，见仓库 [Sandbox/README.md](../../../../../Sandbox/README.md)）
2. 再启动主服务 `spingboot-init`，即可用 `admin` / `123456` 登录并提交代码

## 线上已有库

如果只需要修复最近登录设备字段，先确认主服务连接的库，再在该库执行 `SHOW COLUMNS FROM user;`，只补缺少的列。不要为了补字段删除数据库或覆盖现有用户、题目和提交记录。
