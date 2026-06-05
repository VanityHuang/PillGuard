#!/usr/bin/env python3
"""
每日邮件发送脚本
配置cron任务在每日22:00执行: 0 22 * * * /path/to/daily_email.py
"""

import os
import sys
import smtplib
import sqlite3
from email.mime.multipart import MIMEMultipart
from email.mime.text import MIMEText
from email.mime.image import MIMEImage
from datetime import datetime, timedelta
from pathlib import Path

# 配置
SMTP_HOST = os.environ.get('SMTP_HOST', 'smtp.example.com')
SMTP_PORT = int(os.environ.get('SMTP_PORT', '587'))
SMTP_USER = os.environ.get('SMTP_USER', 'pillguard@example.com')
SMTP_PASSWORD = os.environ.get('SMTP_PASSWORD', '')
SMTP_USE_TLS = os.environ.get('SMTP_USE_TLS', 'true').lower() == 'true'
SMTP_USE_SSL = os.environ.get('SMTP_USE_SSL', 'false').lower() == 'true'

DATABASE = os.environ.get('DATABASE', '/var/pillguard/pillguard.db')
UPLOAD_FOLDER = os.environ.get('UPLOAD_FOLDER', '/var/pillguard/uploads')
ADMIN_EMAIL = os.environ.get('ADMIN_EMAIL', 'admin@example.com')

SENDER_NAME = 'PillGuard 用药提醒系统'


def get_db():
    conn = sqlite3.connect(DATABASE)
    conn.row_factory = sqlite3.Row
    return conn


def get_today_records():
    """获取今日所有服药记录"""
    today = datetime.now().strftime('%Y-%m-%d')
    conn = get_db()
    records = conn.execute('''
        SELECT mr.*, u.username, u.email
        FROM medication_records mr
        JOIN users u ON mr.user_id = u.id
        WHERE mr.date = ?
        ORDER BY u.username, mr.time_slot
    ''', (today,)).fetchall()
    conn.close()
    return records


def build_email_html(records):
    """构建邮件HTML内容"""
    today = datetime.now().strftime('%Y年%m月%d日')

    # 按用户分组
    users = {}
    for r in records:
        uid = r['user_id']
        if uid not in users:
            users[uid] = {
                'username': r['username'],
                'email': r['email'],
                'records': []
            }
        users[uid]['records'].append(r)

    html = f"""
    <html>
    <head>
        <style>
            body {{ font-family: Arial, sans-serif; color: #333; }}
            .container {{ max-width: 600px; margin: 0 auto; padding: 20px; }}
            .header {{ background: #1976D2; color: white; padding: 20px; border-radius: 8px 8px 0 0; }}
            .content {{ background: #f5f5f5; padding: 20px; border-radius: 0 0 8px 8px; }}
            .user-section {{ background: white; margin: 10px 0; padding: 15px; border-radius: 8px; box-shadow: 0 1px 3px rgba(0,0,0,0.1); }}
            .record {{ display: flex; justify-content: space-between; padding: 8px 0; border-bottom: 1px solid #eee; }}
            .completed {{ color: #4CAF50; font-weight: bold; }}
            .missed {{ color: #F44336; font-weight: bold; }}
            .photo-link {{ color: #1976D2; }}
        </style>
    </head>
    <body>
        <div class="container">
            <div class="header">
                <h2>PillGuard 每日服药报告</h2>
                <p>{today}</p>
            </div>
            <div class="content">
    """

    for uid, user_data in users.items():
        html += f"""
                <div class="user-section">
                    <h3>{user_data['username']}</h3>
        """
        for r in user_data['records']:
            slot_label = "早上" if r['time_slot'] == 'morning' else "晚上"
            status_class = "completed" if r['completed'] else "missed"
            status_text = "已完成" if r['completed'] else "未完成"

            html += f"""
                    <div class="record">
                        <span>{slot_label} 服药</span>
                        <span class="{status_class}">{status_text}</span>
                    </div>
            """

            if r['completed'] and r['photo_path']:
                html += f"""
                    <div class="record">
                        <span>服药照片</span>
                        <span class="photo-link">见附件</span>
                    </div>
                """

        html += "</div>"

    html += """
            </div>
        </div>
    </body>
    </html>
    """

    return html


def send_email(records):
    """发送每日汇总邮件"""
    if not records:
        print(f"[{datetime.now()}] 今日无记录，跳过邮件发送")
        return

    msg = MIMEMultipart('related')
    msg['Subject'] = f'PillGuard 每日服药报告 - {datetime.now().strftime("%Y-%m-%d")}'
    msg['From'] = f'{SENDER_NAME} <{SMTP_USER}>'
    msg['To'] = ADMIN_EMAIL

    html_content = build_email_html(records)
    msg.attach(MIMEText(html_content, 'html', 'utf-8'))

    # 附加照片
    for r in records:
        if r['completed'] and r['photo_path']:
            photo_path = os.path.join(UPLOAD_FOLDER, r['photo_path'])
            if os.path.exists(photo_path):
                with open(photo_path, 'rb') as f:
                    img = MIMEImage(f.read())
                    slot_label = "早上" if r['time_slot'] == 'morning' else "晚上"
                    img.add_header(
                        'Content-ID',
                        f'<{r["user_id"]}_{r["time_slot"]}>'
                    )
                    img.add_header(
                        'Content-Disposition',
                        'attachment',
                        filename=f'{r["username"]}_{slot_label}_{r["date"]}.jpg'
                    )
                    msg.attach(img)

    # 发送邮件
    try:
        if SMTP_USE_SSL:
            server = smtplib.SMTP_SSL(SMTP_HOST, SMTP_PORT)
        elif SMTP_USE_TLS:
            server = smtplib.SMTP(SMTP_HOST, SMTP_PORT)
            server.starttls()
        else:
            server = smtplib.SMTP(SMTP_HOST, SMTP_PORT)

        server.login(SMTP_USER, SMTP_PASSWORD)
        server.sendmail(SMTP_USER, ADMIN_EMAIL, msg.as_string())
        server.quit()
        print(f"[{datetime.now()}] 邮件发送成功")
    except Exception as e:
        print(f"[{datetime.now()}] 邮件发送失败: {e}")
        sys.exit(1)


def cleanup_photos(records):
    """清理已发送的照片文件"""
    for r in records:
        if r['photo_path']:
            photo_path = os.path.join(UPLOAD_FOLDER, r['photo_path'])
            if os.path.exists(photo_path):
                try:
                    os.remove(photo_path)
                    print(f"[{datetime.now()}] 已删除照片: {r['photo_path']}")
                except OSError as e:
                    print(f"[{datetime.now()}] 删除照片失败: {r['photo_path']}, {e}")


def main():
    print(f"[{datetime.now()}] 开始执行每日邮件任务")

    records = get_today_records()
    print(f"[{datetime.now()}] 获取到 {len(records)} 条记录")

    send_email(records)
    cleanup_photos(records)

    print(f"[{datetime.now()}] 每日邮件任务完成")


if __name__ == '__main__':
    main()
