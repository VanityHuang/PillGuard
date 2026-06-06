# PillGuard - 高血压患者用药提醒应用

一款专为高血压患者设计的Android用药提醒应用，支持定时提醒、拍照打卡、数据同步和邮件汇报功能。

## 主要功能

- **定时提醒**：每日8:00和20:00自动提醒服药，支持震动、响铃和弹窗通知（可自由组合）
- **智能提醒**：已打卡则自动跳过提醒；未打卡则每5分钟重复提醒，最多6次
- **拍照打卡**：一键调用摄像头拍照，打卡时间自动判定早/晚时段
- **重复打卡检测**：重复打卡时弹窗警告，紫色标识，在线模式即时邮件通知
- **服药历史**：主页7天周历 + 30天历史列表，直观展示打卡状态
- **离线模式**：无服务器也可使用全部本地功能
- **数据同步**：在线登录后自动同步服务器记录，照片后台自动上传
- **每日邮件**：服务器22:00自动发送当日服药汇总邮件（含照片附件）
- **提醒设置**：可自由开关震动/响铃/弹窗，支持测试提醒

## 技术栈

| 层级 | 技术 |
|------|------|
| Android客户端 | Kotlin, Room, CameraX, WorkManager, AlarmManager, Retrofit |
| 服务器端 | Python, Flask, Gunicorn, Nginx, SQLite |
| 安全 | AndroidKeyStore AES-GCM加密, JWT认证, HTTPS |

## 环境要求

### 客户端

- Android Studio (Flamingo 或更新版本)
- JDK 17
- Android SDK 34
- 最低运行设备：Android 8.0 (API 26)

### 服务器

- Ubuntu 20.04+ 或其他 Debian 系 Linux
- Python 3.8+
- 公网IP或域名

## 安装与配置

### 一、Android客户端

1. 用 Android Studio 打开项目
2. **File → Settings → Build Tools → Gradle → Gradle JDK** 选择 JDK 17
3. Sync Gradle
4. 连接手机或模拟器，点击运行

### 二、服务器部署

#### 1. 上传文件

```bash
scp -r server/* root@你的服务器IP:/opt/pillguard/server/
```

#### 2. 修改配置

编辑 `server/deploy.sh`，修改以下配置项：

| 配置项 | 说明 | 示例 |
|--------|------|------|
| `SERVER_IP` | 服务器公网IP | `47.106.163.25` |
| `SMTP_HOST` | 邮件服务器地址 | `smtp.qq.com` |
| `SMTP_PORT` | 邮件端口 | `465`(SSL) |
| `SMTP_USER` | 发件邮箱 | `xxx@qq.com` |
| `SMTP_PASSWORD` | 邮箱授权码 | QQ邮箱设置中获取 |
| `ADMIN_EMAIL` | 收件邮箱 | `xxx@qq.com` |

> JWT密钥和密码盐值在部署时自动生成，无需手动配置。

#### 3. 执行部署

```bash
ssh root@你的服务器IP
cd /opt/pillguard/server && bash deploy.sh
```

#### 4. 创建用户

```bash
python3 /opt/pillguard/server/create_user.py
```

按提示输入用户名和密码。

#### 5. 验证

```bash
# 检查 API 健康状态
curl http://你的服务器IP/api/health

# 测试邮件发送
sudo /var/pillguard/venv/bin/python3 /opt/pillguard/server/test_email.py
```

返回 `{"status":"ok",...}` 即 API 部署成功；收到测试邮件即邮件配置正确。

## 使用教程

### 登录

打开APP后有两种登录方式：

- **在线登录**：输入服务器地址（如 `http://47.106.163.25`）、用户名和密码
- **离线登录**：点击"离线登录"按钮，无需任何输入

登录成功后下次打开APP会自动登录。自动登录失败时会弹出登录页并自动填入上次的信息。

### 打卡

1. 在主页点击**拍照打卡**按钮
2. 系统调用摄像头，拍照后自动保存
3. 打卡时间自动判定时段：
   - 2:00 ~ 14:00 → 早上打卡
   - 14:00 ~ 次日2:00 → 晚上打卡
4. 如果该时段已有打卡记录，会弹出重复打卡警告

### 查看历史

- **主页底部**：近7天周历，圆点颜色表示状态（绿色=正常，紫色=重复，红色=未完成）
- **历史记录页**：点击主页右上角菜单 → 历史记录，查看30天详细记录

### 设置

