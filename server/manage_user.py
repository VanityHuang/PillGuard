#!/usr/bin/env python3
"""
PillGuard 用户管理工具
用法:
  python3 manage_user.py list              # 列出所有用户
  python3 manage_user.py delete <用户名>    # 删除用户及其所有打卡记录
  python3 manage_user.py reset <用户名>     # 仅清除用户打卡记录，保留账号
"""

import os
import sys
import sqlite3

DATABASE = os.environ.get('DATABASE', '/var/pillguard/pillguard.db')


def get_db():
    conn = sqlite3.connect(DATABASE)
    conn.row_factory = sqlite3.Row
    return conn


def list_users():
    conn = get_db()
    users = conn.execute('SELECT id, username, email, created_at FROM users ORDER BY created_at').fetchall()

    if not users:
        print("暂无用户")
        conn.close()
        return

    print(f"{'用户名':<15} {'邮箱':<25} {'注册时间':<20} {'打卡记录数':<10}")
    print("-" * 70)

    for u in users:
        count = conn.execute(
            'SELECT COUNT(*) as cnt FROM medication_records WHERE user_id = ?',
            (u['id'],)
        ).fetchone()['cnt']
        email = u['email'] or '-'
        created = u['created_at'] or '-'
        print(f"{u['username']:<15} {email:<25} {created:<20} {count:<10}")

    conn.close()


def delete_user(username):
    conn = get_db()
    user = conn.execute('SELECT id, username FROM users WHERE username = ?', (username,)).fetchone()

    if not user:
        print(f"用户 '{username}' 不存在")
        conn.close()
        return

    # 统计数据
    record_count = conn.execute(
        'SELECT COUNT(*) as cnt FROM medication_records WHERE user_id = ?',
        (user['id'],)
    ).fetchone()['cnt']

    # 确认删除
    print(f"即将删除用户 '{username}' 及其 {record_count} 条打卡记录")
    confirm = input("确认删除？(输入 yes 确认): ").strip()

    if confirm != 'yes':
        print("已取消")
        conn.close()
        return

    # 删除打卡记录
    conn.execute('DELETE FROM medication_records WHERE user_id = ?', (user['id'],))
    # 删除用户
    conn.execute('DELETE FROM users WHERE id = ?', (user['id'],))
    conn.commit()
    conn.close()

    print(f"用户 '{username}' 及其所有数据已删除")


def reset_user(username):
    conn = get_db()
    user = conn.execute('SELECT id, username FROM users WHERE username = ?', (username,)).fetchone()

    if not user:
        print(f"用户 '{username}' 不存在")
        conn.close()
        return

    record_count = conn.execute(
        'SELECT COUNT(*) as cnt FROM medication_records WHERE user_id = ?',
        (user['id'],)
    ).fetchone()['cnt']

    print(f"即将清除用户 '{username}' 的 {record_count} 条打卡记录（保留账号）")
    confirm = input("确认清除？(输入 yes 确认): ").strip()

    if confirm != 'yes':
        print("已取消")
        conn.close()
        return

    conn.execute('DELETE FROM medication_records WHERE user_id = ?', (user['id'],))
    conn.commit()
    conn.close()

    print(f"用户 '{username}' 的打卡记录已清除，账号保留")


def main():
    if len(sys.argv) < 2:
        print("用法:")
        print("  python3 manage_user.py list              # 列出所有用户")
        print("  python3 manage_user.py delete <用户名>    # 删除用户及其所有打卡记录")
        print("  python3 manage_user.py reset <用户名>     # 仅清除打卡记录，保留账号")
        sys.exit(1)

    command = sys.argv[1]

    if command == 'list':
        list_users()
    elif command == 'delete':
        if len(sys.argv) < 3:
            print("请指定用户名: python3 manage_user.py delete <用户名>")
            sys.exit(1)
        delete_user(sys.argv[2])
    elif command == 'reset':
        if len(sys.argv) < 3:
            print("请指定用户名: python3 manage_user.py reset <用户名>")
            sys.exit(1)
        reset_user(sys.argv[2])
    else:
        print(f"未知命令: {command}")
        print("可用命令: list, delete, reset")


if __name__ == '__main__':
    main()
