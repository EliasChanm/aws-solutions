# Cloud Migration Infrastructure (AWS CDK)

企业级云迁移基础设施，使用 AWS CDK (Java) 定义，采用多栈分层架构。

- **主区域**: ap-southeast-1 (新加坡)
- **灾备区域**: ap-northeast-1 (东京)

## 架构概览

```
┌─────────────────────────────────────────────────────────────────┐
│                        Primary Region (SGP)                      │
│                                                                 │
│  NetworkStack ──▶ SecurityStack ──┬──▶ DataStack ──▶ ObservabilityStack
│                                   └──▶ ComputeStack             │
│                                                                 │
│  EdgeStack (独立)                                                │
└─────────────────────────────────────────────────────────────────┘
┌─────────────────────────────────────────────────────────────────┐
│                        DR Region (Tokyo)                         │
│  DrStack (独立)                                                  │
└─────────────────────────────────────────────────────────────────┘
```

## Stack 说明

| Stack | 描述 | 核心资源 |
|-------|------|----------|
| NetworkStack | 网络基础 | VPC, Subnets (Public/Private/Isolated), NAT, VPC Endpoints, Flow Logs |
| SecurityStack | 安全层 | KMS Key, IAM Roles, WAF Web ACL, Security Groups |
| DataStack | 数据层 | Aurora MySQL (Multi-AZ), ElastiCache Redis, S3, ECR |
| ComputeStack | 计算层 | EKS 1.33, Bastion Host, Lambda |
| EdgeStack | 边缘/CDN | CloudFront, Global Accelerator, Route 53 |
| ObservabilityStack | 可观测性 | CloudWatch (Logs/Alarms/Dashboard), CloudTrail, SNS |
| DrStack | 灾备 | DR VPC, S3 Backup Bucket, ECR Mirror |

## 前置条件

- Java 17+
- Maven 3.x
- AWS CDK CLI (`npm install -g aws-cdk`)
- 已配置 AWS 凭证的账号

## 部署

```bash
# 编译
mvn package

# 部署所有栈（使用默认参数）
cdk deploy --all

# 指定参数部署
cdk deploy --all \
  -c account=123456789012 \
  -c primaryRegion=ap-southeast-1 \
  -c drRegion=ap-northeast-1
```

## Context 参数

| 参数 | 默认值 | 说明 |
|------|--------|------|
| account | `CDK_DEFAULT_ACCOUNT` 环境变量 | AWS 账号 ID |
| primaryRegion | ap-southeast-1 | 主区域 |
| drRegion | ap-northeast-1 | 灾备区域 |

## 常用命令

| 命令 | 说明 |
|------|------|
| `mvn package` | 编译并运行测试 |
| `cdk ls` | 列出所有栈 |
| `cdk synth` | 生成 CloudFormation 模板 |
| `cdk deploy --all` | 部署所有栈 |
| `cdk diff` | 比较已部署栈与当前代码差异 |
