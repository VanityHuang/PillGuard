#!/usr/bin/env python3
"""
创建PillGuard用户账号
用法: python3 create_user.py
"""

import os
import sys
import sqlite3
import uuid
import hashlib
import subprocess


def get_service_env():
    """从systemd服务中读取环境变量"""
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


DATABASE = os.environ.get('DATABASE', '/var/pillguard/pillguard.db')
PASSWORD_SALT = os.environ.get('PASSWORD_SALT', 'pillguard-salt-change-in-production')


def hash_password(password: str) -> str:
    return hashlib.pbkdf2_hmac('sha256', password.encode(), PASSWORD_SALT.encode(), 100000).hex()


def main():
    # 尝试从systemd服务中读取环境变量
    get_service_env()
    # 重新读取（可能已被更新）
    global PASSWORD_SALT
    PASSWORD_SALT = os.environ.get('PASSWORD_SALT', 'pillguard-salt-change-in-production')

    print("=== PillGuard 创建用户 ===")
    if PASSWORD_SALT == 'pillguard-salt-change-in-production':
        print("⚠ 警告: 使用默认密码盐值，请确认服务端也使用相同盐值")
    print()

    username = input("请输入用户名: ").strip()
    if not username:
        print("用户名不能为空")
        sys.exit(1)

    password = input("请输入密码: ").strip()
    if not password:
        print("密码不能为空")
        sys.exit(1)

    email = input("请输入邮箱（可选，直接回车跳过）: ").strip()

    conn = sqlite3.connect(DATABASE)
    try:
        user_id = str(uuid.uuid4())
        conn.execute(
            'INSERT INTO users (id, username, password_hash, email) VALUES (?, ?, ?, ?)',
            (user_id, username, hash_password(password), email if email else None)
        )
        conn.commit()
        print(f"\n用户 '{username}' 创建成功！")
        print(f"用户ID: {user_id}")
    except sqlite3.IntegrityError:
        print(f"\n错误: 用户名 '{username}' 已存在")
        sys.exit(1)
    finally:
        conn.close()


if __name__ == '__main__':
    main()
