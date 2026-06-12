from flask import Flask, request, jsonify, send_from_directory
from flask_cors import CORS
from flask_jwt_extended import JWTManager, create_access_token, jwt_required, get_jwt_identity
from werkzeug.utils import secure_filename
import os
import hashlib
import hmac
import smtplib
import threading
from email.mime.multipart import MIMEMultipart
from email.mime.text import MIMEText
from email.mime.image import MIMEImage
from datetime import datetime, timedelta
import sqlite3
import json
import uuid

app = Flask(__name__)
app.config['JWT_SECRET_KEY'] = os.environ.get('JWT_SECRET_KEY', 'change-this-to-a-secure-random-key-in-production')
app.config['UPLOAD_FOLDER'] = os.environ.get('UPLOAD_FOLDER', '/var/pillguard/uploads')
app.config['MAX_CONTENT_LENGTH'] = 16 * 1024 * 1024  # 16MB max
app.config['DATABASE'] = os.environ.get('DATABASE', '/var/pillguard/pillguard.db')

# 邮件配置
SMTP_HOST = os.environ.get('SMTP_HOST', 'smtp.example.com')
SMTP_PORT = int(os.environ.get('SMTP_PORT', '587'))
SMTP_USER = os.environ.get('SMTP_USER', 'pillguard@example.com')
SMTP_PASSWORD = os.environ.get('SMTP_PASSWORD', '')
SMTP_USE_TLS = os.environ.get('SMTP_USE_TLS', 'true').lower() == 'true'
SMTP_USE_SSL = os.environ.get('SMTP_USE_SSL', 'false').lower() == 'true'
ADMIN_EMAIL = os.environ.get('ADMIN_EMAIL', 'admin@example.com')

CORS(app)
jwt = JWTManager(app)

ALLOWED_EXTENSIONS = {'jpg', 'jpeg', 'png'}

# 确保上传目录存在
os.makedirs(app.config['UPLOAD_FOLDER'], exist_ok=True)


def get_db():
    """获取数据库连接"""
    conn = sqlite3.connect(app.config['DATABASE'])
    conn.row_factory = sqlite3.Row
    return conn


def init_db():
    """初始化数据库"""
    conn = get_db()
    conn.executescript('''
        CREATE TABLE IF NOT EXISTS users (
            id TEXT PRIMARY KEY,
            username TEXT UNIQUE NOT NULL,
            password_hash TEXT NOT NULL,
            email TEXT,
            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
        );

        CREATE TABLE IF NOT EXISTS medication_records (
            id TEXT PRIMARY KEY,
            user_id TEXT NOT NULL,
            date TEXT NOT NULL,
            time_slot TEXT NOT NULL,
            completed BOOLEAN DEFAULT 0,
            photo_path TEXT,
            taken_at TIMESTAMP,
            is_duplicate BOOLEAN DEFAULT 0,
            uploaded_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            FOREIGN KEY (user_id) REFERENCES users(id)
        );
    ''')
    conn.commit()
    conn.close()


def hash_password(password: str) -> str:
    """密码哈希"""
    salt = os.environ.get('PASSWORD_SALT', 'pillguard-salt-change-in-production')
    return hashlib.pbkdf2_hmac('sha256', password.encode(), salt.encode(), 100000).hex()


def allowed_file(filename: str) -> bool:
    return '.' in filename and filename.rsplit('.', 1)[1].lower() in ALLOWED_EXTENSIONS


