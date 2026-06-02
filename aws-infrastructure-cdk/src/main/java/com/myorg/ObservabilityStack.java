package com.myorg;

import software.amazon.awscdk.*;
import software.amazon.awscdk.services.cloudtrail.*;
import software.amazon.awscdk.services.cloudwatch.*;
import software.amazon.awscdk.services.kms.Key;
import software.amazon.awscdk.services.logs.*;
import software.amazon.awscdk.services.s3.Bucket;
import software.amazon.awscdk.services.sns.Topic;
import software.amazon.awscdk.services.cloudwatch.actions.SnsAction;
import software.constructs.Construct;

import java.util.List;
import java.util.Map;

/**
 * Observability Stack
 * - CloudWatch Log Groups, Dashboards, Alarms
 * - CloudTrail (audit trail)
 * - SNS Alert Topic
 */
public class ObservabilityStack extends Stack {

    public ObservabilityStack(final Construct scope, final String id, final StackProps props,
                              final Bucket logBucket, final Key kmsKey) {
        super(scope, id, props);

        // CloudWatch 日志组
        for (String logGroup : List.of("application", "eks-cluster", "aurora", "waf", "vpc-flow")) {
            LogGroup.Builder.create(this, "LogGroup-" + logGroup)
                    .logGroupName("/migration/" + logGroup)
                    .retention(RetentionDays.SIX_MONTHS)
                    .encryptionKey(kmsKey)
                    .removalPolicy(RemovalPolicy.RETAIN)
                    .build();
        }

        // 发送告警的 SNS Topic
        Topic alertTopic = Topic.Builder.create(this, "AlertTopic")
                .topicName("migration-alerts")
                .masterKey(kmsKey)
                .build();

        // CloudWatch 告警
        Alarm cpuAlarm = Alarm.Builder.create(this, "HighCpuAlarm")
                .alarmName("migration-high-cpu")
                .metric(Metric.Builder.create()
                        .namespace("AWS/EC2").metricName("CPUUtilization")
                        .statistic("Average").period(Duration.minutes(5))
                        .build())
                .threshold(80).evaluationPeriods(3)
                .comparisonOperator(ComparisonOperator.GREATER_THAN_THRESHOLD)
                .build();
        cpuAlarm.addAlarmAction(new SnsAction(alertTopic));

        Alarm dbConnAlarm = Alarm.Builder.create(this, "DbConnectionAlarm")
                .alarmName("migration-db-connections")
                .metric(Metric.Builder.create()
                        .namespace("AWS/RDS").metricName("DatabaseConnections")
                        .statistic("Average").period(Duration.minutes(5))
                        .build())
                .threshold(200).evaluationPeriods(2)
                .comparisonOperator(ComparisonOperator.GREATER_THAN_THRESHOLD)
                .build();
        dbConnAlarm.addAlarmAction(new SnsAction(alertTopic));

        // CloudWatch Dashboard
        Dashboard.Builder.create(this, "OperationsDashboard")
                .dashboardName("Migration-Operations")
                .widgets(List.of(List.of(
                        GraphWidget.Builder.create()
                                .title("EC2 CPU Utilization")
                                .left(List.of(Metric.Builder.create()
                                        .namespace("AWS/EC2").metricName("CPUUtilization")
                                        .statistic("Average").period(Duration.minutes(5)).build()))
                                .width(12).build(),
                        GraphWidget.Builder.create()
                                .title("Aurora DB Connections")
                                .left(List.of(Metric.Builder.create()
                                        .namespace("AWS/RDS").metricName("DatabaseConnections")
                                        .statistic("Average").period(Duration.minutes(5)).build()))
                                .width(12).build()
                ), List.of(
                        GraphWidget.Builder.create()
                                .title("EKS Pod Count")
                                .left(List.of(Metric.Builder.create()
                                        .namespace("ContainerInsights").metricName("pod_number_of_running_pods")
                                        .statistic("Average").period(Duration.minutes(5)).build()))
                                .width(12).build(),
                        GraphWidget.Builder.create()
                                .title("ElastiCache Hit Rate")
                                .left(List.of(Metric.Builder.create()
                                        .namespace("AWS/ElastiCache").metricName("CacheHitRate")
                                        .statistic("Average").period(Duration.minutes(5)).build()))
                                .width(12).build()
                )))
                .build();

        // CloudTrail 审计 - 授权 CloudTrail 访问 S3 和 KMS
        logBucket.addToResourcePolicy(software.amazon.awscdk.services.iam.PolicyStatement.Builder.create()
                .sid("AllowCloudTrailAclCheck")
                .principals(List.of(new software.amazon.awscdk.services.iam.ServicePrincipal("cloudtrail.amazonaws.com")))
                .actions(List.of("s3:GetBucketAcl"))
                .resources(List.of(logBucket.getBucketArn()))
                .build());

        logBucket.addToResourcePolicy(software.amazon.awscdk.services.iam.PolicyStatement.Builder.create()
                .sid("AllowCloudTrailWrite")
                .principals(List.of(new software.amazon.awscdk.services.iam.ServicePrincipal("cloudtrail.amazonaws.com")))
                .actions(List.of("s3:PutObject"))
                .resources(List.of(logBucket.getBucketArn() + "/cloudtrail/*"))
                .conditions(Map.of("StringEquals", Map.of("s3:x-amz-acl", "bucket-owner-full-control")))
                .build());

        kmsKey.grant(new software.amazon.awscdk.services.iam.ServicePrincipal("cloudtrail.amazonaws.com"),
                "kms:GenerateDataKey*", "kms:DescribeKey");

        Trail.Builder.create(this, "AuditTrail")
                .trailName("migration-audit-trail")
                .bucket(logBucket)
                .s3KeyPrefix("cloudtrail")
                .isMultiRegionTrail(true)
                .includeGlobalServiceEvents(true)
                .enableFileValidation(true)
                .encryptionKey(kmsKey)
                .build();

        Tags.of(this).add("Project", "CloudMigration");
        Tags.of(this).add("Layer", "Observability");
        Tags.of(this).add("ManagedBy", "CDK");
    }
}

