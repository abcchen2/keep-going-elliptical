# keep-going-elliptical（椭圆机续命）

莫比 (Mobi) 已经倒闭，服务器关停后官方 App 无法登录，椭圆机也就无法调阻力。这个 Android App 不需要登录，直接用蓝牙连接椭圆机，按官方 App 的协议控制阻力。

协议是从官方 App 3.5.9.1 和 4.5.17 反编译分析出来的，细节见 [docs/PROTOCOL.md](docs/PROTOCOL.md)。

[English README](README.md) · 本项目参加 Kiroween 2026（Resurrection 类别），使用 MIT 许可证。

## 支持的设备

自动识别四种协议，识别顺序与官方相同：

| 协议 | 特征 | 阻力控制方式 |
|---|---|---|
| 莫比 2.0 | 服务 0x8800 | 先写 88FF 解锁 `11 82 07`，再写 880F `02 位数 档位` |
| 莫比 1.0 | 服务 FFE0，0xAB 帧 | 写 FFE3 `AB 03 00 TT SS 档位 xx`，TT/SS/xx 从机器上报帧拷贝 |
| 环通 | 服务 FFF0，名字 `MOBI-E…` | 每秒写一次 FFF2 `20 C1 档位 00 校验` |
| FTMS | 服务 0x1826 | 写 2AD9 `04 档位×10`（1 字节） |

## 使用

1. 椭圆机通电，踩几圈唤醒蓝牙盒子。关掉官方 App 和其他可能连着椭圆机的 App。
2. 打开本 App，点「扫描设备」。名字以 MOBI / MB- 开头的设备会高亮显示，点它连接。
3. 连接后用 + / − / 滑条 / 快捷档位调阻力。“机器反馈”是机器回报的当前档位（环通主板不回报，显示的是 App 正在保持的档位）。
4. 2.0 和 FTMS 机型如果调了没反应，先在“本次锻炼”里点「开始」（会同时给机器发开始指令）。
5. “本次锻炼”显示用时、里程、热量、心率、踏频、速度，以及平均踏频、平均阻力、平均/最高心率。开始踩动自动开始计时，停止踩动 60 秒后自动暂停（可在设置里修改）。点“结束”后保存为一条锻炼记录（30 秒以上才保存）。
   - 里程和热量优先用机器上报的值，机器不报时按官方公式计算。卡片下方会注明数据来源。
   - “消耗 kcal/分”可以用来检查热量是否合理：正常大约 3–15，上限 20。觉得偏高或偏低，可以在设置里换算法或调热量系数。算法细节见 [docs/PROTOCOL.md 第 7 节](docs/PROTOCOL.md)。

6. 没有椭圆机时，可以在连接页点「演示模式 Demo（无需设备）」，用模拟椭圆机体验全部功能，不需要蓝牙权限。

运动时屏幕保持常亮。环通主板要求 App 每秒发送一次档位，请让 App 保持在前台。

### 调不动时

- 展开「日志与调试」，看协议识别结果，以及 `WRITE … [..]` 有没有“写入失败”。
- 在「设置」里强制指定协议，例如私有协议和 FTMS 都试一下。
- FTMS 机型可以在设置里换“阻力编码”。
- 「发送原始指令」可以直接往任意特征写十六进制，方便自己测试。
- 「复制日志」可以把完整日志贴给别人分析。

## 编译

需要 JDK 17 和 Android SDK 34。

```powershell
# Windows
$env:JAVA_HOME="C:\Program Files\jdk17"
.\gradlew.bat assembleDebug
```

```bash
# macOS / Linux
./gradlew assembleDebug
```

输出文件：`app/build/outputs/apk/debug/app-debug.apk`。也可以直接用 Android Studio 打开本目录。

最低支持 Android 8.0 (API 26)。Android 11 及以下扫描蓝牙需要定位权限，并且要打开系统定位开关。

## 代码结构

```
app/src/main/java/com/keepgoing/elliptical/
├── EllipticalController.kt   扫描、连接、重连、协议选择、阻力指令合并
├── ble/
│   ├── GattLink.kt           串行 GATT 队列（一次只执行一个操作，带超时）
│   ├── BleUuids.kt           UUID 常量
│   └── Bytes.kt              字节工具
├── protocol/
│   ├── ProtocolDetector.kt   协议识别（与官方顺序一致）
│   ├── V2Protocol.kt         莫比 2.0
│   ├── V1Protocol.kt         莫比 1.0
│   ├── HuanTongProtocol.kt   环通（1Hz 心跳）
│   ├── FtmsProtocol.kt       FTMS 莫比变体
│   ├── DemoMachine.kt        演示模式（模拟椭圆机）
│   └── ResistanceTables.kt   官方型号 → 阻力范围表
├── workout/                  锻炼统计（1Hz 累加）、官方热量公式
├── model/                    状态与设置
└── ui/                       Compose 界面
.kiro/                        Kiro 的 specs、steering、hooks
```

## 声明

本项目只用于让用户继续使用自己已经购买的设备，与莫比官方无关。协议信息来自对官方 App 的互操作性分析，仓库里不包含官方代码或资源，反编译结果没有公开。

许可证：[MIT](LICENSE)。
