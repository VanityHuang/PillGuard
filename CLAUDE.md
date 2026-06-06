# CLAUDE.md

本文件为 Claude Code (claude.ai/code) 在此仓库中工作时提供指导。

## 构建与测试命令

```bash
# Android 客户端（在项目根目录执行）
./gradlew assembleDebug                          # 构建 Debug APK
./gradlew assembleRelease                        # 构建 Release APK（需要 local.properties 中的签名配置）
./gradlew test                                   # 运行单元测试（JUnit 4）
./gradlew connectedAndroidTest                   # 在已连接的设备/模拟器上运行插桩测试
./gradlew :app:test --tests "com.pillguard.app.ExampleTest"  # 运行单个测试类

# 服务器端（Python）
pip install -r server/requirements.txt           # 安装 Python 依赖
python3 server/app.py                            # 运行 Flask 开发服务器（使用临时自签名 SSL）
gunicorn -w 4 -b 127.0.0.1:5000 app:app         # 生产环境运行（在 server/ 目录下执行）
```

项目使用 JDK 17、Kotlin 1.9.20、AGP 8.1.4、KSP（用于 Room 注解处理），启用了 ViewBinding。最低 SDK 26，目标 SDK 34。源码/目标兼容性为 Java 11（在 app build.gradle 中设置 `kotlinOptions.jvmTarget = '11'`）。

## 架构概览

**离线优先的双模式设计**：Android 应用在完全离线状态下可通过本地 Room 数据库正常工作。当用户在线登录后，记录会与 Flask 服务器双向同步。照片通过 WorkManager 排队后台上传。

### Android 客户端 — 包名 `com.pillguard.app`

**入口点**：`PillGuardApp`（Application 子类）暴露了一个 `database: AppDatabase` 懒加载单例，整个应用中通过 `(application as PillGuardApp).database` 访问。

**数据层**（`data/local/`）：
- `MedicationRecord` — 唯一的 Room 实体，包含 date、timeSlot（"morning"/"evening"）、completed、photoPath、uploaded、isDuplicate 等字段
- `MedicationRecordDao` — 所有数据库查询接口；注意该 DAO 使用 `LiveData` 进行响应式查询（供 MainActivity 使用），同时使用 `suspend` 函数进行一次性操作
- 数据库使用了 `fallbackToDestructiveMigration()` — schema 版本升级时会销毁现有数据

**网络层**（`network/`）：
- `ApiClient` — 单例 Retrofit 封装。使用前必须先调用 `init(baseUrl, authToken)` 进行初始化。包含日志拦截器和认证拦截器，采用线程安全的懒加载初始化方式注入 auth token。
- `ApiService` — Retrofit 接口，定义了登录、照片上传（multipart）、记录查询、token 刷新等端点
- `UploadWorker` — CoroutineWorker，以指数退避策略上传照片。通过 `UploadWorker.scheduleUpload()` 调度，受网络和电量约束限制。上传成功后标记本地记录为已上传并删除本地照片缓存。

**安全**（`security/SecurityManager`）：
- 通过 AndroidKeyStore 硬件密钥进行 AES-GCM 加密
- 凭证（userId + JWT）加密存储在 SharedPreferences 中
- 密钥别名：`pillguard_auth_key`

**提醒流水线**（`receiver/` + `service/`）：
1. `ReminderScheduler` 设置 AlarmManager 闹钟，时间分别为 08:00 和 20:00
2. `ReminderReceiver` 触发 → 检查数据库中是否已完成打卡 → 启动 `ReminderService` 前台服务
3. `ReminderService` 执行震动、播放闹钟铃声、显示全屏通知。如果是首次提醒（retryCount=0），则重新调度下一天的闹钟。同时调度 5 分钟后的重试闹钟，最多重试 6 次。
4. `BootReceiver` 在设备重启后重新注册所有闹钟

**AuthManager 中的关键业务逻辑**：
- 打卡时段：早上 = 2:00–14:00，晚上 = 14:00–次日 2:00
- 午夜跨越规则：0:00–2:00 属于前一天的晚上时段
- 离线用户以 SHA-256 哈希密码存储在 SharedPreferences 中

