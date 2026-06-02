package com.myorg;

import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.Tags;
import software.amazon.awscdk.services.ec2.*;
import software.constructs.Construct;

import java.util.List;

/**
 * Network Foundation Stack
 * - VPC with 2 AZs, Public/Private subnets
 * - NAT Gateway, Internet Gateway
 * - VPC Flow Logs for compliance
 */
public class NetworkStack extends Stack {
    private final Vpc vpc;

    public NetworkStack(final Construct scope, final String id, final StackProps props) {
        super(scope, id, props);

        this.vpc = Vpc.Builder.create(this, "PrimaryVpc")
                .vpcName("migration-primary-vpc")
                .maxAzs(2)
                .natGateways(1)
                .subnetConfiguration(List.of(
                        SubnetConfiguration.builder()
                                .name("Public")
                                .subnetType(SubnetType.PUBLIC)
                                .cidrMask(24)
                                .build(),
                        SubnetConfiguration.builder()
                                .name("Private")
                                .subnetType(SubnetType.PRIVATE_WITH_EGRESS)
                                .cidrMask(22)
                                .build(),
                        SubnetConfiguration.builder()
                                .name("Isolated")
                                .subnetType(SubnetType.PRIVATE_ISOLATED)
                                .cidrMask(24)
                                .build()
                ))
                .flowLogs(java.util.Map.of("FlowLog", FlowLogOptions.builder()
                        .trafficType(FlowLogTrafficType.ALL)
                        .build()))
                .build();

        // VPC Endpoints for private access to AWS services
        vpc.addGatewayEndpoint("S3Endpoint", GatewayVpcEndpointOptions.builder()
                .service(GatewayVpcEndpointAwsService.S3)
                .build());

        vpc.addGatewayEndpoint("DynamoDBEndpoint", GatewayVpcEndpointOptions.builder()
                .service(GatewayVpcEndpointAwsService.DYNAMODB)
                .build());

        vpc.addInterfaceEndpoint("EcrEndpoint", InterfaceVpcEndpointOptions.builder()
                .service(InterfaceVpcEndpointAwsService.ECR)
                .build());

        vpc.addInterfaceEndpoint("EcrDockerEndpoint", InterfaceVpcEndpointOptions.builder()
                .service(InterfaceVpcEndpointAwsService.ECR_DOCKER)
                .build());

        vpc.addInterfaceEndpoint("CloudWatchLogsEndpoint", InterfaceVpcEndpointOptions.builder()
                .service(InterfaceVpcEndpointAwsService.CLOUDWATCH_LOGS)
                .build());

        Tags.of(this).add("Project", "CloudMigration");
        Tags.of(this).add("Layer", "Network");
        Tags.of(this).add("ManagedBy", "CDK");
    }

    public Vpc getVpc() { return vpc; }
}

