# AWS 飞书智能助手 (CDK)

基于 AWS Bedrock 的飞书机器人，支持 RAG 知识库检索增强生成。

## 架构

```
飞书用户 → API Gateway (/webhook POST)
              → Receiver Lambda (去重 + 入队)
                  → SQS FIFO Queue (按用户有序)
                      → Worker Lambda
                          → Bedrock LLM (生成回复)
                          → Bedrock Knowledge Base (RAG 检索)
                          → MCP Lambda (工具调用)
                      → 飞书 API (回复消息)

知识库数据链路：
S3 (知识文档) → Bedrock Data Source → OpenSearch Serverless (向量索引)
```

## 核心组件

| 组件 | 说明 |
|------|------|
| API Gateway | 飞书事件回调入口 |
| Receiver Lambda | 接收消息、去重、投递 SQS |
| Worker Lambda | 调用 Bedrock 生成回复并回复飞书 |
| MCP Lambda | AWS 产品/定价等工具查询 |
| SQS FIFO | 保证同一用户消息串行处理 |
| DynamoDB (conversations) | 对话历史存储 |
| DynamoDB (dedup) | 消息去重 |
| Bedrock Knowledge Base | RAG 知识检索 (Titan Embed V2) |
| OpenSearch Serverless | 向量存储 (1024维, HNSW/FAISS) |
| S3 Bucket | 知识文档存储 |
| Secrets Manager | 飞书凭据 |

## 前置条件

- AWS CLI 已配置，账号有足够权限
- Node.js (CDK CLI 依赖)、Java 17、Maven
- AWS CDK CLI (`npm install -g aws-cdk`)
- Bedrock 模型访问权限已开通（`amazon.titan-embed-text-v2:0` 及所用的对话模型）
- 飞书开放平台已创建机器人应用，获取 App ID、App Secret、Encrypt Key

## 项目结构

```
├── src/main/java/com/myorg/
│   ├── AwsFeishuAssistantCdkApp.java   # CDK 入口
│   └── AwsFeishuAssistantCdkStack.java # 基础设施定义
├── custom-resources/
│   └── aoss-index/index.py             # 自动创建 OpenSearch 向量索引
├── cdk.json                            # CDK 配置与 context 参数
└── pom.xml
```

业务 Lambda 源码通过 `sourceCodePath` context 参数指定（不在本仓库内）。

## 配置

`cdk.json` 中的 context 参数：

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `sourceCodePath` | `xxx/xxx/business-codes` | Lambda 业务代码路径 |
| `embeddingModelId` | `amazon.titan-embed-text-v2:0` | Embedding 模型 ID |

## 部署

```bash
# 1. 设置业务代码路径（替换为实际路径）
#    方式一：修改 cdk.json 中 context.sourceCodePath
#    方式二：命令行传入
cdk deploy -c sourceCodePath=/path/to/business-codes

# 2. 首次部署需要 bootstrap
cdk bootstrap

# 3. 部署
cdk deploy
```

## 部署后配置

### 1. 更新飞书凭据

部署输出会显示 `SecretsArn`，更新 Secret 值：

```bash
aws secretsmanager put-secret-value \
  --secret-id feishu-assistant/secrets \
  --secret-string '{"FEISHU_APP_ID":"your-app-id","FEISHU_APP_SECRET":"your-secret","FEISHU_ENCRYPT_KEY":"your-encrypt-key"}'
```

### 2. 配置飞书 Webhook

将部署输出的 `WebhookUrl` 填入飞书开放平台 → 机器人 → 事件订阅 → 请求地址。

### 3. 上传知识文档并同步

```bash
# 上传文档到 S3
aws s3 cp /path/to/docs/ s3://<KnowledgeBucketName>/ --recursive

# 触发知识库同步
aws bedrock-agent start-ingestion-job \
  --knowledge-base-id <KnowledgeBaseId> \
  --data-source-id <DataSourceId>
```

`KnowledgeBaseId`、`DataSourceId`、`KnowledgeBucketName` 均在部署输出中。

## 常用命令

| 命令 | 说明 |
|------|------|
| `mvn package` | 编译并运行测试 |
| `cdk synth` | 生成 CloudFormation 模板 |
| `cdk deploy` | 部署到 AWS |
| `cdk diff` | 对比已部署栈与当前代码差异 |
| `cdk destroy` | 销毁栈（S3 bucket 会保留） |

## 注意事项

- S3 知识文档桶设置了 `RemovalPolicy.RETAIN`，`cdk destroy` 不会删除
- Worker Lambda 超时 900s（Lambda 最大值），处理复杂对话可能耗时较长
- SQS 消息失败 3 次后进入死信队列 (`feishu-assistant-dlq.fifo`)，保留 14 天
- 对话历史和去重记录均有 TTL 自动过期
