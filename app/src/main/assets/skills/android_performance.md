---
name: android_performance
description: Android 性能优化技能 - 覆盖启动、渲染、内存、电量、网络等维度
version: "1.0"
author: YuNian Team
tags: [android, performance, optimization, startup, memory, battery]
requires_confirmation: false
---

# Android 性能优化技能

## 核心指标

### 启动性能
- **TTID (Time To Initial Display)**: 冷启动 < 400ms，热启动 < 200ms
- **TTFD (Time To Full Display)**: 完全可交互 < 1.5s
- **关键路径**: Application.onCreate → Activity.onCreate → 首帧绘制

### 渲染性能
- **帧率**: 60fps (16.67ms/帧)，120fps 设备 8.33ms/帧
- **Jank**: 丢帧 < 5%，严重卡顿 < 1%
- **过度绘制**: < 2.5x，GPU 过度绘制可视化检查

### 内存
- **Java Heap**: < 192MB (低端机) / < 512MB (高端机)
- **Native 内存**: 图片、Bitmap、GL 纹理
- **LeakCanary**: 0 活动泄漏，定期巡检

### 电量
- **WakeLock**: 最小化持有时间，使用 WorkManager 替代
- **网络**: 批量请求、压缩、缓存策略
- **定位**: 被动定位优先、精度按需

### 网络
- **连接复用**: HTTP/2、Keep-Alive、连接池
- **请求合并**: 批量 API、GraphQL 按需查询
- **离线优先**: 本地缓存、增量同步、冲突解决

## 优化工具链

### 分析工具
- **Perfetto / Systrace**: 系统级追踪、CPU 调度、锁竞争
- **Android Profiler**: CPU、内存、网络、能耗实时监控
- **Layout Inspector**: 视图层级、过度绘制、重绘区域
- **LeakCanary**: 内存泄漏自动检测
- **Macrobenchmark**: 启动、滚动、动画基准测试

### 编译期优化
- **R8 全量模式**: 代码压缩、混淆、优化
- **Baseline Profiles**: 启动热点预编译、PGO
- **App Bundle**: 按需分发、动态特性模块

## 常见优化模式

### 启动优化
```kotlin
// ❌ 错误：主线程做 IO
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Database.init(this)  // 阻塞主线程！
        Analytics.init(this)
    }
}

// ✅ 正确：后台初始化 + 惰性加载
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // 仅注册 ContentProvider，真正初始化延迟到首次使用
        StartupLogger.log("Application created")
    }
}

// ContentProvider 方式（更早初始化，但需极快）
class InitProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        // 仅做极轻量初始化（< 10ms）
        return true
    }
}
```

### 列表渲染优化
```kotlin
// ✅ 使用稳定 ID、DiffUtil、ViewType
adapter = PagingDataAdapter(diffCallback) { ... }
recyclerView.setHasFixedSize(true)
recyclerView.itemAnimator = null  // 禁用默认动画

// ✅ 图片预加载 + 占位图
CoilImage(
    data = url,
    placeholder = painterResource(R.drawable.placeholder),
    error = painterResource(R.drawable.error),
    crossfade = true
)
```

### 内存优化
```kotlin
// ✅ 大图分片加载、复用 Bitmap
val options = BitmapFactory.Options().apply {
    inMutable = true
    inBitmap = reusableBitmap  // API 19+
}

// ✅ 生命周期感知
lifecycleScope.launchWhenStarted {
    // 仅在可见时加载
}
```

## 性能预算表

| 指标 | 预算 | 报警阈值 |
|-----|------|---------|
| 冷启动 TTID | < 400ms | > 800ms |
| 热启动 TTID | < 200ms | > 400ms |
| 列表滚动帧率 | 60fps | < 55fps |
| Java Heap 峰值 | < 256MB | > 384MB |
| ANR 率 | < 0.1% | > 0.5% |
| 崩溃率 | < 0.5% | > 1% |

## 排查清单

```
启动慢：
[ ] Application.onCreate 耗时分析
[ ] ContentProvider 初始化耗时
[ ] 首屏布局复杂度、嵌套深度
[ ] 网络请求是否阻塞首屏
[ ] Baseline Profile 是否生效

卡顿：
[ ] Systrace 分析主线程 16ms 超时
[ ] RecyclerView 预取、预加载
[ ] 复杂布局 flatten、ViewStub
[ ] 协程 Dispatchers.Default 切换
[ ] 数据库事务、大查询是否在 IO 线程

内存增长：
[ ] LeakCanary 报告分析
[ ] 图片加载库缓存配置
[ ] 监听器/回调是否及时移除
[ ] 静态集合持有大对象
[ ] Native 内存 (Bitmap/GL) 释放
```