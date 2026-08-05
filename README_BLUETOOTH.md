# SWR005 蓝牙通信完整协议文档

## 概述

SWR005 是一款激光测距仪设备（对外名称 "Mate X"），通过蓝牙低功耗（BLE）与手机通信。本文档详细描述了设备发现、连接、特征值选择、AT 命令协议、数据解析等完整流程，可供其他项目参考实现。

---

## 1. 设备发现与过滤

### 扫描规则

```
扫描方式: BLE 扫描（Flutter: flutter_blue_plus / iOS: CoreBluetooth / Android: BluetoothLeScanner）
超时时间: 10 秒
过滤条件: 设备广播名称以 "Mate X" 开头
```

### 过滤逻辑

```
function isTargetDevice(scanResult):
    name = scanResult.advertisementData.advName
    return name is not empty AND name.startsWith("Mate X")
```

### 注意事项
- 设备必须处于可被发现状态（开机、未休眠）
- 扫描结果需要持续监听，设备可能出现较晚
- iOS 需要位置权限才能进行 BLE 扫描

---

## 2. 连接流程

### 连接步骤

```
1. 停止扫描
2. 连接设备（device.connect()）
3. 发现服务（device.discoverServices()）
4. 找到写入特征值（见第3节）
5. 找到通知特征值（见第4节）
6. 订阅通知（setNotifyValue(true) → 必须 await）
7. 连接完成
```

### 关键点
- **连接前必须先停止扫描**
- **setNotifyValue(true) 必须 await**：需要与设备完成 GATT 描述符写入交互，不 await 的话通知通道未建立，所有设备响应都会丢失
- 连接后需要监听断开事件，自动清理状态

---

## 3. 写入特征值选择

### SWR005 特征值结构

SWR005 设备有多个服务和特征值，AT 命令的写入特征值是 **最后一个具有 `.write` 属性的特征值**（UUID 通常是 `fff2`）。

### 选择算法

```
function findWriteCharacteristic(services):
    lastWrite = null
    for each service in services:
        for each characteristic in service.characteristics:
            if characteristic.properties.write == true:
                lastWrite = characteristic
    return lastWrite
```

### 重要：不要使用 writeWithoutResponse 优先选择
- SWR005 的写入特征值声明了 `write` 属性，但实际可能只支持 `writeWithoutResponse`
- 正确做法：**先尝试 write-with-response，失败后降级到 write-without-response**（见第6节）

### 调试日志
连接后应打印所有特征值及其属性，方便排查：
```
for each service:
    for each characteristic:
        props = []
        if characteristic.write: props.add("write")
        if characteristic.writeWithoutResponse: props.add("writeNoRsp")
        if characteristic.notify: props.add("notify")
        if characteristic.read: props.add("read")
        print("BLE char: ${characteristic.uuid} [${props.join(",")}]")
```

---

## 4. 通知特征值订阅

### 订阅逻辑

```
function findNotifyCharacteristics(services):
    result = []
    for each service in services:
        for each characteristic in service.characteristics:
            if characteristic.properties.notify == true:
                result.add(characteristic)
    return result
```

### 订阅步骤

```
for each notifyChar in notifyCharacteristics:
    await notifyChar.setNotifyValue(true)  // 必须 await！
    notifyChar.onValueReceived.listen(handleData)
```

### 数据接收
- 所有通知数据通过 `onValueReceived` 回调接收
- 数据类型为 `List<int>`，需要转换为字符串：`String.fromCharCodes(data)`

---

## 5. AT 命令协议

### 通用规则
- 所有命令以 `##` 结尾
- 数据按字符串传输，区分大小写
- 命令通过写入特征值发送，响应通过通知特征值接收

### 命令列表

| 命令 | 功能 | 说明 |
|------|------|------|
| `AT+Calibration=0##` | 打开绿光激光 | 开启绿光指示 |
| `AT+Calibration=1##` | 关闭绿光激光 | 关闭绿光指示 |
| `AT+Measure##` | 开始测距 | 按下测距按钮时发送，设备开始持续上报距离数据 |
| `AT+Stop##` | 停止测距 | 松开测距按钮时发送，设备停止上报 |
| `AT+Batt##` | 查询电池电量 | 设备返回电池等级 |
| `AT+Info##` | 查询设备信息 | 设备返回设备信息 |
| `AT+WAKE##` | 唤醒设备 | 唤醒休眠状态的设备 |
| `AT+CAL=1,0##` | 角度校准 | 角度校准命令 |
| `AT+CAL=1,3##` | 88.4m 校准 | 88.4米距离校准命令 |
| `AT+CAL=1,14##` | 方位角校准 | 方位角校准命令 |

### 命令发送方式

```
function sendCommand(command):
    bytes = command.toByteArray()  // UTF-8 编码

    // 先尝试 write-with-response
    try:
        writeCharacteristic.write(bytes, withoutResponse: false)
    catch:
        // 降级到 write-without-response
        writeCharacteristic.write(bytes, withoutResponse: true)
```

---

## 6. 响应格式与解析

### 6.1 测距数据

**格式**: `AT+Distance=<Dis0>,<Dis1>,<Dis2>,<Flag>##`

| 字段 | 含义 | 单位 | 说明 |
|------|------|------|------|
| Dis0 | 直线距离 | 米 (m) | 主测量值 |
| Dis1 | 补偿距离 | 米 (m) | 水平补偿距离，可能为空 |
| Dis2 | 高度 | 米 (m) | 垂直高度，可能为空 |
| Flag | 标志位 | - | 0=正常, 1=旗杆锁定 |

