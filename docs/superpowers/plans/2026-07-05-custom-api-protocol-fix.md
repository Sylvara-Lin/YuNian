# Custom API Protocol Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make custom API configs reliably connect to third-party OpenAI-compatible relays by default while preserving Anthropic-compatible custom endpoints as an advanced option.

**Architecture:** Treat `CUSTOM` as a protocol-selectable provider driven by `ApiConfig.formatHint`. OpenAI-compatible custom configs use `/models` and `/chat/completions`; Anthropic-compatible custom configs use `/messages` and manual model entry. The settings UI must always expose a visible one-click model fetch action for OpenAI-compatible custom configs once URL and key are filled.

**Tech Stack:** Kotlin 2.2, Android Jetpack Compose, Room `ApiConfig`, `SettingsViewModel`, `AiService`, OkHttp.

---

## File Structure

- Modify `feature/settings/src/main/java/com/yunian/ai/feature/settings/ui/viewmodel/SettingsViewModel.kt`
  - Respect `CUSTOM.formatHint` when testing connections.
  - Prevent OpenAI `/models` fetch for custom Anthropic-compatible mode.
  - Improve model fetch state handling so errors are visible and stale results clear correctly.
- Modify `feature/settings/src/main/java/com/yunian/ai/feature/settings/ui/screen/SettingsScreen.kt`
  - Fix the main API edit dialog model area so the fetch button is visible before models exist.
  - Disable/hide model fetch when `CUSTOM.formatHint == "anthropic"` and show manual-model guidance.
- Modify `feature/settings/src/main/java/com/yunian/ai/feature/settings/ui/screen/PetalApiCards.kt`
  - Apply the same fixes to the active Petal API card edit dialog, which is the primary settings UI path.
- Modify `core/network/src/main/java/com/yunian/ai/network/AiService.kt`
  - Use `formatHint` in custom chat/proactive/custom-system dispatch paths, not only in unused `providerFor`.
- Modify `core/network/src/test/java/com/yunian/ai/network/RequestSecurityInterceptorTest.kt`
  - Repair broken signer test stubs so the network test source set can compile.
- Create or modify `core/network/src/test/java/com/yunian/ai/network/CustomApiProtocolDispatchTest.kt`
  - Add focused unit tests for custom protocol decision helpers introduced in `AiService`.

---

### Task 1: Repair Existing Network Unit Test Compilation

**Files:**
- Modify: `core/network/src/test/java/com/yunian/ai/network/RequestSecurityInterceptorTest.kt:18-75`

- [ ] **Step 1: Update signer stubs to return `RequestSignature`**

Replace every test signer that currently returns a raw string with a `RequestSecurityInterceptor.RequestSignature` object.

Use this helper inside `RequestSecurityInterceptorTest` before `private class RecordingChain`:

```kotlin
private fun testSignature(value: String = "abc123") =
    RequestSecurityInterceptor.RequestSignature(
        signature = value,
        keyId = "test-key",
        deviceId = "test-device"
    )
```

Then change these signer lambdas:

```kotlin
signer = RequestSecurityInterceptor.Signer { "abc123" }
```

to:

```kotlin
signer = RequestSecurityInterceptor.Signer { testSignature("abc123") }
```

And change the payload-capturing signer to:

```kotlin
signer = RequestSecurityInterceptor.Signer {
    capturedPayload.add(String(it))
    testSignature("abc123")
}
```

Keep the `null` signer tests unchanged.

- [ ] **Step 2: Fix payload assertions for current v1 signature payload**

`RequestSecurityInterceptor.computeRequestSignature()` now builds an 8-line payload:

```text
v1
METHOD
PATH_WITH_QUERY
BODY_HASH
TIMESTAMP
NONCE
CLIENT_ID
DEVICE_ID
```

Update `intercept_includesQueryStringInSignaturePayload()` assertions to:

```kotlin
val payload = capturedPayload.first().lines()
assertEquals("v1", payload[0])
assertEquals("GET", payload[1])
assertEquals("/chat/completions?prompt=hello&limit=1", payload[2])
assertEquals(8, payload.size)
```

- [ ] **Step 3: Run the repaired test compile**

Run:

```bash
./gradlew :core:network:compileDebugUnitTestKotlin
```

Expected: `BUILD SUCCESSFUL` or a new unrelated compile error. If a new unrelated error appears, stop and report it before touching production code.

- [ ] **Step 4: Commit test repair**

```bash
git add core/network/src/test/java/com/yunian/ai/network/RequestSecurityInterceptorTest.kt
git commit -m "test(network): repair request security interceptor tests"
```

---

### Task 2: Add Protocol Decision Helpers and Tests

