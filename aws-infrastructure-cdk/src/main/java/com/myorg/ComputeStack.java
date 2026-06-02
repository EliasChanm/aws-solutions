package com.myorg;

import software.amazon.awscdk.*;
import software.amazon.awscdk.services.ec2.*;
import software.amazon.awscdk.services.eks.*;
import software.amazon.awscdk.services.iam.*;
import software.amazon.awscdk.services.kms.Key;
import software.amazon.awscdk.services.lambda.Code;
import software.amazon.awscdk.services.lambda.Function;
import software.amazon.awscdk.services.lambda.Runtime;
import software.amazon.awscdk.cdk.lambdalayer.kubectl.v33.KubectlV33Layer;
import software.constructs.Construct;

import java.util.List;
import java.util.Map;

/**
 * Compute Stack
 * - Amazon EKS Cluster (K8s 1.33)
 * - EC2 Bastion Host (Jump Server)
 * - Lambda function (automation tasks)
 */
public class ComputeStack extends Stack {
    private final Cluster eksCluster;

    public ComputeStack(final Construct scope, final String id, final StackProps props,
                        final Vpc vpc, final Key kmsKey) {
        super(scope, id, props);

        // EKS 集群
        this.eksCluster = Cluster.Builder.create(this, "EksCluster")
                .clusterName("migration-eks")
                .version(KubernetesVersion.V1_33)
                .kubectlLayer(new KubectlV33Layer(this, "KubectlLayer"))
                .vpc(vpc)
                .vpcSubnets(List.of(SubnetSelection.builder().subnetType(SubnetType.PRIVATE_WITH_EGRESS).build()))
                .defaultCapacity(0)
                .endpointAccess(EndpointAccess.PRIVATE)
                .secretsEncryptionKey(kmsKey)
                .clusterLogging(List.of(ClusterLoggingTypes.API, ClusterLoggingTypes.AUDIT,
                        ClusterLoggingTypes.AUTHENTICATOR, ClusterLoggingTypes.CONTROLLER_MANAGER,
                        ClusterLoggingTypes.SCHEDULER))
                .build();

        // EKS Add-ons
        // vpc-cni
        CfnAddon vpcCni = CfnAddon.Builder.create(this, "VpcCniAddon")
                .addonName("vpc-cni")
                .clusterName(eksCluster.getClusterName())
                .resolveConflicts("OVERWRITE")
                .build();

        // kube-proxy
        CfnAddon kubeProxy = CfnAddon.Builder.create(this, "KubeProxyAddon")
                .addonName("kube-proxy")
                .clusterName(eksCluster.getClusterName())
                .resolveConflicts("OVERWRITE")
                .build();

        // coredns
        CfnAddon coreDns = CfnAddon.Builder.create(this, "CoreDnsAddon")
                .addonName("coredns")
                .clusterName(eksCluster.getClusterName())
                .resolveConflicts("OVERWRITE")
                .build();

        // 托管节点组
        Nodegroup nodegroup = eksCluster.addNodegroupCapacity("WorkerNodes", NodegroupOptions.builder()
                .nodegroupName("migration-workers")
                .amiType(NodegroupAmiType.AL2023_X86_64_STANDARD)
                .instanceTypes(List.of(InstanceType.of(InstanceClass.M6I, InstanceSize.XLARGE)))
                .minSize(2).maxSize(10).desiredSize(3)
                .diskSize(100)
                .subnets(SubnetSelection.builder().subnetType(SubnetType.PRIVATE_WITH_EGRESS).build())
                .build());
        nodegroup.getNode().addDependency(vpcCni);
        nodegroup.getNode().addDependency(kubeProxy);
        nodegroup.getNode().addDependency(coreDns);

        // ebs-csi-driver
        ServiceAccount ebsCsiSa = eksCluster.addServiceAccount("EbsCsiSa", ServiceAccountOptions.builder()
                .name("ebs-csi-controller-sa")
                .namespace("kube-system")
                .build());
        ebsCsiSa.getRole().addManagedPolicy(
                ManagedPolicy.fromAwsManagedPolicyName("service-role/AmazonEBSCSIDriverPolicy"));

        CfnAddon ebsCsi = CfnAddon.Builder.create(this, "EbsCsiAddon")
                .addonName("aws-ebs-csi-driver")
                .clusterName(eksCluster.getClusterName())
                .serviceAccountRoleArn(ebsCsiSa.getRole().getRoleArn())
                .resolveConflicts("OVERWRITE")
                .build();
        ebsCsi.getNode().addDependency(nodegroup);
        ebsCsi.getNode().addDependency(ebsCsiSa);

        // AWS Load Balancer Controller (via Helm chart)
        Role lbcRole = Role.Builder.create(this, "LbcRole")
                .assumedBy(new FederatedPrincipal(
                        eksCluster.getOpenIdConnectProvider().getOpenIdConnectProviderArn(),
                        Map.of(),
                        "sts:AssumeRoleWithWebIdentity"))
                .managedPolicies(List.of(ManagedPolicy.fromAwsManagedPolicyName("ElasticLoadBalancingFullAccess")))
                .build();

        eksCluster.addHelmChart("AwsLoadBalancerController", HelmChartOptions.builder()
                .chart("aws-load-balancer-controller")
                .repository("https://aws.github.io/eks-charts")
                .namespace("kube-system")
                .release("aws-load-balancer-controller")
                .values(Map.of(
                        "clusterName", eksCluster.getClusterName(),
                        "region", this.getRegion(),
                        "vpcId", vpc.getVpcId(),
                        "serviceAccount", Map.of(
                                "create", true,
                                "name", "aws-load-balancer-controller",
                                "annotations", Map.of(
                                        "eks.amazonaws.com/role-arn", lbcRole.getRoleArn()
                                )
                        )
                ))
                .build());

        // 堡垒机
        BastionHostLinux.Builder.create(this, "BastionHost")
                .vpc(vpc)
                .instanceName("migration-bastion")
                .subnetSelection(SubnetSelection.builder().subnetType(SubnetType.PUBLIC).build())
                .build();

        // Lambda 自动化函数
        Function.Builder.create(this, "AutomationLambda")
                .runtime(Runtime.PYTHON_3_12)
                .handler("index.handler")
                .code(Code.fromInline(
                        "import boto3\n" +
                        "def handler(event, context):\n" +
                        "    print('Automation task executed')\n" +
                        "    return {'statusCode': 200}\n"))
                .timeout(Duration.minutes(5))
                .environment(Map.of("PROJECT", "CloudMigration"))
                .build();

        Tags.of(this).add("Project", "CloudMigration");
        Tags.of(this).add("Layer", "Compute");
        Tags.of(this).add("ManagedBy", "CDK");
    }

    public Cluster getEksCluster() { return eksCluster; }
}

