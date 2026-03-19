# ChipletRing Android 语音转写开发记录

## 1. 本次目标

- 录音结束后，把导出的音频文件分别发送给 OpenAI 和 Gemini 的语音转文字模型
- 将转写文本直接展示在录音页面
- 调整录音页布局，突出录音、转写结果和日志

## 2. 当前已完成

### 2.1 页面与交互

- 已重做 `TestActivity` 页面
- 页面现在分为：
  - 录音控制区
  - OpenAI 转写结果区
  - Gemini 转写结果区
  - 日志区
  - 原有调试按钮区
- 录音流程：
  - 点击“开始录音”
  - 点击“停止并转写”
  - 自动导出 WAV
  - 自动并行触发 OpenAI 和 Gemini 转写
  - 页面展示结果或错误信息

### 2.2 音频链路

- 录音数据仍然来自戒指 SDK 回调，不是手机麦克风
- 当前录音导出链路可正常工作：
  - 原始 `.adpcm`
  - 中间 `.pcm`
  - 导出 `.wav`
  - 预处理试听版 `_skip1.wav`
- 试听功能可正常播放最新录音

### 2.3 OpenAI 转写

- 已接入官方音频转写接口：
  - `POST https://api.openai.com/v1/audio/transcriptions`
- 当前代码使用 multipart 上传音频文件
- 模型从本地配置读取：
  - `OPENAI_TRANSCRIBE_MODEL`
- 当前已验证：
  - 接口路径和调用方式是对的
  - key 无效问题已排除
  - 现在真实返回是 `insufficient_quota`

### 2.4 Gemini 转写

- 已接入 Gemini 文件上传 + `generateContent`
- 当前模型从本地配置读取：
  - `GEMINI_TRANSCRIBE_MODEL`
- 已将模型改为：
  - `gemini-2.5-flash`
- 本地真实测试成功
- 本地 WAV 测试返回文本：
  - `哈喽哈喽你好你好`

## 3. 当前配置方式

配置文件：

- `example/ringDemo/local.properties`

当前需要的字段：

```properties
OPENAI_API_KEY=你的_openai_api_key
OPENAI_TRANSCRIBE_MODEL=gpt-4o-transcribe
GEMINI_API_KEY=你的_gemini_api_key
GEMINI_TRANSCRIBE_MODEL=gemini-2.5-flash
```

说明：

- 这些值会在编译时写入 `BuildConfig`
- 改完 `local.properties` 后，必须重新编译并安装 app

## 4. 关键文件

- `example/ringDemo/app/src/main/java/com/lomo/demo/activity/TestActivity.java`
- `example/ringDemo/app/src/main/java/com/lomo/demo/audio/AudioCaptureSession.java`
- `example/ringDemo/app/src/main/java/com/lomo/demo/audio/SpeechTranscriptionClient.java`
- `example/ringDemo/app/src/main/res/layout/activity_test.xml`
- `example/ringDemo/app/build.gradle`
- `example/ringDemo/local.properties`

## 5. 当前已确认的问题

### 5.1 本地电脑测试

- Gemini 可成功转写
- OpenAI 当前报错：
  - `insufficient_quota`

说明：

- OpenAI 代码链路是通的
- 当前阻塞点是 API 账户额度，不是代码格式问题

### 5.2 真机测试

- 真机录音导出正常
- 真机试听正常
- 真机调用 OpenAI / Gemini 时都出现 TLS 握手失败

已抓到的关键错误：

- `javax.net.ssl.SSLHandshakeException: connection closed`
- `java.io.EOFException: connection closed`

说明：

- 这不是音频没录到
- 更像是手机当前网络环境拦截或中断了 HTTPS 连接
- 可能与 Wi-Fi、代理、VPN、证书中间件或网络出口有关

## 6. 下次继续开发时优先做什么

### P0

- 在真机上切换网络再测：
  - 优先用手机 4G/5G
  - 关闭代理 / VPN / 抓包证书
- 重新验证 Gemini 在真机是否能成功
- 确认 OpenAI 账户额度是否恢复

### P1

- 把页面里的失败信息显示得更完整
- 不只打 `logcat`，也把异常文本直接显示到 UI

