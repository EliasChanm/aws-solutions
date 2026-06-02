package com.myorg;

import java.util.List;
import java.util.Map;

import software.constructs.Construct;
import software.amazon.awscdk.*;
import software.amazon.awscdk.services.apigateway.*;
import software.amazon.awscdk.services.bedrock.*;
import software.amazon.awscdk.services.dynamodb.*;
import software.amazon.awscdk.services.iam.*;
import software.amazon.awscdk.services.lambda.*;
import software.amazon.awscdk.services.lambda.Runtime;
import software.amazon.awscdk.services.lambda.eventsources.SqsEventSource;
import software.amazon.awscdk.services.logs.RetentionDays;
import software.amazon.awscdk.services.opensearchserverless.*;
import software.amazon.awscdk.services.s3.*;
import software.amazon.awscdk.services.secretsmanager.Secret;
import software.amazon.awscdk.services.secretsmanager.SecretStringGenerator;
import software.amazon.awscdk.services.sqs.DeduplicationScope;
import software.amazon.awscdk.services.sqs.FifoThroughputLimit;
import software.amazon.awscdk.services.sqs.Queue;

public class AwsFeishuAssistantCdkStack extends Stack {

    public AwsFeishuAssistantCdkStack(final Construct scope, final String id) {
        this(scope, id, null);
    }

