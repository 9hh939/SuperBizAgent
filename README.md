# SuperBizAgent

> 基于 Spring Boot + AI Agent 的智能问答与运维系统

## 📖 项目简介

企业级智能业务代理系统，包含两大核心模块：

### 1. RAG 智能问答
集成 Milvus 向量数据库和阿里云 DashScope，提供基于检索增强生成的智能问答能力，支持多轮对话和流式输出。

### 2. AIOps 智能运维
基于 AI Agent 的自动化运维系统，采用 Planner-Executor-Replanner 架构，实现告警分析、日志查询、智能诊断和报告生成。

## 🚀 核心特性

- ✅ **RAG 问答**: 向量检索 + 多轮对话 + 流式输出
- ✅ **AIOps 运维**: 智能诊断 + 多 Agent 协作 + 自动报告
- ✅ **工具集成**: 文档检索、告警查询、日志分析、时间工具
- ✅ **会话管理**: H2 持久化、重启恢复、历史窗口管理
- ✅ **Web 界面**: 提供测试界面和 RESTful API


## 🛠️ 技术栈

| 技术 | 版本 | 说明 |
|------|------|------|
| Java | 17 | 开发语言 |
| Spring Boot | 3.2.0 | 应用框架 |
| Spring AI | - | AI Agent 框架 |
| DashScope | 2.17.0 | 阿里云 AI 服务 |
| Milvus | 2.6.10 | 向量数据库 |

## 📦 核心模块

```
SuperBizAgent/
├── src/main/java/org/example/
│   ├── controller/
│   │   └── ChatController.java        # 统一接口控制器 ⭐
│   ├── service/
│   │   ├── ChatService.java           # 对话服务 ⭐
│   │   ├── AiOpsService.java          # AIOps 服务 ⭐
│   │   └── Vector*.java               # 向量服务
│   ├── agent/tool/                    # Agent 工具集
│   │   ├── DateTimeTools.java         # 时间工具
│   │   ├── InternalDocsTools.java     # 文档检索
│   │   ├── QueryMetricsTools.java     # 告警查询
│   │   └── QueryLogsTools.java        # 日志查询
│   └── config/                        # 配置类
├── src/main/resources/
│   ├── static/                        # Web 界面
│   └── application.yml                # 应用配置
└── aiops-docs/                        # 运维文档库
```


## 📡 核心接口

### 1. 智能问答接口

**流式对话（推荐）**
```bash
POST /api/chat_stream
Content-Type: application/json

{
  "Id": "session-123",
  "Question": "什么是向量数据库？"
}
```
支持 SSE 流式输出、自动工具调用、多轮对话。

**普通对话**
```bash
POST /api/chat
Content-Type: application/json

{
  "Id": "session-123",
  "Question": "什么是向量数据库？"
}
```
一次性返回完整结果，支持工具调用和多轮对话。

### 2. AIOps 智能运维接口

```bash
POST /api/ai_ops
```
自动执行告警分析流程，生成运维报告（SSE 流式输出）。

### 3. 会话管理

- `POST /api/chat/clear` - 清空会话历史
- `GET /api/chat/session/{sessionId}` - 获取会话信息
- `DELETE /api/chat/session/{sessionId}` - 删除会话及其持久化消息

普通聊天和流式聊天共用同一份后端会话上下文。默认仅保留最近 6 对用户/AI 消息，
数据写入本地 H2 文件数据库；浏览器 `localStorage` 继续负责页面历史展示。

> 当前会话管理面向本地演示环境：`sessionId` 只是会话定位符，不是身份凭证。
> 项目尚未实现登录、RBAC 或多租户隔离，生产部署需要接入认证并按用户/租户校验会话归属。

### 4. 文件管理

- `POST /api/upload` - 上传文件并自动向量化
- `GET /milvus/health` - Milvus 健康检查


## ⚙️ 核心配置

### application.yml

```yaml
server:
  port: 9900

# Milvus 向量数据库
milvus:
  host: localhost
  port: 19530

# 阿里云 DashScope
spring:
  datasource:
    url: "jdbc:h2:file:./data/chat-history;DB_CLOSE_ON_EXIT=FALSE"
  ai:
    dashscope:
      api-key: "${DASHSCOPE_API_KEY}" // 环境变量

# RAG 配置
rag:
  top-k: 3
  model: "qwen3-max"

chat:
  history:
    max-message-pairs: 6

# 文档分片
document:
  chunk:
    max-size: 800
    overlap: 100
```

### 环境变量

```bash
export DASHSCOPE_API_KEY=your-api-key
```


## 🚀 快速开始

### 1. 环境准备

```bash
# 设置 API Key
export DASHSCOPE_API_KEY=your-api-key
```

### 2. 启动应用

方法一： 手动启动
```bash
1.先启动向量数据库
docker compose up -d -f vector-database.yml

2.启动服务
mvn clean install
mvn spring-boot:run
```

方法二：一键启动
```bash
make init  # 会自动启动向量数据库并上传运维文档到向量库
```


### 3. 使用示例

**Web 界面**
```
http://localhost:9900
```

**命令行**
```bash
# 上传文档
curl -X POST http://localhost:9900/api/upload \
  -F "file=@document.txt"

# 智能问答
curl -X POST http://localhost:9900/api/chat \
  -H "Content-Type: application/json" \
  -d '{"Id":"test","Question":"什么是向量数据库？"}'

# 健康检查
curl http://localhost:9900/milvus/health
```


**版本**: v1.0.0  
**作者**: chief  
**许可证**: MIT