def send_duplicate_alert_email(username, date, time_slot, photo_path, taken_at):
    """在后台线程中发送重复打卡告警邮件"""
    try:
        slot_label = "早上" if time_slot == 'morning' else "晚上"
        today = datetime.now().strftime('%Y年%m月%d日 %H:%M')

        msg = MIMEMultipart('related')
        msg['Subject'] = f'[PillGuard] 重复打卡提醒 - {username} {date} {slot_label}'
        msg['From'] = f'PillGuard <{SMTP_USER}>'
        msg['To'] = ADMIN_EMAIL

        html = f"""
        <html>
        <head>
            <style>
                body {{ font-family: Arial, sans-serif; color: #333; }}
                .container {{ max-width: 600px; margin: 0 auto; padding: 20px; }}
                .header {{ background: #9C27B0; color: white; padding: 20px; border-radius: 8px 8px 0 0; }}
                .content {{ background: #f5f5f5; padding: 20px; border-radius: 0 0 8px 8px; }}
                .alert {{ background: #F3E5F5; border: 2px solid #9C27B0; padding: 15px; border-radius: 8px; margin: 10px 0; }}
                .info {{ font-size: 16px; margin: 8px 0; }}
                .label {{ font-weight: bold; color: #555; }}
            </style>
        </head>
        <body>
            <div class="container">
                <div class="header">
                    <h2>重复打卡提醒</h2>
                    <p>{today}</p>
                </div>
                <div class="content">
                    <div class="alert">
                        <p class="info"><span class="label">用户：</span>{username}</p>
                        <p class="info"><span class="label">日期：</span>{date}</p>
                        <p class="info"><span class="label">时段：</span>{slot_label}</p>
                        <p class="info"><span class="label">打卡时间：</span>{taken_at}</p>
                        <p class="info" style="color: #9C27B0; font-weight: bold;">该时段已有打卡记录，此次为重复打卡</p>
                    </div>
                    <p>重复打卡照片见附件。</p>
                </div>
            </div>
        </body>
        </html>
        """

        msg.attach(MIMEText(html, 'html', 'utf-8'))

        # 附加照片
        if photo_path:
            full_path = os.path.join(app.config['UPLOAD_FOLDER'], photo_path)
            if os.path.exists(full_path):
                with open(full_path, 'rb') as f:
                    img = MIMEImage(f.read())
                    img.add_header('Content-Disposition', 'attachment',
                                   filename=f'{username}_{slot_label}_重复打卡_{date}.jpg')
                    msg.attach(img)

        # 发送邮件
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
        print(f"[{datetime.now()}] 重复打卡邮件发送成功: {username} {date} {slot_label}")

        # 邮件发送成功后立即删除照片（避免在服务器上累积）
        if photo_path:
            full_path = os.path.join(app.config['UPLOAD_FOLDER'], photo_path)
            if os.path.exists(full_path):
                try:
                    os.remove(full_path)
                    print(f"[{datetime.now()}] 重复打卡照片已删除: {photo_path}")
                except OSError as e:
                    print(f"[{datetime.now()}] 删除重复打卡照片失败: {photo_path}, {e}")
    except Exception as e:
        print(f"[{datetime.now()}] 重复打卡邮件发送失败: {e}")


# ============ 认证接口 ============

@app.route('/api/auth/login', methods=['POST'])
def login():
    """用户登录"""
    data = request.get_json()
    username = data.get('username', '')
    password = data.get('password', '')

    if not username or not password:
        return jsonify(success=False, message='用户名和密码不能为空'), 400

    conn = get_db()
    user = conn.execute(
        'SELECT id, username, password_hash FROM users WHERE username = ?',
        (username,)
    ).fetchone()
    conn.close()

    if not user or user['password_hash'] != hash_password(password):
        return jsonify(success=False, message='用户名或密码错误'), 401

    token = create_access_token(identity=user['id'])
    return jsonify(success=True, token=token, userId=user['id'])


@app.route('/api/auth/register', methods=['POST'])
def register():
    """用户注册"""
    data = request.get_json()
    username = data.get('username', '')
    password = data.get('password', '')
    email = data.get('email', '')

    if not username or not password:
        return jsonify(success=False, message='用户名和密码不能为空'), 400

    conn = get_db()
    try:
        user_id = str(uuid.uuid4())
        conn.execute(
            'INSERT INTO users (id, username, password_hash, email) VALUES (?, ?, ?, ?)',
            (user_id, username, hash_password(password), email)
        )
        conn.commit()
        token = create_access_token(identity=user_id)
        return jsonify(success=True, token=token, userId=user_id), 201
    except sqlite3.IntegrityError:
        return jsonify(success=False, message='用户名已存在'), 409
    finally:
        conn.close()