**Files:**
- Modify: `core/network/src/main/java/com/yunian/ai/network/AiService.kt`
- Create: `core/network/src/test/java/com/yunian/ai/network/CustomApiProtocolDispatchTest.kt`

- [ ] **Step 1: Add failing tests for custom protocol helpers**

Create `core/network/src/test/java/com/yunian/ai/network/CustomApiProtocolDispatchTest.kt`:

```kotlin
package com.yunian.ai.network

import com.yunian.ai.database.model.ApiConfig
import com.yunian.ai.database.model.ApiProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomApiProtocolDispatchTest {

    @Test
    fun usesAnthropicProtocol_isTrueOnlyForCustomAnthropicHintOrAnthropicProvider() {
        val customAnthropic = ApiConfig(
            provider = ApiProvider.CUSTOM,
            apiKey = "sk-test",
            baseUrl = "https://relay.example.com/v1",
            model = "claude-3-5-sonnet",
            formatHint = "anthropic"
        )
        val customOpenAi = customAnthropic.copy(formatHint = "openai")
        val anthropic = customAnthropic.copy(provider = ApiProvider.ANTHROPIC)

        assertTrue(AiService.usesAnthropicProtocol(customAnthropic))
        assertTrue(AiService.usesAnthropicProtocol(anthropic))
        assertFalse(AiService.usesAnthropicProtocol(customOpenAi))
    }

    @Test
    fun supportsOpenAiModelList_isFalseForCustomAnthropicHint() {
        val customAnthropic = ApiConfig(
            provider = ApiProvider.CUSTOM,
            apiKey = "sk-test",
            baseUrl = "https://relay.example.com/v1",
            model = "claude-3-5-sonnet",
            formatHint = "anthropic"
        )
        val customOpenAi = customAnthropic.copy(formatHint = "openai")
        val openAi = customAnthropic.copy(provider = ApiProvider.OPENAI, formatHint = "openai")

        assertFalse(AiService.supportsOpenAiModelList(customAnthropic))
        assertTrue(AiService.supportsOpenAiModelList(customOpenAi))
        assertTrue(AiService.supportsOpenAiModelList(openAi))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run:

```bash
./gradlew :core:network:testDebugUnitTest --tests "com.yunian.ai.network.CustomApiProtocolDispatchTest"
```

Expected: FAIL because `AiService.usesAnthropicProtocol` and `AiService.supportsOpenAiModelList` do not exist.

- [ ] **Step 3: Implement protocol helpers in `AiService` companion object**

Add these public helper functions inside `AiService.Companion`, near `providerFor(config)`:

```kotlin
fun usesAnthropicProtocol(config: ApiConfig): Boolean {
    return config.provider == ApiProvider.ANTHROPIC ||
        (config.provider == ApiProvider.CUSTOM && config.formatHint == "anthropic")
}

fun supportsOpenAiModelList(config: ApiConfig): Boolean {
    return !usesAnthropicProtocol(config)
}
```

- [ ] **Step 4: Run helper tests again**

Run:

```bash
./gradlew :core:network:testDebugUnitTest --tests "com.yunian.ai.network.CustomApiProtocolDispatchTest"
```

Expected: PASS.

- [ ] **Step 5: Commit helper tests and implementation**

```bash
git add core/network/src/main/java/com/yunian/ai/network/AiService.kt core/network/src/test/java/com/yunian/ai/network/CustomApiProtocolDispatchTest.kt
git commit -m "fix(network): add custom api protocol decision helpers"
```

---

### Task 3: Route Custom Anthropic Configs Through Anthropic Calls

**Files:**
- Modify: `core/network/src/main/java/com/yunian/ai/network/AiService.kt`
- Modify: `feature/settings/src/main/java/com/yunian/ai/feature/settings/ui/viewmodel/SettingsViewModel.kt`

- [ ] **Step 1: Update `SettingsViewModel.testConnection()` dispatch**

In `SettingsViewModel.kt`, replace the `when (currentConfig.provider)` block around the test call with protocol-aware logic:

```kotlin
if (AiService.usesAnthropicProtocol(testConfig)) {
    aiService.callAnthropicForTest(testConfig, testMessages, "Be helpful.")
} else {
    aiService.callOpenAiCompatibleForTest(testConfig, testMessages)
}
```

This keeps `ANTHROPIC` and `CUSTOM + anthropic` on `/messages`, while all OpenAI-compatible providers continue to use `/chat/completions`.

- [ ] **Step 2: Update main chat dispatch in `AiService.sendMessage()`**

Replace the `when (config.provider)` response dispatch in `sendMessage()` with:

```kotlin
val (rawResponse, reasoning) = if (usesAnthropicProtocol(config)) {
    val resp = callAnthropic(config, messages, systemPrompt)
    Pair(resp, null)
} else {
    callOpenAiCompatibleWithReasoning(config, messages)
}
```

- [ ] **Step 3: Update proactive message dispatch**

In `generateProactiveMessage()`, replace the provider `when` with:

```kotlin
val rawResponse = if (usesAnthropicProtocol(config)) {
    callAnthropic(config, messages, systemPrompt)
} else {
    callOpenAiCompatible(config, messages)
}
```

- [ ] **Step 4: Update custom-system dispatch**

In `sendMessageWithCustomSystem()`, replace the provider `when` with:

```kotlin
val rawResponse = if (usesAnthropicProtocol(config)) {
    callAnthropic(config, messages, customSystemPrompt)
} else {
    callOpenAiCompatible(config, messages)
}
```

- [ ] **Step 5: Search for remaining manual `CUSTOM` dispatches**

Run:

```bash
rg "ApiProvider\.CUSTOM|usesAnthropicProtocol|callOpenAiCompatible" core/network/src/main/java/com/yunian/ai/network/AiService.kt feature/settings/src/main/java/com/yunian/ai/feature/settings/ui/viewmodel/SettingsViewModel.kt -n
```

Expected: any remaining `ApiProvider.CUSTOM` dispatch should either be in provider lists that are not protocol-sensitive or be guarded by `usesAnthropicProtocol(config)`.

- [ ] **Step 6: Compile modified modules**

Run:

```bash
./gradlew :core:network:compileDebugKotlin :feature:settings:compileDebugKotlin
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Commit protocol routing**