- 提醒方式：可自由开关震动、响铃、弹窗通知
- 测试提醒：点击后立即触发一次提醒，方便验证提醒效果
- 清除打卡记录：离线模式直接清除，在线模式清除后需重新登录（自动填入上次登录信息）
- 退出登录：主页右上角菜单 → 退出登录

## 服务器管理

```bash
# 查看所有用户
python3 /opt/pillguard/server/manage_user.py list

# 创建新用户
python3 /opt/pillguard/server/create_user.py

# 清除用户打卡记录（保留账号）
python3 /opt/pillguard/server/manage_user.py reset 用户名

# 删除用户及所有数据
python3 /opt/pillguard/server/manage_user.py delete 用户名

# 查看服务状态
sudo systemctl status pillguard

# 重启服务
sudo systemctl restart pillguard

# 查看服务日志
sudo journalctl -u pillguard -f

# 查看每日邮件日志
sudo tail -30 /var/pillguard/logs/daily_email.log

# 测试邮件发送（验证SMTP配置是否正常）
sudo /var/pillguard/venv/bin/python3 /opt/pillguard/server/test_email.py

# 手动运行一次每日邮件（不含 cron 环境限制）
sudo /var/pillguard/venv/bin/python3 /opt/pillguard/server/daily_email.py
```

### 域名与 SSL

服务器支持通过域名 `yellowduck.top`（Let's Encrypt 证书）和 IP `47.106.163.25` 访问：

- **Android App**：使用 `http://47.106.163.25`（HTTP 直连，不经过 SSL）
- **浏览器 / API 调用**：推荐 `https://yellowduck.top`

Nginx 配置位于 `/etc/nginx/sites-available/yellowduck`，修改后需执行：
```bash
sudo nginx -t && sudo systemctl reload nginx
```

## 项目结构

```
PillGuard/
├── app/src/main/
│   ├── AndroidManifest.xml
│   ├── java/com/pillguard/app/
│   │   ├── PillGuardApp.kt                    # Application类
│   │   ├── data/local/
│   │   │   ├── AppDatabase.kt                 # Room数据库
│   │   │   ├── MedicationRecord.kt            # 数据实体
│   │   │   └── MedicationRecordDao.kt         # 数据访问接口
│   │   ├── network/
│   │   │   ├── ApiClient.kt                   # Retrofit客户端
│   │   │   ├── ApiService.kt                  # API接口定义
│   │   │   └── UploadWorker.kt                # 后台上传服务
│   │   ├── receiver/
│   │   │   ├── BootReceiver.kt                # 开机重设提醒
│   │   │   └── ReminderReceiver.kt            # 提醒触发器
│   │   ├── security/
│   │   │   └── SecurityManager.kt             # 加密存储
│   │   ├── service/
│   │   │   └── ReminderService.kt             # 提醒前台服务
│   │   ├── ui/
│   │   │   ├── MainActivity.kt                # 主页
│   │   │   ├── LoginActivity.kt               # 登录页
│   │   │   ├── CameraActivity.kt              # 拍照页
│   │   │   ├── HistoryActivity.kt             # 历史记录页
│   │   │   ├── SettingsActivity.kt            # 设置页
│   │   │   └── adapter/                       # 列表适配器
│   │   └── util/
│   │       ├── AuthManager.kt                 # 认证管理
│   │       └── ReminderScheduler.kt           # 闹钟调度
│   └── res/                                   # 布局、图标、样式等资源
│
└── server/
    ├── app.py                                 # Flask API服务
    ├── daily_email.py                         # 每日邮件脚本
    ├── test_email.py                          # SMTP邮件测试脚本
    ├── create_user.py                         # 创建用户脚本
    ├── manage_user.py                         # 用户管理脚本
    ├── deploy.sh                              # 一键部署脚本
    └── requirements.txt                       # Python依赖
```

## 邮件通知机制

| 触发条件 | 邮件内容 |
|----------|----------|
| 每日22:00（root cron 定时任务） | 当日服药情况汇总 + 所有打卡照片 |
| 在线重复打卡（Flask 后台线程） | 即时告警 + 重复打卡照片 |

**工作原理**：SMTP 配置（QQ邮箱等）存储在 systemd 环境变量中，不在 cron 环境变量中。`daily_email.py` 和 `test_email.py` 通过 `get_service_env()` 函数自动从 systemd 服务读取配置，确保 cron 定时任务能正常发送邮件。

邮件发送后服务器自动删除已发送的照片文件。

**测试邮件**：
```bash
sudo /var/pillguard/venv/bin/python3 /opt/pillguard/server/test_email.py
```
