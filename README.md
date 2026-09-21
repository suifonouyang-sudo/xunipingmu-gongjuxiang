# 虚拟屏工具箱

> 仓库名 `xunipingmu-gongjuxiang` 是「虚拟屏幕工具箱」的拼音。GitHub 的仓库名字段只接受
> ASCII 字母/数字/`.`/`-`/`_`，中文会被直接规整掉，所以仓库名只能用拼音，项目正式名称仍是中文。

一台 Android 设备上把「虚拟屏（Virtual Display）」跑起来并管好它的小工具箱：基于
[Shizuku](https://shizuku.rikka.app/) 拿到 adb/shell 级权限执行系统命令，覆盖从授权检查、
虚拟屏创建、分辨率/DPI/旋转控制、无线调试端口固化，到 APK 扫描安装、应用备份恢复的完整链路。
没有 Shizuku 时也能跑「授权检查」和部分只读功能；有 root 则解锁应用数据备份等高级能力。

> 全部功能都是对 Android 系统自带命令（`cmd display`、`cmd window`、`pm`、`wm`、`setprop` …）的
> 编排，不注入、不 hook、不修改系统分区。

---

## 功能一览

App 共 8 个页面：

| 页面 | 作用 |
| --- | --- |
| **授权检查** | 逐项检测 Shizuku 是否运行/已授权、root 可用性、悬浮窗权限、电池优化白名单，并直接跳转对应系统设置页修好它 |
| **虚拟屏** | 列出可启动应用，在指定 display 上创建/销毁虚拟屏；支持把某个应用投到虚拟屏启动 |
| **虚拟屏控制** | 对指定 display 改分辨率、改 DPI、横竖屏切换/锁定（版本差异已做兼容，见下文） |
| **端口固定** | 检测当前对外监听端口，一键把无线调试固定到 5555；有厂商持久化开关时直接复用，重启不失效 |
| **应用安装** | 全盘扫描设备上的 APK，勾选后批量安装 |
| **应用备份** | 备份应用的安装包（无需 root）；有 root 时连应用数据一起打包 |
| **应用恢复** | 从备份记录恢复，带 split 分片的应用走 session 方式安装，数据按原 uid 归还 |
| **日志** | 查看运行日志，可导出分享 |

另外有两个后台能力：

- **自动广播地址**：把当前 `ip:端口` 同时通过 UDP 广播和 mDNS(NSD) 发布到局域网，方便电脑端
  或 Tasker/MacroDroid/termux-api 自动拿到连接地址，不用手抄。由周期闹钟驱动，开机自动恢复。
- **开机自启**：`BootReceiver` 在开机后重启地址广播等常驻逻辑。

---

## 直接安装

仓库根目录已附带编译好的安装包，不想自己编译的话下载即可：

- **[虚拟屏工具箱-v1.0-debug.apk](虚拟屏工具箱-v1.0-debug.apk)**（3.4 MB，debug 签名）

安装时需在系统里允许「安装未知来源应用」。装完记得先开 Shizuku，再到 **授权检查** 页逐项授权。

---

## 环境要求

- **设备**：Android 7.0 (API 24) 及以上
- **构建**：JDK 17、Android SDK（compileSdk 35）
- **权限**：
  - [Shizuku](https://shizuku.rikka.app/) —— 必需，用于以 shell(uid 2000) 身份执行系统命令
  - root —— 可选，仅「备份应用数据」和「重启 adbd」等少数场景需要
  - 悬浮窗、电池优化白名单 —— 可选，用于长时任务不被系统回收

---

## 编译

项目自带 Gradle Wrapper，克隆后直接构建：

```bash
git clone https://github.com/suifonouyang-sudo/xunipingmu-gongjuxiang.git
cd xunipingmu-gongjuxiang

# 指向你自己的 SDK（该文件已在 .gitignore 中，不会入库）
echo "sdk.dir=/path/to/Android/Sdk" > local.properties

./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

Windows 下用 `gradlew.bat assembleDebug`。

---

## 使用流程

1. 装好 [Shizuku](https://shizuku.rikka.app/) 并按官方说明启动服务（Android 11+ 可走无线调试启动）。
2. 打开本 App 的 **授权检查** 页，逐项修复：Shizuku 授权、悬浮窗、电池优化。
   全部打勾后其余页面才具备完整能力。
3. 进入 **虚拟屏** 页选应用启动虚拟屏，再在 **虚拟屏控制** 页调整该 display 的分辨率/DPI/旋转。
4. 需要无线调试长期可用时，到 **端口固定** 页一键固定 5555。

---

## 几个踩过的坑（已写进代码注释）

这些是实现中真机实测出来的结论，也是本仓库最有参考价值的部分：

**虚拟屏旋转命令的版本差异**
- Android 12+：`cmd window user-rotation -d N {free|lock X}`，有查询子命令。
- Android 11：命令名是 `cmd window set-user-rotation`，且 `-d` 必须放在模式**之后**——
  `set-user-rotation lock -d 10 1` 只转 display 10；写成 `-d 10 lock 1` 会被当成模式解析而报错。
  读旋转状态则要从 `dumpsys window displays` 里解析 `mUserRotationMode` 与 `mViewports` 的 `orientation=`。
- 代码不假设「旧版一定行」：会先查 `cmd window -h` 的帮助里 `set-user-rotation` 是否带
  `-d DISPLAY_ID`，不确定就返回 false 而不是瞎发命令。

**无线调试为什么重启就失效**

只写 `service.adb.tcp.port` 是运行时属性，重启即丢；`persist.adb.tcp.port` 在多数设备上并不生效。
部分 RK 平台设备（如 `/vendor/etc/init/hw/init.rk30board.rc`）带厂商开关：

```
on property:persist.internet_adb_enable=1
    setprop service.adb.tcp.port 5555
    restart adbd
```

写这个 persist 属性即可**当场生效（无需 root，由 init 帮忙重启 adbd）且跨重启保持**。
注意 `on property:` 只在**值发生变化**时触发，已经是 1 时再写 1 不会重启 adbd。
App 会先探测 init 规则是否存在，有才走高优先分支，没有该规则的设备走原逻辑不受影响。

**副作用提醒**：adbd 重启会连带杀掉由它拉起的 Shizuku server。恢复方式是在 Shizuku App
里点一次「启动」即可，授权不会丢。

**备份的能力边界由系统权限客观划定**
- 安装包 `/data/app/…/base.apk` 权限为 `-rw-r--r--`，uid 2000 可完整读出 → **无需 root 可备份**。
- 应用数据 `/data/data/<pkg>` 权限为 `drwx------` 且属主是应用自身 uid，shell 连 `ls` 都 Permission
  denied → 要备份数据必须 root。
- 恢复顺序有硬约束：先装包再放数据（`/data/data/<pkg>` 由安装过程创建并分配 uid），
  解包后文件属主是 root，必须按 `stat -c %u` 取回 uid 再 `chown -R`，否则应用打不开自己的数据。
- 部分 Android 11 设备被厂商裁掉了 `pm install-multiple`，带 split 的应用只能走
  `pm install-create → install-write → install-commit`。

**顺带一提**：设备上的 `tar` 是 toybox 版本，`--exclude` 匹配的是文件名而非路径，
打包要先 `-C` 到父目录再给相对名。

---

## 目录结构

```
app/src/main/java/com/vscreen/toolbox/
├── MainActivity.java          # 8 页 Tab 容器
├── AuthFragment.java          # 授权检查
├── DisplayFragment.java       # 虚拟屏创建/销毁
├── ControllerFragment.java    # 分辨率 / DPI / 旋转
├── PortFragment.java          # 无线调试端口固定
├── InstallFragment.java       # APK 扫描与批量安装
├── BackupFragment.java        # 应用备份
├── RestoreFragment.java       # 应用恢复
├── LogFragment.java           # 日志
├── Displays.java              # 显示屏信息统一入口（含旋转命令版本兼容）
├── AppBackup.java             # 备份/恢复核心
├── ApkScanner.java            # APK 全盘扫描
├── ShizukuCmd.java            # Shizuku 命令执行封装
├── RootShell.java             # root 命令执行封装
├── AddrBroadcaster.java       # 局域网地址广播（UDP + mDNS）
├── AddrTickReceiver.java      # 周期闹钟落点
├── AddrSelfReceiver.java      # 广播回环自检（须动态注册）
├── BootReceiver.java          # 开机自启
├── LogStore.java / Prefs.java # 日志与偏好存取
```

---

## 免责声明

本项目面向**自己拥有或已获授权**的设备，用于调试、自动化与备份等正当用途。
使用者需自行遵守当地法律法规及设备保修条款；因使用本工具造成的任何后果由使用者自负。