```bash
git add core/network/src/main/java/com/yunian/ai/network/AiService.kt feature/settings/src/main/java/com/yunian/ai/feature/settings/ui/viewmodel/SettingsViewModel.kt
git commit -m "fix(api): route custom anthropic configs correctly"
```

---

### Task 4: Make Model Fetch Protocol-Aware in ViewModel

**Files:**
- Modify: `feature/settings/src/main/java/com/yunian/ai/feature/settings/ui/viewmodel/SettingsViewModel.kt`

- [ ] **Step 1: Add early protocol guard to `fetchModels()`**

Inside `fetchModels(baseUrl, apiKey, provider, skipCertVerify)`, after resolving `resolvedProvider`, create a lightweight config and reject custom Anthropic-compatible model fetches.

Use this code after `resolvedProvider` is computed:

```kotlin
val modelListConfig = ApiConfig(
    provider = resolvedProvider ?: ApiProvider.CUSTOM,
    apiKey = apiKey,
    baseUrl = baseUrl,
    model = "",
    skipCertVerify = skipCertVerify,
    formatHint = if (provider == "CUSTOM_ANTHROPIC") "anthropic" else "openai"
)
if (!AiService.supportsOpenAiModelList(modelListConfig)) {
    _modelFetchStates.value = _modelFetchStates.value.toMutableMap().apply {
        put(provider, ModelFetchState(errorMessage = "Anthropic 兼容模式通常不支持 /models，请手动填写模型名"))
    }
    return@launch
}
```

If the UI passes only provider names, keep the existing `CUSTOM` provider key for now and make the UI avoid calling this in Anthropic mode. The guard is defensive for future callers.

- [ ] **Step 2: Clear stale models on new fetch start**

At the start of `fetchModels()`, before setting loading state, clear stale models for that provider:

```kotlin
_fetchedModels.value = _fetchedModels.value.toMutableMap().apply {
    remove(provider)
}
_modelFetchStates.value = _modelFetchStates.value.toMutableMap().apply {
    put(provider, ModelFetchState(isLoading = true))
}
```

- [ ] **Step 3: Preserve current PARTNER remote-key behavior**

Ensure the existing block remains unchanged in behavior:

```kotlin
if (resolvedProvider == ApiProvider.PARTNER && keyToUse.isBlank()) {
    val remoteKeys = RemoteKeyProvider.fetchKeysAsync(getApplication(), forceRefresh = true)
    ...
}
```

Only adjust imports if necessary; do not change partner handshake or quota behavior.

- [ ] **Step 4: Compile Settings ViewModel**

Run:

```bash
./gradlew :feature:settings:compileDebugKotlin
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit model fetch guard**

```bash
git add feature/settings/src/main/java/com/yunian/ai/feature/settings/ui/viewmodel/SettingsViewModel.kt
git commit -m "fix(settings): make model fetch protocol aware"
```

---

### Task 5: Fix Main Settings Dialog Model Fetch UI

**Files:**
- Modify: `feature/settings/src/main/java/com/yunian/ai/feature/settings/ui/screen/SettingsScreen.kt:778-1017`

- [ ] **Step 1: Add protocol state variables in `ApiConfigEditDialog()`**

After `val isCustom = config.provider == ApiProvider.CUSTOM`, add:

```kotlin
val isCustomAnthropic = isCustom && formatHint == "anthropic"
val canFetchOpenAiModels = onFetchModels != null && !isCustomAnthropic && baseUrl.isNotBlank() && (isPartner || apiKey.isNotBlank())
```

- [ ] **Step 2: Include `formatHint` in auto-fetch effect keys**

Change:

```kotlin
LaunchedEffect(apiKey, baseUrl, config.provider) {
```

to:

```kotlin
LaunchedEffect(apiKey, baseUrl, config.provider, formatHint, skipCertVerify) {
```

Change `shouldFetch` to:

```kotlin
val shouldFetch = canFetchOpenAiModels && fetchParams != lastFetchedParams
```

- [ ] **Step 3: Replace the `if (hasModels && onFetchModels != null)` model area**

Replace the whole model UI branch from the existing `if (hasModels && onFetchModels != null)` through its `else` model text field with this structure:

```kotlin
if (isCustomAnthropic) {
    OutlinedTextField(
        value = model,
        onValueChange = { model = it },
        label = { Text("Model", color = textSecondaryColor) },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = PetalPrimary,
            unfocusedBorderColor = dividerColor,
            focusedContainerColor = cardBackground,
            unfocusedContainerColor = cardBackground,
            focusedTextColor = textPrimaryColor,
            unfocusedTextColor = textPrimaryColor
        ),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        singleLine = true
    )
    Text(
        text = "Anthropic 兼容模式通常不支持自动拉取模型，请手动填写模型名",
        fontSize = 12.sp,
        color = textSecondaryColor
    )
} else if (onFetchModels != null) {
    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = if (hasModels) selectedModelText else model,
            onValueChange = { if (!hasModels) model = it },
            readOnly = hasModels,
            label = { Text("Model", color = textSecondaryColor) },
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = hasModels) { showModelDropdown = !showModelDropdown },
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = PetalPrimary,
                unfocusedBorderColor = dividerColor,
                focusedContainerColor = cardBackground,
                unfocusedContainerColor = cardBackground,
                focusedTextColor = textPrimaryColor,
                unfocusedTextColor = textPrimaryColor
            ),
            trailingIcon = if (hasModels) {
                {
                    Icon(
                        imageVector = if (showModelDropdown) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                        contentDescription = "展开",
                        modifier = Modifier.clickable { showModelDropdown = !showModelDropdown },
                        tint = textSecondaryColor
                    )
                }
            } else null,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            singleLine = true
        )
        if (hasModels && !modelFetchState.isLoading) {
            DropdownMenu(
                expanded = showModelDropdown,
                onDismissRequest = { showModelDropdown = false },
                modifier = Modifier.fillMaxWidth(0.8f)
            ) {
                availableModels.forEach { m ->
                    DropdownMenuItem(
                        text = { Text(m, color = textPrimaryColor, fontSize = 14.sp) },
                        onClick = {
                            model = m
                            showModelDropdown = false
                        }
                    )
                }
            }
        }
    }

    if (modelFetchState.isLoading) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = PetalPrimary)
        Text("正在获取模型列表...", fontSize = 12.sp, color = textSecondaryColor)
    }

    modelFetchState.errorMessage?.let { error ->
        Text(text = error, fontSize = 12.sp, color = PetalError)
    }

    Button(
        onClick = {
            model = ""
            lastFetchedParams = ""
            onFetchModels.invoke(baseUrl, apiKey.trim(), skipCertVerify)
        },
        enabled = canFetchOpenAiModels && !modelFetchState.isLoading,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = PetalPrimary.copy(alpha = 0.15f),
            contentColor = PetalPrimary,
            disabledContainerColor = dividerColor.copy(alpha = 0.2f),
            disabledContentColor = textSecondaryColor
        )
    ) {
        Text(if (hasModels) "重新拉取模型" else "一键拉取模型", fontSize = 13.sp)
    }
} else {
    OutlinedTextField(
        value = model,
        onValueChange = { model = it },
        label = { Text("Model", color = textSecondaryColor) },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = PetalPrimary,
            unfocusedBorderColor = dividerColor,
            focusedContainerColor = cardBackground,
            unfocusedContainerColor = cardBackground,
            focusedTextColor = textPrimaryColor,
            unfocusedTextColor = textPrimaryColor
        ),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        singleLine = true
    )
}
```

- [ ] **Step 4: Move API format selector above model area if needed**

If `formatHint` is declared after the model area, keep the variable where it is but move the visible API format selector block above the model area so users choose protocol before model fetching. Use the existing selector code unchanged except for its placement.

- [ ] **Step 5: Compile settings module**

Run:

```bash
./gradlew :feature:settings:compileDebugKotlin
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit main dialog UI fix**