@app.route('/api/auth/refresh', methods=['POST'])
@jwt_required()
def refresh_token():
    """刷新Token"""
    user_id = get_jwt_identity()
    token = create_access_token(identity=user_id)
    return jsonify(success=True, token=token, userId=user_id)


# ============ 照片上传接口 ============

@app.route('/api/upload/photo', methods=['POST'])
@jwt_required()
def upload_photo():
    """上传服药照片"""
    user_id = get_jwt_identity()

    if 'photo' not in request.files:
        return jsonify(success=False, message='未找到照片文件'), 400

    file = request.files['photo']
    if file.filename == '' or not allowed_file(file.filename):
        return jsonify(success=False, message='无效的文件格式'), 400

    date = request.form.get('date', datetime.now().strftime('%Y-%m-%d'))
    time_slot = request.form.get('timeSlot', 'morning')
    is_duplicate = request.form.get('isDuplicate', 'false').lower() == 'true'

    # 保存文件
    file_id = str(uuid.uuid4())
    filename = f"{user_id}_{date}_{time_slot}_{file_id}.jpg"
    filepath = os.path.join(app.config['UPLOAD_FOLDER'], filename)
    file.save(filepath)

    taken_at = datetime.now().isoformat()

    # 保存记录到数据库
    conn = get_db()
    try:
        record_id = str(uuid.uuid4())
        conn.execute('''
            INSERT INTO medication_records
            (id, user_id, date, time_slot, completed, photo_path, taken_at, is_duplicate)
            VALUES (?, ?, ?, ?, 1, ?, ?, ?)
        ''', (record_id, user_id, date, time_slot, filename, taken_at, 1 if is_duplicate else 0))
        conn.commit()
    finally:
        conn.close()

    # 重复打卡时立即发送告警邮件（后台线程，不阻塞响应）
    if is_duplicate:
        conn = get_db()
        user = conn.execute('SELECT username FROM users WHERE id = ?', (user_id,)).fetchone()
        conn.close()
        username = user['username'] if user else '未知用户'

        thread = threading.Thread(
            target=send_duplicate_alert_email,
            args=(username, date, time_slot, filename, taken_at)
        )
        thread.daemon = True
        thread.start()

    return jsonify(success=True, message='上传成功', fileId=file_id, isDuplicate=is_duplicate)


# ============ 记录查询接口 ============

@app.route('/api/records', methods=['GET'])
@jwt_required()
def get_records():
    """查询服药记录"""
    user_id = get_jwt_identity()
    start_date = request.args.get('startDate', '')
    end_date = request.args.get('endDate', '')

    conn = get_db()
    query = 'SELECT * FROM medication_records WHERE user_id = ?'
    params = [user_id]

    if start_date:
        query += ' AND date >= ?'
        params.append(start_date)
    if end_date:
        query += ' AND date <= ?'
        params.append(end_date)

    query += ' ORDER BY date DESC, time_slot ASC'

    records = conn.execute(query, params).fetchall()
    conn.close()

    result = []
    for r in records:
        result.append({
            'id': r['id'],
            'date': r['date'],
            'timeSlot': r['time_slot'],
            'completed': bool(r['completed']),
            'photoUrl': f"/api/photos/{r['photo_path']}" if r['photo_path'] else None,
            'takenAt': r['taken_at'],
            'isDuplicate': bool(r['is_duplicate'])
        })

    return jsonify(result)


@app.route('/api/photos/<filename>', methods=['GET'])
@jwt_required()
def get_photo(filename):
    """获取照片"""
    return send_from_directory(app.config['UPLOAD_FOLDER'], filename)


# ============ 健康检查 ============

@app.route('/api/health', methods=['GET'])
def health_check():
    return jsonify(status='ok', timestamp=datetime.now().isoformat())


# ============ 手动触发每日邮件 ============

