# Understanding the SSO Account System — Beginner's Deep Dive

> **Who is this for?** Someone new to this project who wants to understand what was built, how it works, and how to replicate the library separation.

---

## 1. The Big Picture — What Is This System?

This system has **three separate apps** talking to each other:

```
┌─────────────────────┐        AIDL (IPC)        ┌──────────────────────────┐
│    account2 app     │ ◄──────────────────────►  │  com.example.service     │
│  (your client app)  │                           │  (SSO middleware service) │
└─────────────────────┘                           └──────────────┬───────────┘
         ▲                                                        │ HTTP
         │ uses                                                   ▼
┌─────────────────────┐                           ┌──────────────────────────┐
│  ssoapi library     │                           │     Your Backend API     │
│  (ssoapi-debug.aar) │                           │  /get-token, /sign-in    │
└─────────────────────┘                           └──────────────────────────┘
```

| App | What it does |
|-----|-------------|
| **account2** | The UI your user sees — sign in, switch accounts |
| **com.example.service** | The background service that calls your real backend API and stores accounts |
| **ssoapi library** | The bridge — lets account2 talk to the service over Android IPC (AIDL) |

> **Why separate?** The `service` app does the real work (HTTP calls, database storage, AccountManager). The `account2` app just shows UI. Other apps can also use the same service through the ssoapi library.

---

## 2. What Is AIDL?

**AIDL (Android Interface Definition Language)** is how two different Android apps communicate with each other in real-time. It's like a phone call between apps.

```
account2                    com.example.service
    │                               │
    │── bindService() ──────────►  │  (service opens the "phone line")
    │                               │
    │── sso.login("a@b.com") ───►  │  (account2 calls a method)
    │                               │
    │◄── IAuthCallback.onResult() ─ │  (service calls back with result)
```

The AIDL files in `ssoapi` define the "contract" — what methods can be called:

| AIDL File | What it defines |
|-----------|----------------|
| `sso.aidl` | The main interface: `login()`, `logout()`, `getAllAccounts()`, etc. |
| `IAuthCallback.aidl` | How the service sends results back: `onResult()`, `onAccountReceived()` |
| `Account.aidl` | Marks `Account` as a data object that can travel across processes |
| `AuthResult.aidl` | Marks `AuthResult` as a data object that can travel across processes |

---

## 3. What Happens When You Sign In — Step by Step

### User taps "Sign In" on the screen

```
[LoginScreen.kt]
Button(onClick = { viewModel.login(email, password) })
```

### Step 1 — ViewModel receives the call
```
[AccountViewModel.kt]
fun login(mail: String, password: String) {
    _loginState.value = LoginState.Loading   ← shows spinner on screen
    val result = ssoApiClient.login(mail, password)
}
```
The UI immediately shows a loading spinner.

### Step 2 — SsoApiClient connects to the service
```
[SsoApiClient.kt]
suspend fun login(mail, password) {
    ensureConnected()  ← checks if already connected, if not: bindService()
    service.login(mail, password, callback)
}
```
`ensureConnected()` calls Android's `bindService()` to establish the IPC connection to `com.example.service`. It waits up to **5 seconds** for this. If it times out → returns failure immediately.

### Step 3 — Service performs the real work
Inside `com.example.service`, the `SsoService` receives the call, then:
1. Calls your backend `/get-token` API with the email + password
2. Gets a `guid` and `sessionToken` back
3. Calls `/account-info` with the token to get full account details
4. Saves the account to the device's **AccountManager** and local database
5. Marks it as the **active account**
6. Fires `callback.onResult(AuthResult(success=true))` + `callback.onAccountReceived(account)`

### Step 4 — Callback arrives back in account2
```
[SsoApiClient.kt]
override fun onResult(result: AuthResult) {
    wasSuccess = result.success   ← remember success
}
override fun onAccountReceived(account: Account) {
    if (wasSuccess) onSuccess(account)  ← resume the coroutine
}
```

### Step 5 — ViewModel updates the UI state
```
[AccountViewModel.kt]
onSuccess = { account ->
    _activeAccount.value = account.copy(isActive = true)
    _accounts.value = currentAccounts + activeAccount
    _loginState.value = LoginState.Success  ← triggers navigation
}
```

### Step 6 — Screen navigates away
```
[LoginScreen.kt]
LaunchedEffect(loginState) {
    if (loginState is LoginState.Success) {
        onLoginSuccess()  ← navigate to AccountScreen
    }
}
```

