# CLAUDE.md

本文件为 Claude Code (claude.ai/code) 在此仓库中工作时提供指导。

## 构建与测试命令

```bash
# Android 客户端（在项目根目录执行，需先设置 ANDROID_HOME）
export ANDROID_HOME="C:\Program Files\Value\android-sdk"
./gradlew assembleDebug                          # 构建 Debug APK
./gradlew assembleRelease                        # 构建 Release APK（需要 local.properties 中的签名配置）
./gradlew test                                   # 运行单元测试（JUnit 4）
./gradlew connectedAndroidTest                   # 在已连接的设备/模拟器上运行插桩测试
./gradlew :app:test --tests "com.pillguard.app.ExampleTest"  # 运行单个测试类

# 服务器端（Python）
pip install -r server/requirements.txt           # 安装 Python 依赖
python3 server/app.py                            # 运行 Flask 开发服务器（使用临时自签名 SSL）
gunicorn -w 4 -b 127.0.0.1:5000 app:app         # 生产环境运行（在 server/ 目录下执行）

# 服务器管理（SSH 到 47.106.163.25，通过 C:\Users\User\.ssh\DESKTOP-HML.pem 密钥）
sudo systemctl status pillguard                  # 查看 API 服务状态
sudo systemctl restart pillguard                 # 重启 API 服务（app.py 修改后需要）
sudo journalctl -u pillguard --no-pager -n 20    # 查看服务日志
sudo tail -30 /var/pillguard/logs/daily_email.log  # 查看每日邮件日志
sudo /var/pillguard/venv/bin/python3 /opt/pillguard/server/test_email.py  # 测试 SMTP 邮件
sudo nginx -t && sudo systemctl reload nginx     # Nginx 配置重载（修改 /etc/nginx/sites-available/yellowduck 后）
```

项目使用 JDK 17、Kotlin 1.9.20、AGP 8.1.4、KSP（用于 Room 注解处理），启用了 ViewBinding。最低 SDK 26，目标 SDK 34。源码/目标兼容性为 Java 11。**Release 构建切勿开启 `minifyEnabled true`**（R8 混淆会导致登录功能故障）。

## 架构概览

**离线优先的双模式设计**：Android 应用在完全离线状态下可通过本地 Room 数据库正常工作。当用户在线登录后，记录会与 Flask 服务器双向同步。照片通过 WorkManager 排队后台上传。

### Android 客户端 — 包名 `com.pillguard.app`

**入口点**：`PillGuardApp`（Application 子类）在 `onCreate()` 中调度 `PhotoCleanupWorker`（定期清理超24h的照片）并执行一次性迁移（清理旧版 MediaStore 遗留照片）。暴露 `database: AppDatabase` 懒加载单例，整个应用中通过 `(application as PillGuardApp).database` 访问。

**数据层**（`data/local/`）：
- `MedicationRecord` — 唯一的 Room 实体，包含 date、timeSlot、completed、photoPath、uploaded、isDuplicate 等字段。`photoPath` 存储**内部存储绝对路径**（`filesDir/pillsguard_photos/xxx.jpg`），不再使用 `content://` URI
- `MedicationRecordDao` — 同时使用 `LiveData`（响应式查询，供 MainActivity）和 `suspend` 函数（一次性操作）
- 数据库使用 `fallbackToDestructiveMigration()` — schema 升级时销毁现有数据

**照片存储**（关键设计）：
- 拍照保存到 **内部存储** `context.filesDir/pillsguard_photos/`，**不在系统相册中显示**
- 离线模式也保存照片（`uploaded = false`），等待上线后 WorkManager 自动上传
- 删除策略：①上传成功后立即删除 ②`PhotoCleanupWorker` 每 6 小时删除超过 24 小时的照片（长期离线兜底）
- 旧版（MediaStore `Pictures/PillGuard/`）遗留照片由 `PillGuardApp` 一次性迁移清理

**网络层**（`network/`）：
- `ApiClient` — 单例 Retrofit 封装，使用前必须 `init(baseUrl, authToken)`。含日志拦截器和认证拦截器
- `ApiService` — Retrofit 接口（登录、multipart 照片上传、记录查询、token 刷新）
- `UploadWorker` — CoroutineWorker，上传照片到服务器。24 小时超时放弃 + 指数退避重试。适配内部存储文件路径（兼容旧 content:// URI）。约束：网络已连接 + 电量非低
- `PhotoCleanupWorker` — PeriodicWorkRequest（每 6 小时），扫描 `pillsguard_photos/` 删除超过 24h 的本地照片

**安全**（`security/SecurityManager`）：
- AndroidKeyStore 硬件密钥进行 AES-GCM 加密，密钥别名 `pillguard_auth_key`
- 凭证（userId + JWT）加密存储在 SharedPreferences 中

**提醒流水线**（`receiver/` + `service/`）：
1. `ReminderScheduler` 使用 **`AlarmManager.setAlarmClock()`**（比 `setExactAndAllowWhileIdle` 厂商兼容性更好，状态栏短暂显示闹钟图标），时间 08:00 + 20:00
2. 若精准闹钟权限未授权（API 31+）自动回退到 `setAndAllowWhileIdle`
3. `ReminderReceiver` 触发 → 检查数据库是否已打卡 → 启动 `ReminderService` 前台服务
4. `ReminderService` 执行震动/响铃/全屏通知，**`START_STICKY`**（被杀后自动重启），首次提醒后调度次日闹钟 + 5 分钟重试（最多 6 次）
5. `BootReceiver` 在设备重启后重新注册所有闹钟
6. `SettingsActivity` 提供**电池优化**和**精准闹钟权限**状态检查 + 一键跳转系统设置

