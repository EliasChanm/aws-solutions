#!/bin/bash
# Moltbot + Feishu 安装脚本
# 版本: v4.1.0

set -e

exec > >(tee /var/log/moltbot-setup.log)
exec 2>&1

echo "=========================================="
echo "Moltbot + Feishu Setup: $(date)"
echo "=========================================="

export DEBIAN_FRONTEND=noninteractive

# 从环境变量获取参数
AWS_REGION=${AWS_REGION:-us-east-1}
FEISHU_APP_ID=${FEISHU_APP_ID}
FEISHU_APP_SECRET=${FEISHU_APP_SECRET}
MOLTBOT_MODEL=${MOLTBOT_MODEL:-us.anthropic.claude-opus-4-20250514-v1:0}
WAIT_HANDLE_URL=${WAIT_HANDLE_URL}

# 错误处理
send_failure() {
    local reason="$1"
    echo "FATAL ERROR: $reason"
    
    if [ -n "$WAIT_HANDLE_URL" ]; then
        SIGNAL_JSON="{\"Status\":\"FAILURE\",\"Reason\":\"$reason\",\"UniqueId\":\"moltbot\",\"Data\":\"\"}"
        curl -X PUT -H 'Content-Type:' --data-binary "$SIGNAL_JSON" "$WAIT_HANDLE_URL" 2>/dev/null || true
    fi
    
    exit 1
}

trap 'send_failure "Script failed at line $LINENO"' ERR

# [1/11] 系统更新
echo "[1/11] Updating system..."
apt-get update || send_failure "apt-get update failed"
apt-get upgrade -y
apt-get install -y unzip curl python3-pip jq

# [2/11] 安装 AWS CLI
echo "[2/11] Installing AWS CLI..."
curl --connect-timeout 10 --max-time 300 "https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip" -o "awscliv2.zip"
unzip -q awscliv2.zip
./aws/install || send_failure "AWS CLI installation failed"
rm -rf aws awscliv2.zip

# [3/11] 配置 SSM
echo "[3/11] Configuring SSM Agent..."
snap start amazon-ssm-agent || systemctl start amazon-ssm-agent || true

# [4/11] 安装 Node.js
echo "[4/11] Installing Node.js..."
sudo -u ubuntu bash << 'UBUNTU_SCRIPT'
set -e
cd ~
curl -o- https://raw.githubusercontent.com/nvm-sh/nvm/v0.40.1/install.sh | bash
export NVM_DIR="$HOME/.nvm"
[ -s "$NVM_DIR/nvm.sh" ] && . "$NVM_DIR/nvm.sh" || exit 1
nvm install 22
nvm use 22
nvm alias default 22
npm install -g openclaw@latest --timeout=600000
if ! grep -q 'NVM_DIR' ~/.bashrc; then
    echo 'export NVM_DIR="$HOME/.nvm"' >> ~/.bashrc
    echo '[ -s "$NVM_DIR/nvm.sh" ] && . "$NVM_DIR/nvm.sh"' >> ~/.bashrc
fi
UBUNTU_SCRIPT

# [5/11] 配置 AWS
echo "[5/11] Configuring AWS..."
sudo -u ubuntu aws configure set region "$AWS_REGION"
sudo -u ubuntu aws configure set output json

# [6/11] 配置环境变量
echo "[6/11] Configuring environment variables..."
cat >> /home/ubuntu/.bashrc << EOF
export AWS_REGION=$AWS_REGION
export AWS_DEFAULT_REGION=$AWS_REGION
export MOLTBOT_MODEL=$MOLTBOT_MODEL
EOF

# [7/11] 配置 systemd
echo "[7/11] Configuring systemd..."
loginctl enable-linger ubuntu
systemctl start user@1000.service
sleep 3

# [8/11] 安装 Feishu 插件
echo "[8/11] Installing Feishu plugin..."
sudo -u ubuntu bash << 'FEISHU_INSTALL'
set -e
export HOME=/home/ubuntu
export NVM_DIR="$HOME/.nvm"
[ -s "$NVM_DIR/nvm.sh" ] && . "$NVM_DIR/nvm.sh" || exit 1

cd /tmp
curl -L -o feishu-plugin.tar.gz "https://github.com/m1heng/clawdbot-feishu/archive/refs/heads/main.tar.gz"
tar -xzf feishu-plugin.tar.gz
cd clawdbot-feishu-main

