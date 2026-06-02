package com.myorg;

import software.amazon.awscdk.*;
import software.amazon.awscdk.services.ec2.*;
import software.amazon.awscdk.services.ecr.Repository;
import software.amazon.awscdk.services.s3.*;
import software.constructs.Construct;

import java.util.List;

/**
 * DR (Disaster Recovery) Stack - Tokyo Region (ap-northeast-1)
 * - DR VPC
 * - S3 Backup Bucket (replication target)
 * - ECR Repositories (mirror)
 */
public class DrStack extends Stack {

    public DrStack(final Construct scope, final String id, final StackProps props) {
        super(scope, id, props);

        // DR VPC
        Vpc drVpc = Vpc.Builder.create(this, "DrVpc")
                .vpcName("migration-dr-vpc")
                .maxAzs(2)
                .natGateways(1)
                .subnetConfiguration(List.of(
                        SubnetConfiguration.builder()
                                .name("Public").subnetType(SubnetType.PUBLIC).cidrMask(24).build(),
                        SubnetConfiguration.builder()
                                .name("Private").subnetType(SubnetType.PRIVATE_WITH_EGRESS).cidrMask(22).build()
                ))
                .build();

        // DR S3 存储桶
        Bucket.Builder.create(this, "DrBackupBucket")
                .bucketName(String.format("migration-dr-backup-%s", this.getAccount()))
                .versioned(true)
                .encryption(BucketEncryption.S3_MANAGED)
                .blockPublicAccess(BlockPublicAccess.BLOCK_ALL)
                .removalPolicy(RemovalPolicy.RETAIN)
                .build();

        // DR ECR 镜像仓库
        for (String repoName : List.of("app-frontend", "app-backend", "app-worker")) {
            Repository.Builder.create(this, "DrEcr-" + repoName)
                    .repositoryName("migration-dr/" + repoName)
                    .lifecycleRules(List.of(software.amazon.awscdk.services.ecr.LifecycleRule.builder()
                            .maxImageCount(20).description("Keep last 20 images").build()))
                    .removalPolicy(RemovalPolicy.RETAIN)
                    .build();
        }

        Tags.of(this).add("Project", "CloudMigration");
        Tags.of(this).add("Layer", "DR");
        Tags.of(this).add("Region", "Tokyo");
        Tags.of(this).add("ManagedBy", "CDK");
    }
}