### Full Sign-In Flow Diagram

```
User taps Sign In
      │
      ▼
LoginScreen.kt
  viewModel.login(email, pw)
      │
      ▼
AccountViewModel.kt
  loginState = Loading (spinner shows)
  ssoApiClient.login(email, pw)
      │
      ▼
SsoApiClient.kt
  bindService("com.example.service")   ← Android IPC
      │ (waits up to 5 seconds)
      ▼
com.example.service (SsoService)
  → POST /get-token  (HTTP to your server)
  → POST /account-info
  → save to AccountManager + DB
  → callback.onResult(success=true)
  → callback.onAccountReceived(account)
      │
      ▼
SsoApiClient.kt
  coroutine resumes with Result.success(account)
      │
      ▼
AccountViewModel.kt
  loginState = Success
  activeAccount = account
  accounts list updated
      │
      ▼
LoginScreen.kt
  LaunchedEffect sees Success → navigate to AccountScreen
      │
      ▼
AccountScreen.kt
  shows active account card + all accounts list
```

---

## 4. What Happens on App Startup?

The `AccountViewModel` runs this automatically when created:

```kotlin
init {
    fetchAccountsOnStartup()   // called immediately on creation
}

private fun fetchAccountsOnStartup() {
    viewModelScope.launch {
        val accountsList = ssoApiClient.getAllAccounts()   // asks service for stored accounts
        val active = ssoApiClient.getActiveAccount()       // asks which one is active
        _accounts.value = accountsList
        _activeAccount.value = active
        _isInitialized.value = true   // tells UI it's ready
    }
}
```

If the service is not running/installed → `getAllAccounts()` returns empty list and app shows login screen.

---

## 5. Current Structure of account2

```
account2/
├── app/
│   ├── libs/
│   │   └── ssoapi-debug.aar          ← the ssoapi library (AAR file)
│   └── src/main/
│       ├── AndroidManifest.xml        ← has <queries> for Android 11+
│       └── java/com/example/account/
│           ├── MainActivity.kt        ← sets up navigation
│           ├── ui/screen/
│           │   ├── LoginScreen.kt     ← email/password form
│           │   └── AccountScreen.kt   ← account list, switch, logout
│           └── viewmodel/
│               └── AccountViewModel.kt ← all the logic, talks to SsoApiClient
├── app/build.gradle.kts               ← imports the AAR from libs/
└── settings.gradle.kts                ← no longer includes :ssoapi
```

---

## 6. The Changes Made — What and Why

### Change 1 — Extracted `ssoapi` into a Standalone Library

**Before:**
```
account2/
├── app/
└── ssoapi/          ← lived here, inside account2
    ├── build.gradle.kts
    └── src/ ...
```

**After:**
```
sso-api-lib/         ← completely separate project
└── ssoapi/
    └── src/ ...

account2/
└── app/
    └── libs/
        └── ssoapi-debug.aar   ← imported as a file
```

**Why?** So any app can use the SSO library — not just account2. You just give someone the `.aar` file.

---

### Change 2 — Added `maven-publish` to sso-api-lib

In `sso-api-lib/ssoapi/build.gradle.kts`:
```kotlin
plugins {
    alias(libs.plugins.android.library)
    `maven-publish`    ← NEW: lets us publish to Maven
}

publishing {
    singleVariant("release") { withSourcesJar() }
    singleVariant("debug")   { withSourcesJar() }
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = "com.example"
                artifactId = "ssoapi"
                version = "1.0.0"
            }
            create<MavenPublication>("debug") {
                from(components["debug"])
                groupId = "com.example"
                artifactId = "ssoapi-debug"
                version = "1.0.0"
            }
        }
    }
}
```
This lets you run `.\gradlew :ssoapi:publishToMavenLocal` to publish both debug and release.

---

### Change 3 — Switched account2 from `project()` to `fileTree()`

In `account2/app/build.gradle.kts`:
```kotlin
// BEFORE — was a local module reference:
implementation(project(":ssoapi"))

// AFTER — imports the AAR file from libs/ folder:
implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))
```

---

### Change 4 — Removed `:ssoapi` from `settings.gradle.kts`

In `account2/settings.gradle.kts`:
```kotlin
// BEFORE:
include(":app")
include(":ssoapi")

// AFTER:
include(":app")
// ssoapi removed — it's now a standalone library
```

---

### Change 5 — Fixed Android 11+ Package Visibility Bug

