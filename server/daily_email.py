#!/usr/bin/env python3
"""
每日邮件发送脚本
配置cron任务在每日22:00执行: 0 22 * * * /path/to/daily_email.py

设计：
- 查询所有 emailed_at IS NULL 的记录（即从未通过邮件发送过的记录）
- 按用户分别发送邮件，附带照片附件
- 发送成功后标记 emailed_at 并删除照片文件
- 即使记录跨多天（如昨天+今天），也会在同一天全部发出
"""

import os
import sys
import smtplib
import sqlite3
import subprocess
from email.mime.multipart import MIMEMultipart
from email.mime.text import MIMEText
from email.mime.image import MIMEImage
from email.utils import formataddr
from datetime import datetime, timedelta


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
    except Exception:
        pass


# 优先使用当前环境变量，cron 环境下自动从 systemd 服务兜底
get_service_env()

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
    # 自动迁移：确保 emailed_at 列存在
    cols = [r[1] for r in conn.execute('PRAGMA table_info(medication_records)')]
    if 'emailed_at' not in cols:
        conn.execute('ALTER TABLE medication_records ADD COLUMN emailed_at TIMESTAMP DEFAULT NULL')
        conn.commit()
        print(f"[{datetime.now()}] 已添加 emailed_at 列")
    return conn


def get_unemailed_records():
    """获取所有未通过邮件发送的记录，按用户分组"""
    conn = get_db()
    records = conn.execute('''
        SELECT mr.*, u.username, u.email
        FROM medication_records mr
        JOIN users u ON mr.user_id = u.id
        WHERE mr.emailed_at IS NULL AND mr.completed = 1
        ORDER BY u.username, mr.date, mr.time_slot
    ''').fetchall()
    conn.close()

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
    return users


def photo_exists(photo_path):
    """检查照片文件是否实际存在于磁盘"""
    if not photo_path:
        return False
    full_path = os.path.join(UPLOAD_FOLDER, photo_path)
    return os.path.isfile(full_path)


