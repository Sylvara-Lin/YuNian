# build_agent.ps1 — Agent 原生库构建脚本（P3-10）
# 用法：
#   powershell -File scripts/build_agent.ps1            # 测试 + 交叉编译 4 ABI
#   powershell -File scripts/build_agent.ps1 -SkipTest  # 跳过宿主编译/单测
#   powershell -File scripts/build_agent.ps1 -GenBindings  # 额外重新生成 UniFFI Kotlin 绑定
# 输出：core/agent/src/main/jniLibs/{abi}/liblianyu_agent.so（提交入库的生成物）
param(
    [switch]$SkipTest,
    [switch]$GenBindings
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
$AgentNative = Join-Path $Root "agent-native"
$JniLibs = Join-Path $Root "core/agent/src/main/jniLibs"

# 1. NDK 定位（cargo-ndk 需要）
$ndk = $env:ANDROID_NDK_HOME
if (-not $ndk -or -not (Test-Path $ndk)) {
    $ndk = Join-Path $env:LOCALAPPDATA "Android/Sdk/ndk/30.0.14904198"
}
if (-not (Test-Path $ndk)) { throw "NDK 未找到：请设置 ANDROID_NDK_HOME（需要 30.0.14904198）" }
$env:ANDROID_NDK_HOME = $ndk

Write-Host "[1/3] NDK = $ndk"

# 2. 宿主编译 + 单测（native_gateway 注入 mock 传输；含世界书注入 Q2 用例）
Push-Location $AgentNative
try {
    if (-not $SkipTest) {
        Write-Host "[2/3] cargo test ..."
        cargo test
        if ($LASTEXITCODE -ne 0) { throw "cargo test 失败" }
    }
    else {
        Write-Host "[2/3] 跳过 cargo test（-SkipTest）"
    }

    # 3. 交叉编译 4 ABI → jniLibs
    Write-Host "[3/3] cargo ndk build --release (4 ABI) ..."
    cargo ndk -t arm64-v8a -t armeabi-v7a -t x86_64 -t x86 -o $JniLibs build --release
    if ($LASTEXITCODE -ne 0) { throw "cargo ndk 失败" }

    if ($GenBindings) {
        Write-Host "[+] uniffi-bindgen 重新生成 Kotlin 绑定 ..."
        # ⚠️ 注意：PowerShell 不支持 bash 风格的 `\` 续行（会原样当参数传给 cargo 导致失败），
        # 故此处必须写成单行；参数用数组拼接保持可读性。
        $bindgenArgs = @(
            "run", "--features", "cli-bin", "--bin", "uniffi-bindgen", "--",
            "generate",
            "--library", "target/aarch64-linux-android/release/liblianyu_agent.so",
            "--language", "kotlin",
            "--out-dir", (Join-Path $Root "core/agent/src/main/kotlin"),
            "--no-format"
        )
        & cargo @bindgenArgs
        if ($LASTEXITCODE -ne 0) { throw "uniffi-bindgen 失败" }
        Write-Host "[+] 绑定输出为 LF（与仓库既有 lianyu_agent.kt 一致，勿转 CRLF）"
    }
}
finally {
    Pop-Location
}

Write-Host "完成：jniLibs 已更新（若改了 UniFFI 接口记得加 -GenBindings）"