**The Bug:** `SsoApiClient.bindService()` was silently returning `false` even when `com.example.service` was installed and running. This caused the "Connection timeout or failed" error.

**Why it happens:** Since Android 11 (API 30), apps cannot "see" other apps unless they explicitly declare them.

**The Fix** — added to `AndroidManifest.xml`:
```xml
<queries>
    <package android:name="com.example.service" />
    <intent>
        <action android:name="com.example.service.SSO_SERVICE" />
    </intent>
</queries>
```

---

## 7. How to Separate ssoapi from account — Step by Step

If you have a project where `ssoapi` is a sub-module inside your app and want to make it standalone:

### Step 1 — Create the new library project folder
```
mkdir C:\Users\YourName\AndroidStudioProjects\sso-api-lib
```

### Step 2 — Copy the Gradle wrapper
```powershell
Copy-Item "account2\gradlew" "sso-api-lib\gradlew"
Copy-Item "account2\gradlew.bat" "sso-api-lib\gradlew.bat"
Copy-Item "account2\gradle\wrapper" "sso-api-lib\gradle\wrapper" -Recurse
```

### Step 3 — Create `settings.gradle.kts` in sso-api-lib
```kotlin
rootProject.name = "sso-api-lib"
include(":ssoapi")
```

### Step 4 — Create root `build.gradle.kts` in sso-api-lib
```kotlin
plugins { alias(libs.plugins.android.library) apply false }
```

### Step 5 — Create `gradle/libs.versions.toml` in sso-api-lib
Copy the relevant entries from your original app (agp version, appcompat, etc.)
Remove any app-only entries (compose, activity-compose, etc.)

### Step 6 — Copy your ssoapi source files verbatim
Copy the entire `account2/ssoapi/src/` folder to `sso-api-lib/ssoapi/src/`

### Step 7 — Create `ssoapi/build.gradle.kts` with maven-publish
(See the full file in Section 6, Change 2 above)

### Step 8 — Create `local.properties`
```properties
sdk.dir=C\:\\Users\\YourName\\AppData\\Local\\Android\\Sdk
```

### Step 9 — Build the AAR
```bash
cd sso-api-lib
.\gradlew :ssoapi:bundleDebugAar
# Output: ssoapi/build/outputs/aar/ssoapi-debug.aar
```

### Step 10 — Remove ssoapi from account2
```powershell
Remove-Item "account2\ssoapi" -Recurse -Force
```

### Step 11 — Update account2 settings.gradle.kts
Remove `include(":ssoapi")`.
If using mavenLocal, add `mavenLocal()` to repositories.

### Step 12 — Update account2 app/build.gradle.kts
```kotlin
// Option A — AAR file in app/libs/
implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))

// Option B — via mavenLocal
implementation("com.example:ssoapi:1.0.0")
```

### Step 13 — Add queries to AndroidManifest.xml ⚠️
```xml
<queries>
    <package android:name="com.example.service" />
    <intent>
        <action android:name="com.example.service.SSO_SERVICE" />
    </intent>
</queries>
```

### Step 14 — Build and verify
```bash
cd account2
.\gradlew :app:assembleDebug
```
Expected: `BUILD SUCCESSFUL`

---

## 8. Frequently Asked Questions

**Q: Why is `bindService()` timing out?**
A: Usually because `<queries>` is missing in AndroidManifest.xml (Android 11+ issue) OR the service app (`com.example.service`) is not installed on the device.

**Q: What is the 5-second timeout in SsoApiClient?**
A: `CONNECTION_TIMEOUT_MS = 5000L` — if Android can't establish the IPC connection within 5 seconds, it gives up. The 30-second `CALLBACK_TIMEOUT_MS` is how long it waits for the service to respond after connecting.

**Q: Why does account2 use a debug AAR and not release?**
A: During development, debug builds are easier to inspect (no ProGuard, readable stack traces). Use release AAR when shipping to production.

**Q: Can I have two different apps using the same service?**
A: Yes! That's exactly why the ssoapi library was separated. Any app that has the `.aar` and the `<queries>` manifest entry can bind to `com.example.service`.

**Q: What data does `Account` contain?**
```kotlin
data class Account(
    val guid: String,          // unique ID from the server
    val mail: String,          // user's email
    val sessionToken: String,  // auth token for API calls
    val profileImage: String?, // optional avatar URL
    val isActive: Boolean      // is this the currently logged-in account?
)
```