**UI**（5 个 Activity，均为竖屏模式，使用 ViewBinding）：
- `LoginActivity` — 自动填入上次登录信息，尝试静默自动重新登录，提供离线模式入口。在线登录成功后，将服务器记录同步到本地数据库（最近 30 天）。
- `MainActivity` — 显示当天早晚打卡状态、7 天周历（通过 RecyclerView 展示）、工具栏包含历史记录/设置/退出登录。通过 `SecurityManager.isLoggedIn()` 守卫入口。
- `CameraActivity` — CameraX 实现，照片保存到 MediaStore 的 `Pictures/PillGuard/` 目录。保存前检测重复打卡。离线模式下跳过拍照，直接记录打卡。
- `HistoryActivity` — 通过 RecyclerView 展示 30 天打卡记录列表
- `SettingsActivity` — 提醒方式开关（震动/响铃/弹窗）、测试提醒按钮、服务器地址配置、清除历史记录（区分在线/离线模式处理）

### 服务器端 — Flask + Gunicorn + Nginx + SQLite

**Nginx 路由架构**（生产环境）：
- Nginx 同时监听 80（HTTP）和 443（HTTPS，Let's Encrypt 证书）
- `location /api/` → `127.0.0.1:5000`（gunicorn / Flask API）
- `location /` → `127.0.0.1:8080`（Node.js 前端服务）
- HTTP 下 `/api/` 路径**不强制跳转 HTTPS**，确保 Android 客户端通过 IP 直连时不受 SSL 证书主机名限制；非 API 请求仍 301 跳转 HTTPS

单个 Flask 应用（`server/app.py`），提供以下端点：
- `POST /api/auth/login`、`/api/auth/register`、`/api/auth/refresh` — 基于 JWT 的认证
- `POST /api/upload/photo` — multipart 照片上传，保存记录。重复打卡上传时触发即时告警邮件（后台线程，不阻塞响应）
- `GET /api/records` — 按 userId + 日期范围查询记录，返回 JSON
- `GET /api/photos/<filename>` — 提供照片文件访问
- `GET /api/health` — 健康检查

数据库包含两张表：`users`（id, username, password_hash, email）和 `medication_records`（通过 user_id 外键关联）。

辅助脚本：
- `deploy.sh` — 全自动部署脚本：安装系统依赖、设置 Python 虚拟环境、生成 JWT 密钥和密码盐值、配置 systemd 服务（gunicorn 监听 :5000）、配置 Nginx 反向代理（监听 :80）、使用 root crontab 设置每日 22:00 邮件任务
- `daily_email.py` — 生成按用户分组的 HTML 报告并附带照片附件，发送后清理已发送的照片文件。**关键设计**：`get_service_env()` 函数通过 `systemctl show pillguard --property=Environment` 从 systemd 服务读取 SMTP 配置，解决 cron 环境下缺少环境变量的问题。在 cron 中运行时必须以此方式获取配置。
- `test_email.py` — SMTP 邮件发送测试脚本，同样通过 `get_service_env()` 从 systemd 读取配置。部署后运行 `sudo /var/pillguard/venv/bin/python3 /opt/pillguard/server/test_email.py` 验证邮件功能
- `create_user.py` — 交互式创建用户（从 systemd 环境变量中读取 PASSWORD_SALT）
- `manage_user.py` — 命令行用户管理工具（list/delete/reset）

### Release 签名

`local.properties` 文件已加入 `.gitignore`，其中包含用于 `pillguard-release.jks` 密钥库的 `RELEASE_STORE_PASSWORD`、`RELEASE_KEY_ALIAS`、`RELEASE_KEY_PASSWORD`。

### 已知注意事项

- SMTP 配置（QQ 邮箱等）存储在 systemd 环境变量中，非 cron 环境变量。`daily_email.py` 和 `test_email.py` 通过 `get_service_env()` 兜底读取。
- Nginx 配置位于 `/etc/nginx/sites-available/yellowduck`，每次修改后需 `sudo nginx -t && sudo systemctl reload nginx`。
- Let's Encrypt 证书域名为 `yellowduck.top`，Android 客户端使用 HTTP + IP 直连可绕过证书主机名验证问题。
