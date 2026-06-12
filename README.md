# PillGuard - 高血压患者用药提醒应用

一款专为高血压患者设计的Android用药提醒应用，支持定时提醒、拍照打卡、数据同步和邮件汇报功能。

## 主要功能

- **定时提醒**：每日8:00和20:00自动提醒服药，支持震动、响铃和弹窗通知（可自由组合）
- **智能提醒**：已打卡则自动跳过提醒；未打卡则每5分钟重复提醒，最多6次；使用系统闹钟接口（AlarmClock），厂商ROM兼容性更优
- **拍照打卡**：一键调用摄像头拍照，照片保存在内部私有存储（**不在系统相册中显示**），打卡时间自动判定早/晚时段
- **重复打卡检测**：重复打卡时弹窗警告，紫色标识，在线模式即时邮件通知
- **服药历史**：主页7天周历 + 30天历史列表，直观展示打卡状态
- **离线模式**：无服务器也可使用全部本地功能，照片排队等待上线后自动上传
- **数据同步**：在线登录后自动同步服务器记录，照片通过WorkManager后台上传，成功后自动删除本地副本
- **每日邮件**：服务器22:00自动发送当日服药汇总邮件（含照片附件）；重复打卡立即发送即时告警
- **提醒设置**：可自由开关震动/响铃/弹窗，支持测试提醒；**电池优化**和**精准闹钟权限**状态一目了然，一键跳转系统设置
- **Web 管理面板**：浏览器访问 `https://yellowduck.top/pillguard/`，支持月历打卡视图、照片预览、今日打卡横幅、月度统计、测试邮件、手动触发日报

## 技术栈

| 层级 | 技术 |
|------|------|
| Android客户端 | Kotlin, Room, CameraX, WorkManager, AlarmManager, Retrofit |
| 服务器端 | Python, Flask, Gunicorn, Nginx, SQLite |
| Web 管理面板 | 纯 HTML/CSS/JS（无框架），响应式布局 |
| 安全 | AndroidKeyStore AES-GCM加密, JWT认证, HTTPS |

## 环境要求

### 客户端

- **推荐**：Android Studio (Flamingo 或更新版本)
- **或**：VS Code + Android SDK 命令行（需设置 `ANDROID_HOME` 环境变量）
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
2. 系统调用摄像头，拍照后自动保存（**保存在APP内部私有目录，不会出现在系统相册**）
3. 打卡时间自动判定时段：
   - 2:00 ~ 14:00 → 早上打卡
   - 14:00 ~ 次日2:00 → 晚上打卡
4. 如果该时段已有打卡记录，会弹出重复打卡警告
5. 在线模式下照片自动排队上传，上传成功后自动删除本地副本；离线照片保留最多 24 小时后自动清理

### 查看历史

- **主页底部**：近7天周历，圆点颜色表示状态（绿色=正常，紫色=重复，红色=未完成）
- **历史记录页**：点击主页右上角菜单 → 历史记录，查看30天详细记录

### 设置

- **提醒方式**：可自由开关震动、响铃、弹窗通知
- **系统权限**：电池优化状态 + 精准闹钟权限检查，未开启可一键跳转系统设置开启（**确保提醒准时触发的关键**）
- **测试提醒**：点击后立即触发一次提醒，方便验证提醒效果
- **服务器地址**：支持修改 API 服务器地址
- **清除打卡记录**：离线模式直接清除，在线模式清除后需重新登录（自动填入上次登录信息）
- **退出登录**：主页右上角菜单 → 退出登录

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

# 重启服务（修改 app.py 后需要）
sudo systemctl restart pillguard

# 查看服务日志
sudo journalctl -u pillguard --no-pager -n 30

# 查看每日邮件日志
sudo tail -30 /var/pillguard/logs/daily_email.log

# 测试邮件发送
sudo /var/pillguard/venv/bin/python3 /opt/pillguard/server/test_email.py

# 手动运行一次每日邮件
sudo /var/pillguard/venv/bin/python3 /opt/pillguard/server/daily_email.py

# 查看服务器照片目录
ls -lh /var/pillguard/uploads/

# 查看数据库记录
sqlite3 /var/pillguard/pillguard.db "SELECT mr.date, mr.time_slot, mr.is_duplicate, u.username FROM medication_records mr JOIN users u ON mr.user_id=u.id ORDER BY mr.date DESC LIMIT 10;"
```

## Web 管理面板

浏览器访问 `https://yellowduck.top/pillguard/`，使用与 APP 相同的账号密码登录。

### 功能

| 功能 | 说明 |
|------|------|
| **月历视图** | 当月日历网格，早晚两个状态圆点（绿=正常，紫=重复，红=未打卡） |
| **今日横幅** | 顶部醒目显示今天早/晚打卡状态和时间 |
| **月度统计** | 本月打卡率、打卡天数、总打卡次数 |
| **点击详情** | 点击日历某天，展开显示早晚打卡具体时间和状态 |
| **时段/状态筛选** | 按月历筛选时段（早/晚）和状态（正常/重复/未打卡） |
| **照片预览** | 查看服务端暂存照片（缩略图网格 + 点击放大灯箱） |
| **测试邮件** | 一键发送测试邮件，验证 SMTP 配置 |
| **手动日报** | 手动触发 `daily_email.py`，有记录发送完整报告，无记录发送空通知 |
| **响应式布局** | 自动适配手机 / 平板 / 桌面 |

### 部署

Web 面板文件位于仓库 `web/pillguard/` 目录，部署到服务器 `/var/www/yellowduck/pillguard/`：

```bash
# 创建目录并复制文件
ssh root@47.106.163.25 "mkdir -p /var/www/yellowduck/pillguard/{css,js,login}"
scp -r web/pillguard/* root@47.106.163.25:/var/www/yellowduck/pillguard/
ssh root@47.106.163.25 "chown -R www-data:www-data /var/www/yellowduck/pillguard/"
```

Nginx 已配置 `/pillguard/` 静态文件路由和 `/pillguard/api/` API 代理（自动剥离前缀转发到 Flask `/api/`）。

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
│   │   │   ├── UploadWorker.kt                # 后台上传服务
│   │   │   └── PhotoCleanupWorker.kt          # 超时照片清理
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
└── web/pillguard/                             # Web 管理面板
    ├── index.html                             # 控制页（日历 + 统计 + 照片）
    ├── login/
    │   └── index.html                         # 登录页
    ├── css/
    │   └── style.css                          # 响应式样式
    └── js/
        ├── api.js                             # API 封装（JWT管理）
        └── calendar.js                        # 日历组件
```

## 邮件通知机制

| 触发条件 | 邮件内容 | 服务器照片处理 |
|----------|----------|---------------|
| 每日22:00（root cron） | 当日服药汇总 + 当天所有打卡照片 | 发送后删除当天全部照片 |
| 在线重复打卡（Flask 后台线程） | 即时告警 + 重复打卡照片 | **发送后立即删除该重复照片** |

**工作原理**：SMTP 配置存储在 systemd 环境变量中。`daily_email.py` 和 `test_email.py` 通过 `get_service_env()` 函数从 `systemctl show pillguard` 读取配置，解决 cron 环境无环境变量的问题。

**照片存储路径**：服务器 `/var/pillguard/uploads/`，手机 `/data/data/com.pillguard.app/files/pillsguard_photos/`（APP 私有，相册不可见）。

**测试邮件**：
```bash
sudo /var/pillguard/venv/bin/python3 /opt/pillguard/server/test_email.py
```