**AuthManager 关键业务逻辑**：
- 打卡时段：早上 2:00–14:00，晚上 14:00–次日 2:00；0:00–2:00 属于前一天晚上
- 离线用户以 SHA-256 哈希密码存储在 SharedPreferences 中

**UI**（6 个 Activity，竖屏 + ViewBinding）：
- `LoginActivity` — 自动填入上次信息、静默自动登录、离线模式入口。在线登录成功同步服务器最近 30 天记录。**开发者模式**：用户名和密码都输入 `dev` 可进入 DevModeActivity
- `MainActivity` — 早晚打卡状态、7 天周历 RecyclerView、工具栏（历史/设置/退出）。`SecurityManager.isLoggedIn()` 守卫
- `CameraActivity` — CameraX 拍照，保存到 `filesDir/pillsguard_photos/`。拍照前检测重复打卡。离线模式也保存照片并排队上传
- `HistoryActivity` — 30 天打卡记录列表
- `SettingsActivity` — 提醒方式开关、系统权限引导（电池优化 + 精准闹钟）、**当前状态**（在线模式显示服务器地址+连接状态圆点+测试连接按钮；离线模式显示"离线登录"）、测试提醒、清除历史
- `DevModeActivity` — 开发者调试页面，显示设备信息、登录状态、数据库统计、网络状态、服务器连接测试、SharedPreferences 内容

### 服务器端 — Flask + Gunicorn + Nginx + SQLite

**Nginx 路由架构**（生产环境）：
- Nginx 同时监听 80（HTTP）和 443（HTTPS，Let's Encrypt 证书，域名 `yellowduck.top`）
- `location /api/` → `127.0.0.1:5000`（gunicorn / Flask API）
- `location /` → `127.0.0.1:8080`（Node.js 前端服务）
- HTTP 下 `/api/` **不强制跳转 HTTPS**，确保 Android 客户端 IP 直连不受 SSL 主机名限制

单个 Flask 应用（`server/app.py`），端点：
- `POST /api/auth/login`、`/api/auth/register`、`/api/auth/refresh` — JWT 认证
- `POST /api/upload/photo` — multipart 照片上传；重复打卡时后台线程发送即时告警邮件，**发送后立即删除该照片**
- `GET /api/records` — 按 userId + 日期范围查询
- `GET /api/photos/<filename>` — 照片文件访问
- `GET /api/health` — 健康检查

数据库：SQLite，路径 `/var/pillguard/pillguard.db`。两张表：`users` 和 `medication_records`。

辅助脚本：
- `deploy.sh` — 全自动部署：系统依赖、Python venv、JWT 密钥+密码盐值、systemd 服务（gunicorn :5000）、root crontab（每日 22:00 邮件）
- `daily_email.py` — **关键设计**：`get_service_env()` 通过 `systemctl show pillguard --property=Environment` 从 systemd 读取 SMTP 配置，解决 cron 环境下无环境变量的问题。查询 `emailed_at IS NULL` 获取所有**未通过邮件发送的记录**（不限日期），按用户分别发送 HTML 报告+照片附件。发送成功后标记 `emailed_at` 并删除照片文件。部分失败则不标记不删照片，下次重试。首次运行自动迁移 DB 添加 `emailed_at` 列。使用 `formataddr()` 编码中文发件人名避免 QQ 邮箱 550 拒绝
- `test_email.py` — SMTP 邮件测试，同样用 `get_service_env()` 读配置。部署后：`sudo /var/pillguard/venv/bin/python3 /opt/pillguard/server/test_email.py`
- `create_user.py` / `manage_user.py` — 用户管理

**服务器照片生命周期**：
- 正常打卡照片：上传 → 保留到 22:00 → `daily_email.py` 作为邮件附件发送 → 标记 `emailed_at` → 删除照片
- 重复打卡照片：上传 → `send_duplicate_alert_email()` 立即发邮件 → **立即删除**（不等到 22:00）
- 存储路径：`/var/pillguard/uploads/{user_id}_{date}_{timeSlot}_{uuid}.jpg`

### Release 签名

`local.properties`（已加入 `.gitignore`）包含 `pillguard-release.jks` 密钥库的 `RELEASE_STORE_PASSWORD`、`RELEASE_KEY_ALIAS`、`RELEASE_KEY_PASSWORD`。

### 已知注意事项

- **Release 禁止 R8 混淆**：`minifyEnabled true` 会导致登录功能故障，保持 `false`
- **APP 安装后大小 ~55MB 是正常 Android 开销**：APK 6.7MB，安装后 ART 编译 DEX→机器码（20-40MB code_cache），非存储泄漏
- SMTP 配置存储在 systemd 环境变量中，cron 脚本需通过 `get_service_env()` 读取
- Nginx 配置位于 `/etc/nginx/sites-available/yellowduck`（不是 sites-available/pillguard）
- Let's Encrypt 证书域名 `yellowduck.top`，Android 客户端用 `http://47.106.163.25` 直连可绕过 SSL 主机名验证
- `daily_email.py` 通过 `emailed_at` 追踪已发送记录，不会重复发送；未发送记录（含历史遗留）会在下次执行时一并发出
- PillGuard systemd 服务 Type=simple，修改 app.py 后需 `sudo systemctl restart pillguard`（不支持 reload）
- `network_security_config.xml` 中同时配置了 `47.106.163.25` 和 `yellowduck.top` 的 cleartext 允许
- 照片迁移标记存储在 `pillguard_migration` SharedPreferences 的 `migrated_to_v2` 键，设为 false 可重新触发旧照片清理
