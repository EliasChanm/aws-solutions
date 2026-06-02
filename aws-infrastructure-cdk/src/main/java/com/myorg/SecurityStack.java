package com.myorg;

import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.Tags;
import software.amazon.awscdk.services.ec2.*;
import software.amazon.awscdk.services.iam.*;
import software.amazon.awscdk.services.kms.*;
import software.amazon.awscdk.services.wafv2.*;
import software.constructs.Construct;

import java.util.List;

/**
 * Security Stack
 * - KMS encryption key
 * - IAM roles (EKS admin, read-only, CI/CD)
 * - WAF Web ACL with managed rules
 * - Security Groups
 */
public class SecurityStack extends Stack {
    private final Key kmsKey;
    private final CfnWebACL wafWebAcl;
    private final SecurityGroup albSg;
    private final SecurityGroup appSg;
    private final SecurityGroup dbSg;

    public SecurityStack(final Construct scope, final String id, final StackProps props, final Vpc vpc) {
        super(scope, id, props);

        // KMS 密钥
        this.kmsKey = Key.Builder.create(this, "MasterKey")
                .alias("migration-master-key")
                .enableKeyRotation(true)
                .description("Master encryption key for all data at rest")
                .build();

        // 允许 CloudWatch Logs 使用 KMS 密钥
        kmsKey.addToResourcePolicy(PolicyStatement.Builder.create()
                .sid("AllowCloudWatchLogs")
                .principals(List.of(new ServicePrincipal("logs." + this.getRegion() + ".amazonaws.com")))
                .actions(List.of("kms:Encrypt*", "kms:Decrypt*", "kms:ReEncrypt*",
                        "kms:GenerateDataKey*", "kms:Describe*"))
                .resources(List.of("*"))
                .build());

        // IAM 角色， EKS Admin，最小权限
        Role.Builder.create(this, "EksAdminRole")
                .roleName("migration-eks-admin")
                .assumedBy(new AccountRootPrincipal())
                .build();

        // IAM 角色，审计员的 Read Only 权限
        Role.Builder.create(this, "ReadOnlyRole")
                .roleName("migration-readonly")
                .assumedBy(new AccountRootPrincipal())
                .managedPolicies(List.of(ManagedPolicy.fromAwsManagedPolicyName("ReadOnlyAccess")))
                .build();

        // IAM 角色，用于 CI/CD Pipeline
        Role cicdRole = Role.Builder.create(this, "CicdRole")
                .roleName("migration-cicd-pipeline")
                .assumedBy(new ServicePrincipal("codebuild.amazonaws.com"))
                .build();
        cicdRole.addToPolicy(PolicyStatement.Builder.create()
                .actions(List.of("ecr:GetAuthorizationToken", "ecr:BatchCheckLayerAvailability",
                        "ecr:PutImage", "ecr:InitiateLayerUpload", "ecr:UploadLayerPart",
                        "ecr:CompleteLayerUpload", "eks:DescribeCluster"))
                .resources(List.of("*"))
                .build());

        // 安全组
        this.albSg = SecurityGroup.Builder.create(this, "AlbSg")
                .vpc(vpc).securityGroupName("migration-alb-sg")
                .description("ALB Security Group - allows HTTP/HTTPS inbound")
                .build();
        albSg.addIngressRule(Peer.anyIpv4(), Port.tcp(443), "HTTPS");
        albSg.addIngressRule(Peer.anyIpv4(), Port.tcp(80), "HTTP");

        this.appSg = SecurityGroup.Builder.create(this, "AppSg")
                .vpc(vpc).securityGroupName("migration-app-sg")
                .description("Application Security Group")
                .build();
        appSg.addIngressRule(albSg, Port.tcpRange(8000, 9000), "From ALB");

        this.dbSg = SecurityGroup.Builder.create(this, "DbSg")
                .vpc(vpc).securityGroupName("migration-db-sg")
                .description("Database Security Group")
                .build();
        dbSg.addIngressRule(appSg, Port.tcp(3306), "MySQL from App");
        dbSg.addIngressRule(appSg, Port.tcp(6379), "Redis from App");

        // WAF Web ACL，保护托管规则
        this.wafWebAcl = CfnWebACL.Builder.create(this, "WafWebAcl")
                .name("migration-waf-acl")
                .scope("REGIONAL")
                .defaultAction(CfnWebACL.DefaultActionProperty.builder()
                        .allow(CfnWebACL.AllowActionProperty.builder().build())
                        .build())
                .visibilityConfig(CfnWebACL.VisibilityConfigProperty.builder()
                        .cloudWatchMetricsEnabled(true)
                        .metricName("migration-waf")
                        .sampledRequestsEnabled(true)
                        .build())
                .rules(List.of(
                        createManagedRule("AWSManagedRulesCommonRuleSet", 1, "CommonRuleSet"),
                        createManagedRule("AWSManagedRulesSQLiRuleSet", 2, "SQLiRuleSet"),
                        createManagedRule("AWSManagedRulesKnownBadInputsRuleSet", 3, "KnownBadInputs")
                ))
                .build();

        Tags.of(this).add("Project", "CloudMigration");
        Tags.of(this).add("Layer", "Security");
        Tags.of(this).add("ManagedBy", "CDK");
    }

    private CfnWebACL.RuleProperty createManagedRule(String name, int priority, String metricName) {
        return CfnWebACL.RuleProperty.builder()
                .name(name)
                .priority(priority)
                .overrideAction(CfnWebACL.OverrideActionProperty.builder()
                        .none(java.util.Collections.emptyMap()).build())
                .statement(CfnWebACL.StatementProperty.builder()
                        .managedRuleGroupStatement(CfnWebACL.ManagedRuleGroupStatementProperty.builder()
                                .vendorName("AWS").name(name).build())
                        .build())
                .visibilityConfig(CfnWebACL.VisibilityConfigProperty.builder()
                        .cloudWatchMetricsEnabled(true).metricName(metricName).sampledRequestsEnabled(true).build())
                .build();
    }

    public Key getKmsKey() { return kmsKey; }
    public CfnWebACL getWafWebAcl() { return wafWebAcl; }
    public SecurityGroup getAlbSg() { return albSg; }
    public SecurityGroup getAppSg() { return appSg; }
    public SecurityGroup getDbSg() { return dbSg; }
}