def build_email_html(username, records):
    """为单个用户构建邮件HTML内容"""
    # 按日期分组
    days = {}
    for r in records:
        date_key = r['date']
        if date_key not in days:
            days[date_key] = []
        days[date_key].append(r)

    # 日期范围
    dates = sorted(days.keys())
    date_range = f"{dates[0]} ~ {dates[-1]}" if len(dates) > 1 else dates[0]

    html = f"""
    <html>
    <head>
        <style>
            body {{ font-family: Arial, sans-serif; color: #333; }}
            .container {{ max-width: 600px; margin: 0 auto; padding: 20px; }}
            .header {{ background: #1976D2; color: white; padding: 20px; border-radius: 8px 8px 0 0; }}
            .content {{ background: #f5f5f5; padding: 20px; border-radius: 0 0 8px 8px; }}
            .day-section {{ background: white; margin: 10px 0; padding: 15px; border-radius: 8px; box-shadow: 0 1px 3px rgba(0,0,0,0.1); }}
            .record {{ display: flex; justify-content: space-between; padding: 8px 0; border-bottom: 1px solid #eee; }}
            .completed {{ color: #4CAF50; font-weight: bold; }}
            .missed {{ color: #F44336; font-weight: bold; }}
            .photo-link {{ color: #1976D2; }}
            .no-photo {{ color: #999; font-style: italic; }}
        </style>
    </head>
    <body>
        <div class="container">
            <div class="header">
                <h2>PillGuard 每日服药报告</h2>
                <p>{username}</p>
                <p style="font-size: 12px; opacity: 0.8;">记录时段: {date_range}</p>
            </div>
            <div class="content">
    """

    for date_key in sorted(days.keys()):
        date_records = days[date_key]
        html += f"""
                <div class="day-section">
                    <h4>{date_key}</h4>
        """
        for r in date_records:
            slot_label = "早上" if r['time_slot'] == 'morning' else "晚上"
            status_class = "completed" if r['completed'] else "missed"
            status_text = "✅ 已完成" if r['completed'] else "❌ 未完成"
            taken_time = r['taken_at'][:19] if r['taken_at'] else ""

            html += f"""
                    <div class="record">
                        <span>{slot_label} 服药</span>
                        <span class="{status_class}">{status_text}</span>
                    </div>
            """

            if taken_time:
                html += f"""
                    <div class="record">
                        <span style="font-size: 12px; color: #999;">打卡时间</span>
                        <span style="font-size: 12px; color: #999;">{taken_time}</span>
                    </div>
                """

            if r['completed'] and r['photo_path']:
                if photo_exists(r['photo_path']):
                    html += """
                    <div class="record">
                        <span>📷 服药照片</span>
                        <span class="photo-link">见附件</span>
                    </div>
                    """
                else:
                    html += """
                    <div class="record">
                        <span>📷 服药照片</span>
                        <span class="no-photo">照片已过期</span>
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


def send_user_email(username, user_email, records):
    """向单个用户发送其专属的邮件"""
    if not records:
        print(f"[{datetime.now()}] 用户 {username}: 无新记录，跳过")
        return True

    msg = MIMEMultipart('related')
    msg['Subject'] = f'PillGuard 服药报告 - {username} - {datetime.now().strftime("%Y-%m-%d")}'
    msg['From'] = formataddr((SENDER_NAME, SMTP_USER))
    msg['To'] = user_email

    html_content = build_email_html(username, records)
    msg.attach(MIMEText(html_content, 'html', 'utf-8'))

    # 附加照片（只附加实际存在的文件）
    photo_count = 0
    for r in records:
        if r['completed'] and r['photo_path']:
            full_path = os.path.join(UPLOAD_FOLDER, r['photo_path'])
            if os.path.isfile(full_path):
                with open(full_path, 'rb') as f:
                    img = MIMEImage(f.read())
                    slot_label = "早上" if r['time_slot'] == 'morning' else "晚上"
                    img.add_header(
                        'Content-ID',
                        f'<{r["user_id"]}_{r["time_slot"]}_{r["date"]}>'
                    )
                    img.add_header(
                        'Content-Disposition',
                        'attachment',
                        filename=f'{username}_{r["date"]}_{slot_label}.jpg'
                    )
                    msg.attach(img)
                    photo_count += 1

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
        server.sendmail(SMTP_USER, user_email, msg.as_string())
        server.quit()
        print(f"[{datetime.now()}] 用户 {username} ({user_email}): 邮件发送成功 ({len(records)} 条记录, {photo_count} 张照片)")
        return True
    except Exception as e:
        print(f"[{datetime.now()}] 用户 {username} ({user_email}): 邮件发送失败: {e}")
        return False


def mark_records_emailed(record_ids):
    """标记记录为已通过邮件发送"""
    if not record_ids:
        return
    conn = get_db()
    now = datetime.now().isoformat()
    placeholders = ','.join('?' * len(record_ids))
    conn.execute(
        f'UPDATE medication_records SET emailed_at = ? WHERE id IN ({placeholders})',
        [now] + list(record_ids)
    )
    conn.commit()
    conn.close()
    print(f"[{datetime.now()}] 已标记 {len(record_ids)} 条记录为已发送")


def cleanup_photos(records):
    """清理已发送记录的照片文件"""
    for r in records:
        if r['photo_path']:
            full_path = os.path.join(UPLOAD_FOLDER, r['photo_path'])
            if os.path.isfile(full_path):
                try:
                    os.remove(full_path)
                    print(f"[{datetime.now()}] 已删除照片: {r['photo_path']}")
                except OSError as e:
                    print(f"[{datetime.now()}] 删除照片失败: {r['photo_path']}, {e}")


def main():
    print(f"[{datetime.now()}] 开始执行每日邮件任务")

    users = get_unemailed_records()
    if not users:
        print(f"[{datetime.now()}] 所有记录均已发送过邮件，无需处理")
        return

    total_users = len(users)
    total_records = sum(len(u['records']) for u in users.values())
    print(f"[{datetime.now()}] 获取到 {total_users} 个用户, 共 {total_records} 条未发送记录")

    success_count = 0
    emailed_record_ids = []
    emailed_records = []  # 用于清理照片

    for uid, user_data in users.items():
        records = user_data['records']
        if send_user_email(user_data['username'], user_data['email'], records):
            success_count += 1
            emailed_record_ids.extend([r['id'] for r in records])
            emailed_records.extend(records)

    if success_count == total_users:
        # 全部成功：标记已发送 + 删除照片
        mark_records_emailed(emailed_record_ids)
        cleanup_photos(emailed_records)
        print(f"[{datetime.now()}] 每日邮件任务完成 ({success_count}/{total_users} 封邮件发送成功)")
    else:
        # 部分失败：不标记、不删照片，下次重试
        failed = total_users - success_count
        print(f"[{datetime.now()}] 部分邮件发送失败 ({success_count}/{total_users} 成功, {failed} 失败), 保留照片不删除, 下次重试")


if __name__ == '__main__':
    main()