```bash
git add feature/settings/src/main/java/com/yunian/ai/feature/settings/ui/screen/SettingsScreen.kt
git commit -m "fix(settings): show custom api model fetch action"
```

---

### Task 6: Fix Petal API Card Dialog Model Fetch UI

**Files:**
- Modify: `feature/settings/src/main/java/com/yunian/ai/feature/settings/ui/screen/PetalApiCards.kt:167-438`

- [ ] **Step 1: Add protocol state variables**

After `val isCustom = config.provider == ApiProvider.CUSTOM`, add:

```kotlin
val isCustomAnthropic = isCustom && formatHint == "anthropic"
val canFetchOpenAiModels = onFetchModels != null && !isCustomAnthropic && baseUrl.isNotBlank() && (isPartner || apiKey.isNotBlank())
```

- [ ] **Step 2: Include `formatHint` and `skipCertVerify` in auto-fetch keys**

Change:

```kotlin
LaunchedEffect(apiKey, baseUrl, config.provider) {
```

to:

```kotlin
LaunchedEffect(apiKey, baseUrl, config.provider, formatHint, skipCertVerify) {
```

Change `shouldFetch` to:

```kotlin
val shouldFetch = canFetchOpenAiModels && fetchParams != lastFetchedParams
```

- [ ] **Step 3: Apply the same model area replacement as Task 5**

Inside the `if (!isPartner)` fields block, replace the `if (hasModels && onFetchModels != null)` model section with the exact protocol-aware model area from Task 5 Step 3.

- [ ] **Step 4: Ensure custom API format selector appears before model area**

In `PetalApiCards.kt`, move the existing `if (isCustom) { ... API 格式 ... }` selector block above the model area. This makes changing between OpenAI and Anthropic immediately affect the one-click fetch UI.

- [ ] **Step 5: Compile settings module**

Run:

```bash
./gradlew :feature:settings:compileDebugKotlin
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit Petal dialog UI fix**

```bash
git add feature/settings/src/main/java/com/yunian/ai/feature/settings/ui/screen/PetalApiCards.kt
git commit -m "fix(settings): update petal custom api model fetch ui"
```

---

### Task 7: Verification

**Files:**
- No new production files unless verification finds a compile error.

- [ ] **Step 1: Run focused network tests**

Run:

```bash
./gradlew :core:network:testDebugUnitTest --tests "com.yunian.ai.network.CustomApiProtocolDispatchTest" --tests "com.yunian.ai.network.RequestSecurityInterceptorTest"
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Compile settings and network modules**

Run:

```bash
./gradlew :core:network:compileDebugKotlin :feature:settings:compileDebugKotlin
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Run debug build if focused compile passes**

Run:

```bash
./gradlew assembleDebug
```

Expected: `BUILD SUCCESSFUL`. If this fails due to unrelated modules already dirty in the working tree, record the exact failure and do not claim full build verification.

- [ ] **Step 4: Manual smoke test in app**

Open API settings and verify:

```text
1. Add 自定义 API.
2. Keep API 格式 = OpenAI 兼容.
3. Fill Base URL and API Key.
4. Confirm 一键拉取模型 is visible before any model has been fetched.
5. Tap 一键拉取模型.
6. Confirm loading indicator appears.
7. Confirm either model dropdown appears or visible error text appears.
8. Switch API 格式 = Anthropic 兼容.
9. Confirm one-click model fetch is disabled/hidden and manual model hint is shown.
10. Fill model name and tap 测试.
```

- [ ] **Step 5: Final commit if verification changes were needed**

If verification required fixes, commit them:

```bash
git add <changed-files>
git commit -m "fix(api): finalize custom api verification fixes"
```

---

## Self-Review Notes

- Spec coverage: protocol default, Anthropic preservation, one-click model fetch, visible errors, and test routing are covered by Tasks 2-7.
- Placeholder scan: no TBD/TODO/implement-later placeholders remain.
- Type consistency: `ApiConfig.formatHint`, `AiService.usesAnthropicProtocol`, and `AiService.supportsOpenAiModelList` are introduced before use.
- Scope check: this plan intentionally avoids broad provider refactors and only touches custom API routing, fetch UI, and broken test compilation needed for verification.
