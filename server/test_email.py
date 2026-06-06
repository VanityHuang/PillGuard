#!/usr/bin/env python3
"""
PillGuard 邮件发送测试脚本
用法: python3 test_email.py [--to test@example.com]
用于测试 SMTP 配置是否正确，验证邮件发送功能是否正常
"""

import os
import sys
import smtplib
import subprocess
from email.mime.multipart import MIMEMultipart
from email.mime.text import MIMEText
from datetime import datetime


def get_service_env():
    """从 systemd 服务中读取环境变量（用于 cron 环境下运行时兜底）"""
    try:
        result = subprocess.run(
            ['systemctl', 'show', 'pillguard', '--property=Environment'],
            capture_output=True, text=True
        )
        env_line = result.stdout.strip()
        if env_line.startswith('Environment='):
            env_str = env_line[len('Environment='):]
            for item in env_str.split():
                if '=' in item:
                    key, value = item.split('=', 1)
                    os.environ.setdefault(key, value)
    except Exception as e:
        print(f"[WARN] 无法从 systemd 读取环境变量: {e}")


# 优先使用当前环境变量，cron 环境下自动从 systemd 服务兜底
get_service_env()

# SMTP 配置
SMTP_HOST = os.environ.get('SMTP_HOST', 'smtp.example.com')
SMTP_PORT = int(os.environ.get('SMTP_PORT', '587'))
SMTP_USER = os.environ.get('SMTP_USER', 'pillguard@example.com')
SMTP_PASSWORD = os.environ.get('SMTP_PASSWORD', '')
SMTP_USE_TLS = os.environ.get('SMTP_USE_TLS', 'true').lower() == 'true'
SMTP_USE_SSL = os.environ.get('SMTP_USE_SSL', 'false').lower() == 'true'
ADMIN_EMAIL = os.environ.get('ADMIN_EMAIL', 'admin@example.com')

# 支持命令行指定收件人
import argparse
parser = argparse.ArgumentParser(description='PillGuard 邮件发送测试')
parser.add_argument('--to', default=ADMIN_EMAIL, help='收件人邮箱地址')
args = parser.parse_args()
TO_EMAIL = args.to


def main():
    print("=" * 60)
    print("PillGuard 邮件发送测试")
    print("=" * 60)
    print(f"时间: {datetime.now()}")
    print(f"SMTP 服务器: {SMTP_HOST}:{SMTP_PORT}")
    print(f"SMTP 用户: {SMTP_USER}")
    print(f"SSL 模式: {SMTP_USE_SSL}")
    print(f"TLS 模式: {SMTP_USE_TLS}")
    print(f"收件人: {TO_EMAIL}")
    print()

    # 检查配置
    if SMTP_PASSWORD == '':
        print("❌ 错误: SMTP_PASSWORD 未设置")
        sys.exit(1)
    if SMTP_HOST == 'smtp.example.com':
        print("❌ 错误: SMTP_HOST 仍是默认值 smtp.example.com")
        sys.exit(1)

    print("[1/3] 检查 SMTP 配置... ✅")

    # 构建测试邮件
    print("[2/3] 构建测试邮件...")
    msg = MIMEMultipart()
    msg['Subject'] = f'[PillGuard 测试] 邮件发送测试 - {datetime.now().strftime("%Y-%m-%d %H:%M:%S")}'
    msg['From'] = f'PillGuard <{SMTP_USER}>'
    msg['To'] = TO_EMAIL

    html = f"""
    <html>
    <body style="font-family: Arial, sans-serif;">
        <h2 style="color: #1976D2;">PillGuard 邮件发送测试</h2>
        <p>这是一封测试邮件，用于验证 SMTP 配置是否正确。</p>
        <hr>
        <table>
            <tr><td><b>发送时间</b></td><td>{datetime.now().strftime("%Y-%m-%d %H:%M:%S")}</td></tr>
            <tr><td><b>SMTP 服务器</b></td><td>{SMTP_HOST}:{SMTP_PORT}</td></tr>
            <tr><td><b>发送账号</b></td><td>{SMTP_USER}</td></tr>
        </table>
        <hr>
        <p style="color: #4CAF50;">如果您收到此邮件，说明 SMTP 配置正确，邮件功能正常！</p>
    </body>
    </html>
    """
    msg.attach(MIMEText(html, 'html', 'utf-8'))
    print("[2/3] 构建测试邮件... ✅")

    # 发送邮件
    print("[3/3] 发送邮件...")
    try:
        if SMTP_USE_SSL:
            print(f"  使用 SMTP_SSL 连接到 {SMTP_HOST}:{SMTP_PORT}")
            server = smtplib.SMTP_SSL(SMTP_HOST, SMTP_PORT, timeout=30)
        elif SMTP_USE_TLS:
            print(f"  使用 SMTP+TLS 连接到 {SMTP_HOST}:{SMTP_PORT}")
            server = smtplib.SMTP(SMTP_HOST, SMTP_PORT, timeout=30)
            server.starttls()
        else:
            print(f"  使用 SMTP 连接到 {SMTP_HOST}:{SMTP_PORT}")
            server = smtplib.SMTP(SMTP_HOST, SMTP_PORT, timeout=30)

        print(f"  登录中 ({SMTP_USER})...")
        server.login(SMTP_USER, SMTP_PASSWORD)
        print(f"  发送到 {TO_EMAIL}...")
        server.sendmail(SMTP_USER, TO_EMAIL, msg.as_string())
        server.quit()
        print("[3/3] 发送邮件... ✅")
        print()
        print("=" * 60)
        print("✅ 邮件发送成功！请检查收件箱。")
        print("=" * 60)
    except smtplib.SMTPAuthenticationError as e:
        print(f"❌ SMTP 认证失败: {e}")
        print("   请检查 SMTP_USER 和 SMTP_PASSWORD 是否正确")
        print("   如果是 QQ 邮箱，授权码可能已过期，需要重新生成")
        sys.exit(1)
    except smtplib.SMTPConnectError as e:
        print(f"❌ SMTP 连接失败: {e}")
        print(f"   请检查 SMTP_HOST={SMTP_HOST} 和 SMTP_PORT={SMTP_PORT} 是否正确")
        print("   防火墙可能阻止了连接")
        sys.exit(1)
    except Exception as e:
        print(f"❌ 邮件发送失败: {type(e).__name__}: {e}")
        sys.exit(1)


if __name__ == '__main__':
    main()
