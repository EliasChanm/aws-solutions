package com.myorg;

import software.amazon.awscdk.App;
import software.amazon.awscdk.Environment;
import software.amazon.awscdk.StackProps;

public class AwsInfrastructureCdkApp {
    public static void main(final String[] args) {
        App app = new App();

        String account = (String) app.getNode().tryGetContext("account");
        String primaryRegion = (String) app.getNode().tryGetContext("primaryRegion");
        String drRegion = (String) app.getNode().tryGetContext("drRegion");

        if (account == null) account = System.getenv("CDK_DEFAULT_ACCOUNT");
        if (primaryRegion == null) primaryRegion = "ap-southeast-1";
        if (drRegion == null) drRegion = "ap-northeast-1";

        Environment primaryEnv = Environment.builder().account(account).region(primaryRegion).build();
        Environment drEnv = Environment.builder().account(account).region(drRegion).build();

        // Stack 1: Network Foundation
        NetworkStack networkStack = new NetworkStack(app, "NetworkStack",
                StackProps.builder().env(primaryEnv).description("VPC, Subnets, NAT, IGW - Network Foundation").build());

        // Stack 2: Security - IAM, KMS, WAF
        SecurityStack securityStack = new SecurityStack(app, "SecurityStack",
                StackProps.builder().env(primaryEnv).description("IAM Roles, KMS Keys, WAF, Security Groups").build(),
                networkStack.getVpc());

        // Stack 3: Data Layer - Aurora, ElastiCache, S3, OpenSearch
        DataStack dataStack = new DataStack(app, "DataStack",
                StackProps.builder().env(primaryEnv).description("Aurora, ElastiCache, S3, OpenSearch").build(),
                networkStack.getVpc(), securityStack.getKmsKey());

        // Stack 4: Compute - EKS, EC2, EMR, Lambda
        ComputeStack computeStack = new ComputeStack(app, "ComputeStack",
                StackProps.builder().env(primaryEnv).description("EKS, EC2 Bastion, EMR, Lambda").build(),
                networkStack.getVpc(), securityStack.getKmsKey());

        // Stack 5: Edge & CDN - CloudFront, Global Accelerator, Route53, ACM
        EdgeStack edgeStack = new EdgeStack(app, "EdgeStack",
                StackProps.builder().env(primaryEnv).description("CloudFront, Global Accelerator, Route53, ACM").build());

        // Stack 6: Observability - CloudWatch, CloudTrail, SNS Alarms
        ObservabilityStack observabilityStack = new ObservabilityStack(app, "ObservabilityStack",
                StackProps.builder().env(primaryEnv).description("CloudWatch Dashboards, Alarms, CloudTrail, Log Groups").build(),
                dataStack.getLogBucket(), securityStack.getKmsKey());

        // Stack 7: DR Region - Aurora Replica, ECR, S3 Replication
        DrStack drStack = new DrStack(app, "DrStack",
                StackProps.builder().env(drEnv).description("DR Region - Aurora Global DB Secondary, ECR, S3 Backup").build());

        // Dependencies
        securityStack.addDependency(networkStack);
        dataStack.addDependency(securityStack);
        computeStack.addDependency(securityStack);
        observabilityStack.addDependency(dataStack);

        app.synth();
    }
}
