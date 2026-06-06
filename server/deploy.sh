#!/bin/bash
# PillGuard 服务器部署脚本
# 适用于 Ubuntu/Debian Linux

# ====== 配置 ======
SERVER_IP="47.106.163.25"
SMTP_HOST="smtp.qq.com"
SMTP_PORT="465"
SMTP_USER="467029499@qq.com"
SMTP_PASSWORD="xymkklwtyiwwbjeb"
ADMIN_EMAIL="467029499@qq.com"
# ====== 配置结束 ======

echo "=== PillGuard 服务器部署 ==="

# 1. 安装系统依赖
echo "[1/6] 安装系统依赖..."
sudo apt-get update -qq
sudo apt-get install -y python3 python3-pip python3-venv nginx

# 2. 创建目录
echo "[2/6] 创建应用目录..."
sudo mkdir -p /opt/pillguard/server
sudo mkdir -p /var/pillguard/uploads
sudo mkdir -p /var/pillguard/logs
sudo chown -R $USER:$USER /var/pillguard

# 3. 复制应用文件（跳过已存在的）
echo "[3/6] 复制应用文件..."
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
if [ "$SCRIPT_DIR" != "/opt/pillguard/server" ]; then
    cp -r "$SCRIPT_DIR/"* /opt/pillguard/server/
fi

# 4. 创建虚拟环境并安装依赖
echo "[4/6] 设置Python环境..."
python3 -m venv /var/pillguard/venv
/var/pillguard/venv/bin/pip install --upgrade pip -q
/var/pillguard/venv/bin/pip install -r /opt/pillguard/server/requirements.txt -q

# 5. 初始化数据库
echo "[5/6] 初始化数据库..."
cd /opt/pillguard/server
/var/pillguard/venv/bin/python3 -c "from app import init_db; init_db()"

# 6. 生成密钥并配置服务
JWT_SECRET=$(openssl rand -hex 32)
PASSWORD_SALT=$(openssl rand -hex 16)

echo "[6/6] 配置系统服务..."
sudo tee /etc/systemd/system/pillguard.service > /dev/null << EOF
[Unit]
Description=PillGuard API Server
After=network.target

[Service]
Type=simple
User=www-data
Group=www-data
WorkingDirectory=/opt/pillguard/server
Environment="PATH=/var/pillguard/venv/bin"
Environment="JWT_SECRET_KEY=${JWT_SECRET}"
Environment="PASSWORD_SALT=${PASSWORD_SALT}"
Environment="DATABASE=/var/pillguard/pillguard.db"
Environment="UPLOAD_FOLDER=/var/pillguard/uploads"
Environment="SMTP_HOST=${SMTP_HOST}"
Environment="SMTP_PORT=${SMTP_PORT}"
Environment="SMTP_USER=${SMTP_USER}"
Environment="SMTP_PASSWORD=${SMTP_PASSWORD}"
Environment="SMTP_USE_SSL=true"
Environment="ADMIN_EMAIL=${ADMIN_EMAIL}"
ExecStart=/var/pillguard/venv/bin/gunicorn -w 4 -b 127.0.0.1:5000 app:app
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
EOF

sudo systemctl daemon-reload
sudo systemctl enable pillguard
sudo systemctl restart pillguard

# 配置每日邮件cron（使用 root crontab，因为 daily_email.py 需要通过 systemctl 读取环境变量）
sudo crontab -l 2>/dev/null | grep -v daily_email | sudo crontab -
(sudo crontab -l 2>/dev/null; echo "0 22 * * * /var/pillguard/venv/bin/python3 /opt/pillguard/server/daily_email.py >> /var/pillguard/logs/daily_email.log 2>&1") | sudo crontab -

# 配置Nginx
sudo tee /etc/nginx/sites-available/pillguard > /dev/null << EOF
server {
    listen 80;
    server_name ${SERVER_IP};

    client_max_body_size 16M;

    location / {
        proxy_pass http://127.0.0.1:5000;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;
    }
}
EOF

sudo ln -sf /etc/nginx/sites-available/pillguard /etc/nginx/sites-enabled/
sudo rm -f /etc/nginx/sites-enabled/default
sudo nginx -t && sudo systemctl reload nginx

# 设置权限
sudo chown -R www-data:www-data /var/pillguard

echo ""
echo "============================================"
echo "=== 部署完成 ==="
echo "============================================"
echo ""
echo "服务地址: http://${SERVER_IP}"
echo "API健康检查: http://${SERVER_IP}/api/health"
echo ""
echo "邮件测试: sudo /var/pillguard/venv/bin/python3 /opt/pillguard/server/test_email.py"
echo ""
echo "接下来请执行："
echo "  python3 /opt/pillguard/server/create_user.py"