@app.route('/api/daily-report/trigger', methods=['POST'])
@jwt_required()
def trigger_daily_report():
    """手动触发每日邮件发送。有记录则发送完整日报，无记录则发送空通知。"""
    import subprocess as sp
    import threading

    # 先检查是否有未发送记录
    db = get_db()
    count = db.execute(
        "SELECT COUNT(*) FROM medication_records WHERE emailed_at IS NULL"
    ).fetchone()[0]

    def run_script():
        try:
            result = sp.run(
                ['/var/pillguard/venv/bin/python3', '/opt/pillguard/server/daily_email.py'],
                capture_output=True, text=True, timeout=120
            )
            print(f"[{datetime.now()}] 手动日报: exit={result.returncode}")
            if result.stdout:
                for line in result.stdout.strip().split('\n')[-10:]:
                    print(f"  {line}")
        except sp.TimeoutExpired:
            print(f"[{datetime.now()}] 手动日报: 超时")
        except Exception as e:
            print(f"[{datetime.now()}] 手动日报失败: {str(e)}")

    if count > 0:
        # 有未发送记录 → 执行完整的 daily_email.py
        thread = threading.Thread(target=run_script, daemon=True)
        thread.start()
        return jsonify(success=True, message=f'日报发送任务已启动（{count} 条未发送记录），完成后将发送至您的邮箱')
    else:
        # 无未发送记录 → 发送一封简短的"今日无记录"通知邮件
        def send_empty_notification():
            try:
                today_str = datetime.now().strftime('%Y年%m月%d日')
                msg = MIMEText(
                    f'PillGuard 服药日报\n\n'
                    f'日期：{today_str}\n\n'
                    f'今天暂无服药打卡记录。\n\n'
                    f'这是一封自动通知邮件，表示 PillGuard 系统运行正常，但今日尚未收到打卡数据。\n'
                    f'如果您刚刚手动触发了日报发送，而今天确实还没有打卡，请忽略此邮件。',
                    'plain', 'utf-8'
                )
                msg['Subject'] = f'[PillGuard] 每日服药报告 - {today_str}（无记录）'
                msg['From'] = f'PillGuard <{SMTP_USER}>'
                msg['To'] = ADMIN_EMAIL

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
                print(f"[{datetime.now()}] 手动日报: 无未发送记录，已发送空通知邮件")
            except Exception as e:
                print(f"[{datetime.now()}] 手动日报空通知发送失败: {str(e)}")

        thread = threading.Thread(target=send_empty_notification, daemon=True)
        thread.start()
        return jsonify(success=True, message='今天暂无打卡记录，已发送空通知邮件至您的邮箱')


# ============ 邮件测试 ============

@app.route('/api/test/email', methods=['POST'])
@jwt_required()
def test_email():
    """发送测试邮件，验证 SMTP 配置是否正确"""
    try:
        msg = MIMEText(
            f'PillGuard 测试邮件发送成功！\n\n'
            f'发送时间：{datetime.now().strftime("%Y-%m-%d %H:%M:%S")}\n'
            f'SMTP 服务器：{SMTP_HOST}:{SMTP_PORT}\n'
            f'收件邮箱：{ADMIN_EMAIL}\n\n'
            f'如果您收到此邮件，说明 PillGuard 邮件配置正确。',
            'plain', 'utf-8'
        )
        msg['Subject'] = '[PillGuard] 邮件发送测试'
        msg['From'] = f'PillGuard <{SMTP_USER}>'
        msg['To'] = ADMIN_EMAIL

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

        print(f"[{datetime.now()}] 测试邮件发送成功")
        return jsonify(success=True, message='测试邮件发送成功，请检查收件箱')

    except Exception as e:
        print(f"[{datetime.now()}] 测试邮件发送失败: {str(e)}")
        return jsonify(success=False, message=f'邮件发送失败: {str(e)}'), 500


if __name__ == '__main__':
    init_db()
    app.run(host='0.0.0.0', port=5000, ssl_context='adhoc')