    public AwsFeishuAssistantCdkStack(final Construct scope, final String id, final StackProps props) {
        super(scope, id, props);

        String sourceCodePath = this.getNode().tryGetContext("sourceCodePath") != null
                ? (String) this.getNode().tryGetContext("sourceCodePath")
                : "xxx/xxx/business-codes";

        String embeddingModelId = this.getNode().tryGetContext("embeddingModelId") != null
                ? (String) this.getNode().tryGetContext("embeddingModelId")
                : "amazon.titan-embed-text-v2:0";

        // ========== Secrets Manager ==========
        Secret feishuSecrets = Secret.Builder.create(this, "FeishuSecrets")
                .secretName("feishu-assistant/secrets")
                .description("Feishu bot credentials (FEISHU_APP_ID, FEISHU_APP_SECRET, FEISHU_ENCRYPT_KEY)")
                .generateSecretString(SecretStringGenerator.builder()
                        .secretStringTemplate("{\"FEISHU_APP_ID\":\"\",\"FEISHU_APP_SECRET\":\"\",\"FEISHU_ENCRYPT_KEY\":\"\"}")
                        .generateStringKey("_placeholder")
                        .build())
                .build();

        // ========== DynamoDB Tables ==========
        Table conversationTable = Table.Builder.create(this, "ConversationTable")
                .tableName("feishu-assistant-conversations")
                .partitionKey(Attribute.builder().name("pk").type(AttributeType.STRING).build())
                .sortKey(Attribute.builder().name("sort").type(AttributeType.STRING).build())
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .timeToLiveAttribute("ttl")
                .removalPolicy(RemovalPolicy.DESTROY)
                .build();

        Table dedupTable = Table.Builder.create(this, "DedupTable")
                .tableName("feishu-assistant-dedup")
                .partitionKey(Attribute.builder().name("message_id").type(AttributeType.STRING).build())
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .timeToLiveAttribute("ttl")
                .removalPolicy(RemovalPolicy.DESTROY)
                .build();

        // ========== SQS FIFO Queue ==========
        Queue dlq = Queue.Builder.create(this, "WorkerDLQ")
                .queueName("feishu-assistant-dlq.fifo")
                .fifo(true)
                .retentionPeriod(Duration.days(14))
                .build();

        Queue messageQueue = Queue.Builder.create(this, "MessageQueue")
                .queueName("feishu-assistant-queue.fifo")
                .fifo(true)
                .contentBasedDeduplication(false)
                .deduplicationScope(DeduplicationScope.MESSAGE_GROUP)
                .fifoThroughputLimit(FifoThroughputLimit.PER_MESSAGE_GROUP_ID)
                .visibilityTimeout(Duration.seconds(900))
                .retentionPeriod(Duration.days(1))
                .deadLetterQueue(software.amazon.awscdk.services.sqs.DeadLetterQueue.builder()
                        .queue(dlq)
                        .maxReceiveCount(3)
                        .build())
                .build();

        // ========== S3 Bucket for Knowledge Documents ==========
        Bucket knowledgeBucket = Bucket.Builder.create(this, "KnowledgeBucket")
                .bucketName("feishu-assistant-knowledge-" + this.getAccount() + "-" + this.getRegion())
                .removalPolicy(RemovalPolicy.RETAIN)
                .blockPublicAccess(BlockPublicAccess.BLOCK_ALL)
                .encryption(BucketEncryption.S3_MANAGED)
                .build();

        // ========== OpenSearch Serverless Collection ==========
        String collectionName = "feishu-kb";
        String indexName = "bedrock-knowledge-base-default-index";

        // Bedrock KB Role
        Role kbRole = Role.Builder.create(this, "KnowledgeBaseRole")
                .roleName("feishu-assistant-kb-role")
                .assumedBy(new ServicePrincipal("bedrock.amazonaws.com"))
                .build();

        // Custom Resource Lambda role for creating AOSS index
        Role indexCreatorRole = Role.Builder.create(this, "IndexCreatorRole")
                .assumedBy(new ServicePrincipal("lambda.amazonaws.com"))
                .managedPolicies(List.of(
                        ManagedPolicy.fromAwsManagedPolicyName("service-role/AWSLambdaBasicExecutionRole")))
                .build();

        // Encryption policy
        CfnSecurityPolicy encryptionPolicy = CfnSecurityPolicy.Builder.create(this, "AossEncryptionPolicy")
                .name("feishu-kb-enc")
                .type("encryption")
                .policy("{\"Rules\":[{\"ResourceType\":\"collection\",\"Resource\":[\"collection/" + collectionName + "\"]}],\"AWSOwnedKey\":true}")
                .build();

        // Network policy
        CfnSecurityPolicy networkPolicy = CfnSecurityPolicy.Builder.create(this, "AossNetworkPolicy")
                .name("feishu-kb-net")
                .type("network")
                .policy("[{\"Rules\":[{\"ResourceType\":\"collection\",\"Resource\":[\"collection/" + collectionName + "\"]},{\"ResourceType\":\"dashboard\",\"Resource\":[\"collection/" + collectionName + "\"]}],\"AllowFromPublic\":true}]")
                .build();

        // Collection
        CfnCollection aossCollection = CfnCollection.Builder.create(this, "AossCollection")
                .name(collectionName)
                .type("VECTORSEARCH")
                .description("Vector store for Feishu assistant knowledge base")
                .build();
        aossCollection.addDependency(encryptionPolicy);
        aossCollection.addDependency(networkPolicy);

        // Data access policy - grant both KB role AND index creator Lambda role
        CfnAccessPolicy dataAccessPolicy = CfnAccessPolicy.Builder.create(this, "AossDataAccessPolicy")
                .name("feishu-kb-access")
                .type("data")
                .policy("[{\"Rules\":[" +
                        "{\"ResourceType\":\"index\",\"Resource\":[\"index/" + collectionName + "/*\"],\"Permission\":[\"aoss:CreateIndex\",\"aoss:UpdateIndex\",\"aoss:DescribeIndex\",\"aoss:ReadDocument\",\"aoss:WriteDocument\",\"aoss:DeleteIndex\"]}," +
                        "{\"ResourceType\":\"collection\",\"Resource\":[\"collection/" + collectionName + "\"],\"Permission\":[\"aoss:CreateCollectionItems\",\"aoss:DescribeCollectionItems\",\"aoss:UpdateCollectionItems\"]}" +
                        "],\"Principal\":[\"" + kbRole.getRoleArn() + "\",\"" + indexCreatorRole.getRoleArn() + "\"]}]")
                .build();

        // Grant index creator Lambda IAM permission to call AOSS API
        indexCreatorRole.addToPolicy(PolicyStatement.Builder.create()
                .actions(List.of("aoss:APIAccessAll"))
                .resources(List.of(aossCollection.getAttrArn()))
                .build());

        // ========== Custom Resource: Create AOSS Index ==========
        Function indexCreatorFn = Function.Builder.create(this, "IndexCreatorFn")
                .runtime(Runtime.PYTHON_3_9)
                .handler("index.handler")
                .code(Code.fromAsset("custom-resources/aoss-index"))
                .role(indexCreatorRole)
                .timeout(Duration.minutes(5))
                .memorySize(256)
                .build();

        CustomResource aossIndex = CustomResource.Builder.create(this, "AossIndex")
                .serviceToken(indexCreatorFn.getFunctionArn())
                .properties(Map.of(
                        "CollectionEndpoint", aossCollection.getAttrCollectionEndpoint(),
                        "IndexName", indexName,
                        "VectorDimension", "1024"))
                .build();
        aossIndex.getNode().addDependency(aossCollection);
        aossIndex.getNode().addDependency(dataAccessPolicy);

        // ========== Bedrock Knowledge Base ==========
        knowledgeBucket.grantRead(kbRole);
        kbRole.addToPolicy(PolicyStatement.Builder.create()
                .actions(List.of("aoss:APIAccessAll"))
                .resources(List.of(aossCollection.getAttrArn()))
                .build());
        kbRole.addToPolicy(PolicyStatement.Builder.create()
                .actions(List.of("bedrock:InvokeModel"))
                .resources(List.of("arn:aws:bedrock:" + this.getRegion() + "::foundation-model/" + embeddingModelId))
                .build());

        CfnKnowledgeBase knowledgeBase = CfnKnowledgeBase.Builder.create(this, "KnowledgeBase")
                .name("feishu-assistant-kb")
                .description("Knowledge base for Feishu assistant")
                .roleArn(kbRole.getRoleArn())
                .knowledgeBaseConfiguration(CfnKnowledgeBase.KnowledgeBaseConfigurationProperty.builder()
                        .type("VECTOR")
                        .vectorKnowledgeBaseConfiguration(CfnKnowledgeBase.VectorKnowledgeBaseConfigurationProperty.builder()
                                .embeddingModelArn("arn:aws:bedrock:" + this.getRegion() + "::foundation-model/" + embeddingModelId)
                                .build())
                        .build())
                .storageConfiguration(CfnKnowledgeBase.StorageConfigurationProperty.builder()
                        .type("OPENSEARCH_SERVERLESS")
                        .opensearchServerlessConfiguration(CfnKnowledgeBase.OpenSearchServerlessConfigurationProperty.builder()
                                .collectionArn(aossCollection.getAttrArn())
                                .vectorIndexName(indexName)
                                .fieldMapping(CfnKnowledgeBase.OpenSearchServerlessFieldMappingProperty.builder()
                                        .vectorField("bedrock-knowledge-base-default-vector")
                                        .textField("AMAZON_BEDROCK_TEXT_CHUNK")
                                        .metadataField("AMAZON_BEDROCK_METADATA")
                                        .build())
                                .build())
                        .build())
                .build();
        knowledgeBase.getNode().addDependency(aossIndex);

        // S3 Data Source
        CfnDataSource knowledgeDataSource = CfnDataSource.Builder.create(this, "KnowledgeDataSource")
                .knowledgeBaseId(knowledgeBase.getAttrKnowledgeBaseId())
                .name("s3-knowledge-docs")
                .description("S3 data source for knowledge documents")
                .dataSourceConfiguration(CfnDataSource.DataSourceConfigurationProperty.builder()
                        .type("S3")
                        .s3Configuration(CfnDataSource.S3DataSourceConfigurationProperty.builder()
                                .bucketArn(knowledgeBucket.getBucketArn())
                                .build())
                        .build())
                .vectorIngestionConfiguration(CfnDataSource.VectorIngestionConfigurationProperty.builder()
                        .chunkingConfiguration(CfnDataSource.ChunkingConfigurationProperty.builder()
                                .chunkingStrategy("FIXED_SIZE")
                                .fixedSizeChunkingConfiguration(CfnDataSource.FixedSizeChunkingConfigurationProperty.builder()
                                        .maxTokens(512)
                                        .overlapPercentage(20)
                                        .build())
                                .build())
                        .build())
                .build();

        // ========== Lambda Layer ==========
        LayerVersion sharedLayer = LayerVersion.Builder.create(this, "SharedLayer")
                .layerVersionName("feishu-assistant-layer")
                .code(Code.fromAsset(sourceCodePath + "/layer"))
                .compatibleRuntimes(List.of(Runtime.PYTHON_3_9))
                .description("Shared dependencies for feishu assistant")
                .build();

        // ========== IAM: Common Lambda policies ==========
        PolicyStatement bedrockPolicy = PolicyStatement.Builder.create()
                .actions(List.of("bedrock:InvokeModel", "bedrock:Retrieve"))
                .resources(List.of("*"))
                .build();

        PolicyStatement secretsPolicy = PolicyStatement.Builder.create()
                .actions(List.of("secretsmanager:GetSecretValue"))
                .resources(List.of(feishuSecrets.getSecretArn()))
                .build();

        // ========== MCP Lambda ==========
        Function mcpLambda = Function.Builder.create(this, "McpLambda")
                .functionName("aws-knowledge-mcp-lambda")
                .runtime(Runtime.PYTHON_3_9)
                .handler("mcp.lambda_handler")
                .code(Code.fromAsset(sourceCodePath + "/lambda"))
                .timeout(Duration.seconds(60))
                .memorySize(256)
                .logGroup(software.amazon.awscdk.services.logs.LogGroup.Builder.create(this, "McpLambdaLog").retention(RetentionDays.ONE_WEEK).removalPolicy(RemovalPolicy.DESTROY).build())
                .build();

        // ========== Receiver Lambda ==========
        Function receiverLambda = Function.Builder.create(this, "ReceiverLambda")
                .functionName("feishu-assistant-receiver")
                .runtime(Runtime.PYTHON_3_9)
                .handler("receiver.handler")
                .code(Code.fromAsset(sourceCodePath + "/lambda"))
                .layers(List.of(sharedLayer))
                .timeout(Duration.seconds(30))
                .memorySize(256)
                .logGroup(software.amazon.awscdk.services.logs.LogGroup.Builder.create(this, "ReceiverLambdaLog").retention(RetentionDays.ONE_WEEK).removalPolicy(RemovalPolicy.DESTROY).build())
                .environment(Map.of(
                        "SECRETS_ARN", feishuSecrets.getSecretArn(),
                        "SQS_QUEUE_URL", messageQueue.getQueueUrl(),
                        "DEDUP_TABLE_NAME", dedupTable.getTableName()))
                .build();

        receiverLambda.addToRolePolicy(secretsPolicy);
        messageQueue.grantSendMessages(receiverLambda);
        dedupTable.grantReadWriteData(receiverLambda);

        // ========== Worker Lambda ==========
        Function workerLambda = Function.Builder.create(this, "WorkerLambda")
                .functionName("feishu-assistant-worker")
                .runtime(Runtime.PYTHON_3_9)
                .handler("unified_worker.handler")
                .code(Code.fromAsset(sourceCodePath + "/lambda"))
                .layers(List.of(sharedLayer))
                .timeout(Duration.seconds(900))
                .memorySize(1024)
                .logGroup(software.amazon.awscdk.services.logs.LogGroup.Builder.create(this, "WorkerLambdaLog").retention(RetentionDays.ONE_WEEK).removalPolicy(RemovalPolicy.DESTROY).build())
                .environment(Map.of(
                        "SECRETS_ARN", feishuSecrets.getSecretArn(),
                        "DDB_TABLE", conversationTable.getTableName(),
                        "BEDROCK_KB_ID", knowledgeBase.getAttrKnowledgeBaseId(),
                        "MCP_LAMBDA_NAME", mcpLambda.getFunctionName()))
                .build();

        workerLambda.addToRolePolicy(secretsPolicy);
        workerLambda.addToRolePolicy(bedrockPolicy);
        conversationTable.grantReadWriteData(workerLambda);
        mcpLambda.grantInvoke(workerLambda);

        workerLambda.addEventSource(SqsEventSource.Builder.create(messageQueue)
                .batchSize(1)
                .build());

        workerLambda.addToRolePolicy(PolicyStatement.Builder.create()
                .actions(List.of("pricing:GetProducts", "ec2:DescribeInstanceTypes"))
                .resources(List.of("*"))
                .build());

        // ========== API Gateway ==========
        RestApi api = RestApi.Builder.create(this, "FeishuWebhookApi")
                .restApiName("feishu-assistant-api")
                .description("Feishu webhook endpoint for the assistant bot")
                .build();

        api.getRoot().addResource("webhook").addMethod("POST",
                LambdaIntegration.Builder.create(receiverLambda).proxy(true).build());

        // ========== Outputs ==========
        CfnOutput.Builder.create(this, "WebhookUrl")
                .value(api.getUrl() + "webhook")
                .description("Feishu webhook URL - configure this in Feishu bot settings")
                .build();

        CfnOutput.Builder.create(this, "SecretsArn")
                .value(feishuSecrets.getSecretArn())
                .description("Update this secret with real Feishu credentials before use")
                .build();

        CfnOutput.Builder.create(this, "KnowledgeBaseId")
                .value(knowledgeBase.getAttrKnowledgeBaseId())
                .description("Bedrock Knowledge Base ID")
                .build();

        CfnOutput.Builder.create(this, "KnowledgeBucketName")
                .value(knowledgeBucket.getBucketName())
                .description("Upload knowledge documents to this S3 bucket, then sync the data source")
                .build();

        CfnOutput.Builder.create(this, "DataSourceId")
                .value(knowledgeDataSource.getAttrDataSourceId())
                .description("Data source ID - use with: aws bedrock-agent start-ingestion-job --knowledge-base-id <kb-id> --data-source-id <ds-id>")
                .build();
    }
}
