# SSO API Library — Integration Guide

The `ssoapi` library lets any Android app bind to the **SSO middleware service** (`com.example.service`) to perform login, logout, account switching, and account retrieval via AIDL.

---

## Requirements

- Android **minSdk 24**
- The **SSO Service app** (`com.example.service`) must be installed on the device
- Your app must declare package visibility for Android 11+ (see Step 2)

---

## Step 1 — Add the Library

### Option A — AAR file (recommended for sharing)

1. Copy `ssoapi-debug.aar` (or `ssoapi-release.aar`) into your project's `app/libs/` folder
2. In `app/build.gradle.kts`:
```kotlin
dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))
}
```

### Option B — Local Maven (for development)

Publish from `sso-api-lib`:
```bash
.\gradlew :ssoapi:publishToMavenLocal
```

In `settings.gradle.kts`:
```kotlin
dependencyResolutionManagement {
    repositories {
        mavenLocal()  // ← must be first
        google()
        mavenCentral()
    }
}
```

In `app/build.gradle.kts`:
```kotlin
dependencies {
    implementation("com.example:ssoapi:1.0.0")        // release
    // OR
    implementation("com.example:ssoapi-debug:1.0.0")  // debug
}
```

---

## Step 2 — Manifest (Required for Android 11+)

Add this **before** `<application>` in `AndroidManifest.xml`:

```xml
<queries>
    <package android:name="com.example.service" />
    <intent>
        <action android:name="com.example.service.SSO_SERVICE" />
    </intent>
</queries>
```

> **Without this**, `bindService()` silently returns `false` on Android 11+ and the service will never connect.

---

## Step 3 — Use the API

### Create the client
```kotlin
val ssoClient = SsoApiClient(context)
```

### Login
```kotlin
val result = ssoClient.login("user@example.com", "password")
result.onSuccess { account ->
    Log.d("SSO", "Logged in: ${account.mail}")
}.onFailure { error ->
    Log.e("SSO", "Login failed: ${error.message}")
}
```

### Register
```kotlin
val result = ssoClient.register("user@example.com", "password")
```

### Get active account
```kotlin
val account: Account? = ssoClient.getActiveAccount()
```

### Get all accounts
```kotlin
val accounts: List<Account> = ssoClient.getAllAccounts()
```

### Logout
```kotlin
ssoClient.logout(account.guid)   // single account
ssoClient.logoutAll()            // all accounts
```

### Switch account
```kotlin
ssoClient.switchAccount(guid)
```

### Fetch token only (without saving)
```kotlin
val result = ssoClient.fetchToken("user@example.com", "password")
// result.getOrNull()?.sessionToken
```

### Cleanup
```kotlin
// Call when your Activity/Fragment is destroyed
ssoClient.unbind()
```

---

## Data Classes

### `Account`
| Field | Type | Description |
|-------|------|-------------|
| `guid` | `String` | Unique account ID |
| `mail` | `String` | Email address |
| `sessionToken` | `String` | Auth token |
| `profileImage` | `String?` | Optional image URL |
| `isActive` | `Boolean` | Whether this is the current active account |

### `AuthResult`
| Field | Type | Description |
|-------|------|-------------|
| `success` | `Boolean` | Operation succeeded |
| `fail` | `Boolean` | Operation failed |
| `message` | `String?` | Error message if failed |

---

## Typical ViewModel Usage

```kotlin
class AccountViewModel(app: Application) : AndroidViewModel(app) {

    private val ssoClient = SsoApiClient(app)

    fun loadAccounts() {
        viewModelScope.launch {
            val accounts = ssoClient.getAllAccounts()
            val active = ssoClient.getActiveAccount()
            // update your UI state
        }
    }

    override fun onCleared() {
        super.onCleared()
        ssoClient.unbind()
    }
}
```

---

## Rebuild the Library

Whenever you change source files in `sso-api-lib`:

```bash
cd C:\Users\krpra\AndroidStudioProjects\sso-api-lib

# Build AARs
.\gradlew :ssoapi:bundleDebugAar
.\gradlew :ssoapi:bundleReleaseAar

# OR publish to local Maven
.\gradlew :ssoapi:publishToMavenLocal
```

Output AARs are at:
```
ssoapi\build\outputs\aar\ssoapi-debug.aar
ssoapi\build\outputs\aar\ssoapi-release.aar
```