echo '{"name":"@m1heng-clawd/feishu","version":"0.1.2-openclaw","type":"module","description":"OpenClaw Feishu/Lark channel plugin","license":"MIT","files":["index.ts","src","clawdbot.plugin.json"],"repository":{"type":"git","url":"git+https://github.com/m1heng/clawdbot-feishu.git"},"keywords":["openclaw","clawdbot","feishu","lark","飞书","chatbot","ai","claude"],"openclaw":{"extensions":["./index.ts"],"channel":{"id":"feishu","label":"Feishu","selectionLabel":"Feishu/Lark (飞书)","docsPath":"/channels/feishu","docsLabel":"feishu","blurb":"飞书/Lark enterprise messaging.","aliases":["lark"],"order":70}},"dependencies":{"@larksuiteoapi/node-sdk":"^1.30.0","zod":"^4.3.6"},"peerDependencies":{"openclaw":">=2026.1.24"}}' > package.json

npm install --production
mkdir -p ~/.openclaw/plugins/feishu
cp -r * ~/.openclaw/plugins/feishu/
echo '{"name":"@m1heng-clawd/feishu","version":"0.1.2-openclaw","type":"channel","channelId":"feishu"}' > ~/.openclaw/plugins/feishu/.openclaw-plugin
FEISHU_INSTALL

# [9/11] 配置 Moltbot
echo "[9/11] Configuring Moltbot..."
sudo -u ubuntu mkdir -p /home/ubuntu/.openclaw
GATEWAY_TOKEN=$(openssl rand -hex 24)

sudo -u ubuntu cat > /home/ubuntu/.openclaw/openclaw.json << JSONEOF
{"gateway":{"mode":"local","port":18789,"bind":"loopback","controlUi":{"enabled":true,"allowInsecureAuth":true},"auth":{"mode":"token","token":"$GATEWAY_TOKEN"}},"models":{"bedrockDiscovery":{"enabled":true,"region":"$AWS_REGION","providerFilter":["anthropic","amazon"],"refreshInterval":3600,"defaultContextWindow":200000,"defaultMaxTokens":8192},"providers":{"amazon-bedrock":{"baseUrl":"https://bedrock-runtime.$AWS_REGION.amazonaws.com","api":"bedrock-converse-stream","auth":"aws-sdk","models":[{"id":"$MOLTBOT_MODEL","name":"Primary Model","input":["text","image"],"contextWindow":200000,"maxTokens":8192}]}}},"agents":{"defaults":{"model":{"primary":"amazon-bedrock/$MOLTBOT_MODEL"}}}}
JSONEOF

# [10/11] 安装 daemon
echo "[10/11] Installing OpenClaw daemon..."
sudo -H -u ubuntu XDG_RUNTIME_DIR=/run/user/1000 bash -c '
export HOME=/home/ubuntu
export NVM_DIR="$HOME/.nvm"
[ -s "$NVM_DIR/nvm.sh" ] && . "$NVM_DIR/nvm.sh"
openclaw daemon install
'

sleep 10

# [10.5/11] 添加 Feishu 配置
echo "[10.5/11] Adding Feishu configuration..."
sudo -u ubuntu bash << ADD_FEISHU
export HOME=/home/ubuntu
export NVM_DIR="$HOME/.nvm"
[ -s "\$NVM_DIR/nvm.sh" ] && . "\$NVM_DIR/nvm.sh"
XDG_RUNTIME_DIR=/run/user/1000 systemctl --user stop openclaw-gateway.service || true
sleep 3
jq '.channels.feishu = {"enabled":true,"appId":"$FEISHU_APP_ID","appSecret":"$FEISHU_APP_SECRET","domain":"feishu","connectionMode":"websocket","dmPolicy":"open","allowFrom":["*"],"groupPolicy":"open","requireMention":true,"mediaMaxMb":30,"renderMode":"auto"}' ~/.openclaw/openclaw.json > ~/.openclaw/openclaw.json.tmp
mv ~/.openclaw/openclaw.json.tmp ~/.openclaw/openclaw.json
XDG_RUNTIME_DIR=/run/user/1000 systemctl --user start openclaw-gateway.service
ADD_FEISHU

sleep 5

# 保存凭证
echo "$GATEWAY_TOKEN" > /home/ubuntu/.openclaw/gateway_token.txt
chown ubuntu:ubuntu /home/ubuntu/.openclaw/*.txt

# [11/11] 完成
echo "[11/11] Complete!"
echo "SUCCESS" > /home/ubuntu/.openclaw/setup_status.txt

# 发送成功信号
if [ -n "$WAIT_HANDLE_URL" ]; then
    COMPLETE_URL="http://localhost:18789/?token=$GATEWAY_TOKEN"
    SIGNAL_JSON="{\"Status\":\"SUCCESS\",\"Reason\":\"Moltbot ready\",\"UniqueId\":\"moltbot\",\"Data\":\"$COMPLETE_URL\"}"
    curl -X PUT -H 'Content-Type:' --data-binary "$SIGNAL_JSON" "$WAIT_HANDLE_URL"
fi

echo "Moltbot + Feishu 安装完成！"

