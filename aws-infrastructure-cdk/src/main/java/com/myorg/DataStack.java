package com.myorg;

import software.amazon.awscdk.*;
import software.amazon.awscdk.services.ec2.Peer;
import software.amazon.awscdk.services.ec2.Port;
import software.amazon.awscdk.services.ec2.SecurityGroup;
import software.amazon.awscdk.services.ec2.SubnetSelection;
import software.amazon.awscdk.services.ec2.SubnetType;
import software.amazon.awscdk.services.ec2.Vpc;
import software.amazon.awscdk.services.ecr.Repository;
import software.amazon.awscdk.services.ecr.RepositoryEncryption;
import software.amazon.awscdk.services.elasticache.CfnReplicationGroup;
import software.amazon.awscdk.services.elasticache.CfnSubnetGroup;
import software.amazon.awscdk.services.kms.Key;
import software.amazon.awscdk.services.rds.*;
import software.amazon.awscdk.services.s3.*;
import software.constructs.Construct;

import java.util.List;

/**
 * Data Stack
 * - Amazon Aurora MySQL (Multi-AZ)
 * - Amazon ElastiCache Redis
 * - Amazon S3 (logs, origin, backup)
 * - Amazon ECR
 */
public class DataStack extends Stack {
    private final Bucket logBucket;
    private final Bucket originBucket;

    public DataStack(final Construct scope, final String id, final StackProps props,
                     final Vpc vpc, final Key kmsKey) {
        super(scope, id, props);

        // S3 - 具有生命周期策略的日志存储桶
        this.logBucket = Bucket.Builder.create(this, "LogBucket")
                .bucketName(String.format("migration-logs-%s", this.getAccount()))
                .encryption(BucketEncryption.KMS)
                .encryptionKey(kmsKey)
                .versioned(true)
                .lifecycleRules(List.of(
                        software.amazon.awscdk.services.s3.LifecycleRule.builder()
                                .id("ArchiveAfter90Days")
                                .transitions(List.of(Transition.builder()
                                        .storageClass(StorageClass.GLACIER)
                                        .transitionAfter(Duration.days(90))
                                        .build()))
                                .build(),
                        software.amazon.awscdk.services.s3.LifecycleRule.builder()
                                .id("DeleteAfter365Days")
                                .expiration(Duration.days(365))
                                .build()
                ))
                .blockPublicAccess(BlockPublicAccess.BLOCK_ALL)
                .removalPolicy(RemovalPolicy.RETAIN)
                .build();

        // S3 - 存储 CloudFront 静态资源的 S3 存储桶
        this.originBucket = Bucket.Builder.create(this, "OriginBucket")
                .bucketName(String.format("migration-origin-%s", this.getAccount()))
                .encryption(BucketEncryption.KMS)
                .encryptionKey(kmsKey)
                .blockPublicAccess(BlockPublicAccess.BLOCK_ALL)
                .build();

        // S3 - 备份存储桶
        Bucket.Builder.create(this, "BackupBucket")
                .bucketName(String.format("migration-backup-%s", this.getAccount()))
                .encryption(BucketEncryption.KMS)
                .encryptionKey(kmsKey)
                .versioned(true)
                .blockPublicAccess(BlockPublicAccess.BLOCK_ALL)
                .removalPolicy(RemovalPolicy.RETAIN)
                .build();

        // 数据库安全组
        SecurityGroup dbSg = SecurityGroup.Builder.create(this, "DbSg")
                .vpc(vpc).description("Aurora DB Security Group").build();
        dbSg.addIngressRule(Peer.ipv4(vpc.getVpcCidrBlock()), Port.tcp(3306), "MySQL from VPC");

        SecurityGroup redisSg = SecurityGroup.Builder.create(this, "RedisSg")
                .vpc(vpc).description("Redis Security Group").build();
        redisSg.addIngressRule(Peer.ipv4(vpc.getVpcCidrBlock()), Port.tcp(6379), "Redis from VPC");

        // Aurora MySQL 数据库集群，启用 Multi-AZ
        DatabaseCluster.Builder.create(this, "AuroraCluster")
                .engine(DatabaseClusterEngine.auroraMysql(AuroraMysqlClusterEngineProps.builder()
                        .version(AuroraMysqlEngineVersion.VER_3_08_2)
                        .build()))
                .writer(ClusterInstance.provisioned("Writer", ProvisionedClusterInstanceProps.builder()
                        .instanceType(software.amazon.awscdk.services.ec2.InstanceType.of(
                                software.amazon.awscdk.services.ec2.InstanceClass.R6G,
                                software.amazon.awscdk.services.ec2.InstanceSize.LARGE))
                        .build()))
                .readers(List.of(ClusterInstance.provisioned("Reader", ProvisionedClusterInstanceProps.builder()
                        .instanceType(software.amazon.awscdk.services.ec2.InstanceType.of(
                                software.amazon.awscdk.services.ec2.InstanceClass.R6G,
                                software.amazon.awscdk.services.ec2.InstanceSize.LARGE))
                        .build())))
                .vpc(vpc)
                .vpcSubnets(SubnetSelection.builder().subnetType(SubnetType.PRIVATE_ISOLATED).build())
                .securityGroups(List.of(dbSg))
                .storageEncrypted(true)
                .storageEncryptionKey(kmsKey)
                .backup(BackupProps.builder().retention(Duration.days(14)).build())
                .deletionProtection(true)
                .removalPolicy(RemovalPolicy.RETAIN)
                .build();

        // ElastiCache Redis 集群
        CfnSubnetGroup redisSubnetGroup = CfnSubnetGroup.Builder.create(this, "RedisSubnetGroup")
                .description("Redis subnet group")
                .subnetIds(vpc.selectSubnets(SubnetSelection.builder()
                        .subnetType(SubnetType.PRIVATE_ISOLATED).build())
                        .getSubnetIds())
                .build();

        CfnReplicationGroup.Builder.create(this, "RedisCluster")
                .replicationGroupDescription("Migration Redis Cluster")
                .engine("redis")
                .cacheNodeType("cache.r6g.large")
                .numCacheClusters(2)
                .automaticFailoverEnabled(true)
                .atRestEncryptionEnabled(true)
                .transitEncryptionEnabled(true)
                .cacheSubnetGroupName(redisSubnetGroup.getRef())
                .securityGroupIds(List.of(redisSg.getSecurityGroupId()))
                .build();

        // ECR 镜像仓库
        for (String repoName : List.of("app-frontend", "app-backend", "app-worker")) {
            Repository.Builder.create(this, "Ecr-" + repoName)
                    .repositoryName("migration/" + repoName)
                    .encryption(RepositoryEncryption.KMS)
                    .encryptionKey(kmsKey)
                    .lifecycleRules(List.of(software.amazon.awscdk.services.ecr.LifecycleRule.builder()
                            .maxImageCount(50)
                            .description("Keep last 50 images")
                            .build()))
                    .removalPolicy(RemovalPolicy.RETAIN)
                    .build();
        }

        Tags.of(this).add("Project", "CloudMigration");
        Tags.of(this).add("Layer", "Data");
        Tags.of(this).add("ManagedBy", "CDK");
    }

    public Bucket getLogBucket() { return logBucket; }
    public Bucket getOriginBucket() { return originBucket; }
}