### P2

- 增加转写历史保存
- 支持记录每次录音文件路径、OpenAI 文本、Gemini 文本、失败原因

## 7. 调试命令

### 7.1 编译并安装

```powershell
cd E:\ChipletRing-APPSDK\ChipletRing-APPSDK-main\Android\example\ringDemo
./gradlew :app:installDebug
```

### 7.2 查看设备

```powershell
D:\Android\Sdk\platform-tools\adb.exe devices
```

### 7.3 抓取当前 app 日志

```powershell
$appPid = D:\Android\Sdk\platform-tools\adb.exe shell pidof -s com.lomo.demo
D:\Android\Sdk\platform-tools\adb.exe logcat -d --pid $appPid
```

### 7.4 重点日志关键字

- `TestActivityUI`
- `OpenAI transcription failed`
- `Gemini transcription failed`
- `SSLHandshakeException`
- `AudioCaptureSession`

## 8. 一句话结论

当前版本已经完成“录音导出 + 双模型转写接入 + 页面展示”，本地电脑下 Gemini 可用、OpenAI 卡在额度，真机当前主要问题是 HTTPS/TLS 握手失败，需要先排查手机网络环境。

## 9. 2026-03-19 最新进展

### 9.1 配置变更

- 用户已更换 `OPENAI_API_KEY`
- 当前 `example/ringDemo/local.properties` 中已配置：
  - `OPENAI_API_KEY`
  - `OPENAI_TRANSCRIBE_MODEL=gpt-4o-transcribe`
  - `GEMINI_API_KEY`
  - `GEMINI_TRANSCRIBE_MODEL=gemini-2.5-flash`
- 注意：
  - 如果旧 OpenAI key 曾暴露，建议直接去 OpenAI 平台后台撤销旧 key，不要只做本地覆盖
  - 修改 `local.properties` 后，必须重新编译安装，才能让新的 `BuildConfig` 生效

### 9.2 本机联调结果

- 已补充本地单测依赖：
  - `example/ringDemo/app/build.gradle`
  - 新增 `testImplementation 'junit:junit:4.13.2'`
- 已新增本地手动验证测试：
  - `example/ringDemo/app/src/test/java/com/lomo/demo/audio/SpeechTranscriptionClientManualTest.java`
- 测试使用的本地样本文件：
  - `artifacts/ring_audio_2026-03-18_16-12-00.wav`
  - `artifacts/ring_audio_2026-03-18_16-20-46.wav`
- 本机执行结果：
  - OpenAI 本地单测已跑到真实网络请求阶段
  - OpenAI 当前结果不是编译错误，也不是本地文件错误，而是 `SocketTimeoutException`
  - Gemini 本地单测未拿到明确成功结果，本次未完成最终确认
- 结论：
  - 当前不能仅凭“更换了 OpenAI key”就判断真机 Gemini 问题已解决
  - Gemini 真机失败仍应优先按网络出口 / TLS 方向排查

### 9.3 真机安装结果

- 已执行：
  - `./gradlew :app:installDebug`
- 安装结果：
  - 安装成功
  - 目标设备：`22122RK93C`
  - 安装包：`ChipletRingDemo_2.0.5_debug_20260319111307.apk`

### 9.4 当前判断

- 真机 Gemini 转写失败的高优先级怀疑点仍然是网络环境
- 原因：
  - 历史真机错误是 `SSLHandshakeException: connection closed`
  - 以及 `EOFException: connection closed`
  - 这类错误更像 HTTPS 连接在握手阶段被中断
- 目前没有证据表明：
  - 音频导出链路有问题
  - Android 权限缺失
  - Gemini 请求代码路径有明显错误

### 9.5 下一步调试动作

- 在真机上重新复现一次录音并触发 Gemini 转写
- 复现完成后，使用 `adb logcat` 抓以下关键字：
  - `TestActivityUI`
  - `Gemini transcription failed`
  - `OpenAI transcription failed`
  - `SSLHandshakeException`
  - `EOFException`
- 目标是确认本次失败究竟属于：
  - 网络出口不可达
  - TLS 握手被中断
  - HTTP 认证失败
  - 配额或接口返回错误