**解析代码**:
```
function parseDistanceData(response):
    if not response.startsWith("AT+Distance="):
        return null

    content = response.remove("AT+Distance=").remove("##")
    parts = content.split(",")

    if parts.length >= 4:
        return DistanceData(
            line: parseFloat(parts[0]),    // 直线距离
            comp: parseFloat(parts[1]),    // 补偿距离
            height: parseFloat(parts[2]),  // 高度
            flag: parseInt(parts[3]),      // 标志位
        )
```

**显示规则**:
- 距离 < 200m：保留两位小数（如 "12.34 m"）
- 距离 ≥ 200m：不保留小数（如 "350 m"）
- 测距过程中设备每 300ms 上报一次数据
- 测距失败时返回 `NG+Distance##`

### 6.2 电池电量

**格式**: `AT+Batt=<level>##`

- level: 0-5（电池等级）

### 6.3 设备信息

**格式**: 设备返回信息字符串

### 6.4 AT+OK 响应

- 部分命令（如绿光开关）设备会返回 `AT+OK##` 确认
- 此响应不需要特殊处理，仅记录日志

### 6.5 校准响应

**88.4m 校准失败**: 设备返回包含 `CAL+884=High level` 的字符串，表示校准失败（水平超限）

---

## 7. 测距按钮交互规范

### 按下-松开模式

测距按钮使用**按下开始、松开停止**的交互模式：

```
onPointerDown:
    sendCommand("AT+Measure##")

onPointerUp:
    sendCommand("AT+Stop##")
```

### 最短持续时间

- 测距最短持续 0.5 秒
- 如果用户在 0.5 秒内松开，延迟到 0.5 秒时再发送 `AT+Stop##`

```
onPointerUp:
    elapsed = now - measureStartTime
    if elapsed < 500ms:
        timer.schedule(500ms - elapsed, () => sendCommand("AT+Stop##"))
    else:
        sendCommand("AT+Stop##")
```

### 手势处理
- **必须使用 `Listener`（原始指针事件）**，不要使用 `GestureDetector`
- 原因：`GestureDetector` 的 tap 手势识别器在长按场景下会提前取消，导致松开时无法触发 `onTapUp`

---

## 8. 平台权限配置

### Android (AndroidManifest.xml)

```xml
<uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30"/>
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" android:maxSdkVersion="30"/>
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" android:usesPermissionFlags="neverForLocation"/>
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT"/>
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION"/>
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION"/>
```

### iOS (Info.plist)

```xml
<key>NSBluetoothAlwaysUsageDescription</key>
<string>需要使用蓝牙连接校准设备</string>
<key>NSBluetoothPeripheralUsageDescription</key>
<string>需要使用蓝牙连接校准设备</string>
<key>NSLocationWhenInUseUsageDescription</key>
<string>蓝牙扫描需要位置权限</string>
```

---

## 9. 错误处理

### GATT 写入错误

SWR005 设备的特征值可能返回 `GATT_INVALID_ATTRIBUTE_LENGTH` 错误（android-code: 13）。解决方案：

```
function sendCommand(command):
    try:
        write(withoutResponse: false)  // 先尝试 with-response
    catch GATT error:
        write(withoutResponse: true)   // 降级到 without-response
```

### 连接断开

- 监听设备断开事件
- 断开后清除写入特征值引用
- 断开后取消通知订阅
- 更新 UI 状态

### 命令发送失败

- 发送命令前记录 TX 日志（即使发送失败也有日志）
- 捕获异常，恢复按钮状态，避免 UI 卡住

---

## 10. 数据流总结

```
[手机App]
    │
    │ AT+Measure## (写入特征值)
    ▼
[SWR005 设备]
    │
    │ AT+Distance=12.34,11.50,4.20,0## (通知特征值)
    ▼
[手机App]
    │
    │ 解析 → DistanceData(line=12.34, comp=11.50, height=4.20, flag=0)
    ▼
[UI 显示]
```

---

## 11. 完整连接时序图

```
App                    SWR005
 │                       │
 │──── BLE Scan ────────>│
 │<─── Advertisement ────│  (Name: "Mate X")
 │                       │
 │──── Connect ─────────>│
 │<─── Connected ────────│
 │                       │
 │──── Discover Services>│
 │<─── Services ─────────│  (包含 write/notify 特征值)
 │                       │
 │──── setNotify(true) ─>│  (必须 await)
 │<─── Notify Ready ─────│
 │                       │
 │──── AT+Measure## ────>│  (写入特征值)
 │<─── AT+Distance=... ──│  (每300ms上报)
 │<─── AT+Distance=... ──│
 │<─── AT+Distance=... ──│
 │                       │
 │──── AT+Stop## ───────>│
 │<─── OK ───────────────│
 │                       │
 │──── Disconnect ──────>│
```

---

## 12. 依赖库

| 平台 | 库 | 版本 | 说明 |
|------|-----|------|------|
| Flutter | flutter_blue_plus | 1.35.2 | 跨平台 BLE 库 |
| iOS 原生 | CoreBluetooth | 系统框架 | Apple BLE 框架 |
| Android 原生 | android.bluetooth | 系统框架 | Android BLE 框架 |
