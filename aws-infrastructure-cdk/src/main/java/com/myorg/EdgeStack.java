package com.myorg;

import software.amazon.awscdk.*;
import software.amazon.awscdk.services.cloudfront.*;
import software.amazon.awscdk.services.cloudfront.origins.S3BucketOrigin;
import software.amazon.awscdk.services.globalaccelerator.*;
import software.amazon.awscdk.services.route53.*;
import software.amazon.awscdk.services.s3.BlockPublicAccess;
import software.amazon.awscdk.services.s3.Bucket;
import software.amazon.awscdk.services.s3.BucketEncryption;
import software.constructs.Construct;

/**
 * Edge & CDN Stack
 * - Amazon CloudFront Distribution
 * - AWS Global Accelerator
 * - Amazon Route 53 Hosted Zone
 * - Origin S3 Bucket (static assets)
 * Note: WAF for CloudFront requires us-east-1, deploy separately if needed
 */
public class EdgeStack extends Stack {

    public EdgeStack(final Construct scope, final String id, final StackProps props) {
        super(scope, id, props);

        Bucket originBucket = Bucket.Builder.create(this, "OriginBucket")
                .bucketName(String.format("migration-cdn-origin-%s", this.getAccount()))
                .encryption(BucketEncryption.S3_MANAGED)
                .blockPublicAccess(BlockPublicAccess.BLOCK_ALL)
                .build();

        // CloudFront 分发（WAF 需在 us-east-1 单独创建后关联）
        Distribution.Builder.create(this, "CdnDistribution")
                .comment("Migration CDN Distribution")
                .defaultBehavior(BehaviorOptions.builder()
                        .origin(S3BucketOrigin.withOriginAccessControl(originBucket))
                        .viewerProtocolPolicy(ViewerProtocolPolicy.REDIRECT_TO_HTTPS)
                        .cachePolicy(CachePolicy.CACHING_OPTIMIZED)
                        .build())
                .minimumProtocolVersion(SecurityPolicyProtocol.TLS_V1_2_2021)
                .httpVersion(HttpVersion.HTTP2_AND_3)
                .priceClass(PriceClass.PRICE_CLASS_200)
                .enabled(true)
                .build();

        // GA 加速器
        Accelerator.Builder.create(this, "GlobalAccelerator")
                .acceleratorName("migration-ga")
                .enabled(true)
                .build();

        // Route 53 托管区
        HostedZone.Builder.create(this, "HostedZone")
                .zoneName("company-migration.com")
                .comment("Migration project DNS zone")
                .build();

        Tags.of(this).add("Project", "CloudMigration");
        Tags.of(this).add("Layer", "Edge");
        Tags.of(this).add("ManagedBy", "CDK");
    }

}

