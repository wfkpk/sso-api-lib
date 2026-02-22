# SSO System — Complete Beginner's Deep Dive

> **Goal of this guide:** Teach you every concept used in this codebase from scratch — coroutines, AIDL, Binder threads, Parcelable, security — so you can read any file and fully understand what's happening and **why**.

---

## Table of Contents

1. [The Big Picture — Three Apps, One System](#1-the-big-picture)
2. [Coroutines — What, Why, and How](#2-coroutines)
3. [AIDL — Talking Between Apps](#3-aidl)
4. [The Binder/Stub Pattern — How AIDL Actually Works](#4-the-binderstub-pattern)
5. [Binder Threads — Which Thread Runs What](#5-binder-threads)
6. [Parcelable — Moving Data Across Processes](#6-parcelable)
7. [AIDL Security — Who Gets to Connect?](#7-aidl-security)
8. [suspendCancellableCoroutine — The Bridge Between Two Worlds](#8-suspendcancellablecoroutine)
9. [The Complete Login Flow — Line by Line](#9-the-complete-login-flow)
10. [Timeouts — Why They Exist and How They Work](#10-timeouts)
11. [serviceConnection — What Happens at Bind and Disconnect](#11-serviceconnection)
12. [The Callback Pattern — Why Two Methods (onResult + onAccountReceived)](#12-the-callback-pattern)
13. [Sealed Classes — AuthCallbackResult](#13-sealed-classes)
14. [Quick Reference Cheat Sheet](#14-quick-reference)

---

## 1. The Big Picture

Before anything else, understand this: **this system has THREE separate apps.**

```
┌─────────────────────┐        AIDL (IPC)        ┌──────────────────────────┐
│    account  app     │ ◄─────────────────────►   │  com.example.service     │
│  (shows the UI)     │                           │  (does the real work)    │
└─────────────────────┘                           └──────────────┬───────────┘
         ▲                                                        │ HTTP calls
         │ uses                                                   ▼
┌─────────────────────┐                           ┌──────────────────────────┐
│   ssoapi library    │                           │     Your Backend API     │
│  (ssoapi-debug.aar) │                           │  /get-token, /sign-in    │
└─────────────────────┘                           └──────────────────────────┘
```

| App | Role |
|-----|------|
| **account** (your UI app) | Shows login screen, account list. The user taps buttons here. |
| **com.example.service** | Background service. Does HTTP calls, stores data in AccountManager + DB. Never shown to user. |
| **ssoapi library** | The glue. Lets the UI app talk to the service over Android IPC. You import this as a `.aar` file. |

**Why three separate things?**
- The service can be shared by multiple apps. Any app that includes the `ssoapi` library can log in using the same SSO service.
- The UI app stays thin — it doesn't know *how* login works, just that it can ask for it.
- Security: the service controls all sensitive data (tokens, passwords). The UI app never directly handles them.

---

## 2. Coroutines

### What is a coroutine?

A **coroutine** is a piece of code that can **pause without blocking the thread**.

Think of it like this: imagine you're reading a book and your phone rings. You put a bookmark in, pick up the call, and then come back to exactly where you left off. A coroutine is that bookmark — it suspends execution at a `suspend` point and resumes later, on the same or a different thread, without wasting any resources while waiting.

### Why can't we just use regular functions?

On Android, the **Main Thread** (also called the UI thread) is responsible for drawing every pixel on screen. If you block it — even for 1-2 seconds — the app freezes and Android shows the "App Not Responding" dialog.

```kotlin
// ❌ WRONG — blocks the Main Thread for 5+ seconds
fun login(mail: String, password: String): Account {
    val result = doNetworkCall()  // thread is frozen here!
    return result
}

// ✅ RIGHT — suspends (pauses) without blocking anything
suspend fun login(mail: String, password: String): Account {
    val result = doNetworkCall()  // thread is FREE while waiting
    return result
}
```

The keyword `suspend` tells Kotlin: *"This function can pause. Don't block the thread."*

### How does a coroutine run?

Coroutines run inside a **scope** and on a **dispatcher** (which decides *which thread* to use):

```kotlin
viewModelScope.launch {
    // this block is a coroutine
    val account = ssoApiClient.login(email, password)
    // ^ suspends here; thread is free; resumes when login() finishes
    _loginState.value = LoginState.Success(account)
}
```

| Dispatcher | What thread(s) it uses | When to use |
|-----------|----------------------|-------------|
| `Dispatchers.Main` | Main/UI thread | UI updates, LiveData/StateFlow |
| `Dispatchers.IO` | Background thread pool (up to 64 threads) | Network, disk, AIDL calls |
| `Dispatchers.Default` | CPU thread pool | Heavy computation |

### withContext — Switching Threads Mid-Coroutine

```kotlin
suspend fun login(mail: String, password: String): Result<Account> {
    // Step 1: Make sure we're connected (must be on Main thread — see Section 5)
    if (!ensureConnected()) { ... }

    // Step 2: Switch to IO thread for the actual AIDL call
    return withContext(Dispatchers.IO) {
        // Now running on a background thread
        service.login(mail, password, callback)
    }
}
```

`withContext` is like passing the baton in a relay race. The coroutine moves from one thread to another seamlessly, and then comes back.

### viewModelScope

`viewModelScope` is a coroutine scope tied to the `ViewModel`'s lifecycle. When the `ViewModel` is destroyed (user navigates away, activity finishes), all coroutines in this scope are **automatically cancelled**. No memory leaks, no work done for nothing.

```kotlin
class AccountViewModel : ViewModel() {
    init {
        viewModelScope.launch {  // this coroutine dies when ViewModel dies
            val accounts = ssoApiClient.getAllAccounts()
        }
    }
}
```

---

## 3. AIDL

### What is AIDL?

**AIDL = Android Interface Definition Language.**

On Android, every app runs in its own **process** — its own little sandbox. Memory is not shared. You cannot just call a function in another app's code. To communicate between processes, Android uses **IPC (Inter-Process Communication)**.

AIDL is how you define the *contract* — a list of methods one app can call on another — so Android knows how to serialize the call, send it across process boundaries, and deserialize the result.

Think of it like an **API specification** between two apps.

### What does AIDL look like?

Here's the actual [sso.aidl](file:///c:/Users/krpra/AndroidStudioProjects/sso-api-lib/ssoapi/src/main/aidl/com/example/ssoapi/sso.aidl) from this project:

```aidl
// sso.aidl
package com.example.ssoapi;

import com.example.ssoapi.Account;
import com.example.ssoapi.IAuthCallback;

interface sso {
    void login(String mail, String password, IAuthCallback callback);
    void register(String mail, String password, IAuthCallback callback);
    void logout(String guid);
    void logoutAll();
    void switchAccount(String guid);
    Account getActiveAccount();
    List<Account> getAllAccounts();
    void fetchToken(String mail, String password, IAuthCallback callback);
    void fetchAccountInfo(String guid, String sessionToken, IAuthCallback callback);
}
```

This says: *"Any app that implements this interface must support these methods."*

And [IAuthCallback.aidl](file:///c:/Users/krpra/AndroidStudioProjects/sso-api-lib/ssoapi/src/main/aidl/com/example/ssoapi/IAuthCallback.aidl):

```aidl
// IAuthCallback.aidl
interface IAuthCallback {
    void onResult(in AuthResult result);
    void onAccountReceived(in Account account);
}
```

This is the **reverse channel** — the service calls back into your app using this interface.

### The [in](file:///c:/Users/krpra/AndroidStudioProjects/sso-api-lib/ssoapi/src/main/java/com/example/ssoapi/SsoApiClient.kt#183-221) keyword

When you see `in AuthResult result`, the [in](file:///c:/Users/krpra/AndroidStudioProjects/sso-api-lib/ssoapi/src/main/java/com/example/ssoapi/SsoApiClient.kt#183-221) means: *"This object flows from caller INTO the callee."* AIDL also has [out](file:///c:/Users/krpra/AndroidStudioProjects/sso-api-lib/ssoapi/src/main/java/com/example/ssoapi/SsoApiClient.kt#339-369) (callee fills it in and sends it back) and `inout` (both). Almost always you use [in](file:///c:/Users/krpra/AndroidStudioProjects/sso-api-lib/ssoapi/src/main/java/com/example/ssoapi/SsoApiClient.kt#183-221).

---

## 4. The Binder/Stub Pattern

This is where the magic happens. When Android compiles your [.aidl](file:///c:/Users/krpra/AndroidStudioProjects/sso-api-lib/ssoapi/src/main/aidl/com/example/ssoapi/sso.aidl) file, it generates a Java/Kotlin class automatically. That class has two important inner parts:

### The Generated Structure

When you write `interface sso { ... }` in AIDL, Android generates roughly this:

```kotlin
// What Android auto-generates from sso.aidl
public interface sso extends IInterface {

    // ── THE STUB ────────────────────────────────────────────────────────────
    // The service implements this. It's the SERVER side.
    abstract class Stub : Binder(), sso {
        // This converts a raw IBinder into the sso interface
        companion object {
            fun asInterface(obj: IBinder?): sso { ... }
        }
        // Android calls this when a remote call arrives
        override fun onTransact(code: Int, data: Parcel, reply: Parcel, flags: Int): Boolean { ... }
    }

    // ── METHODS (the interface itself) ──────────────────────────────────────
    fun login(mail: String, password: String, callback: IAuthCallback)
    fun register(mail: String, password: String, callback: IAuthCallback)
    // ... etc
}
```

### Who Uses What?

| Side | Class Used | What it does |
|------|-----------|--------------|
| **Service** (com.example.service) | `sso.Stub` | **Extends** Stub and **implements** each method (login, logout, etc.) |
| **Client** (account app) | `sso.Stub.asInterface(binder)` | Gets a **proxy** object that looks like `sso` but sends calls across IPC |

### Walkthrough: Making a Remote Call

```
account app                         Android OS                  com.example.service
     │                                   │                              │
     │  service.login("a@b.com", ...)    │                              │
     ├──────────────────────────────────►│                              │
     │                                   │  (serializes to Parcel)      │
     │                                   │  onTransact(LOGIN_CODE, ...) │
     │                                   ├─────────────────────────────►│
     │                                   │                              │ Stub.login() called
     │                                   │                              │ does HTTP, DB work
     │                                   │  callback.onResult(result)   │
     │                                   │◄─────────────────────────────│
     │◄──────────────────────────────────│                              │
     │  your onResult() fires here       │                              │
```

### In the Code

**In ssoService (the server side — com.example.service):**
```kotlin
class SsoService : Service() {
    private val binder = object : sso.Stub() {
        override fun login(mail: String, password: String, callback: IAuthCallback) {
            // ... do the actual login work
            callback.onResult(AuthResult(success = true))
            callback.onAccountReceived(account)
        }
    }

    override fun onBind(intent: Intent): IBinder = binder
}
```

**In SsoApiClient (the client side — account app):**
```kotlin
// When the service connects:
override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
    ssoService = sso.Stub.asInterface(service)  // convert raw IBinder → proxy
    //           ^^^^^^^^^^^^^^^^^^^^^ This is the magic line
}

// Later, calling the method:
ssoService.login(mail, password, callback)  // looks local but goes across IPC!
```

`sso.Stub.asInterface(service)` gives you an object that **looks like** a local `sso` object, but every method call is secretly serialized, sent to the other process, executed there, and the result comes back. You never see any of this complexity.

---

## 5. Binder Threads

> This is frequently misunderstood. Read carefully.

### The Thread Problem

When `com.example.service` receives an AIDL call, Android doesn't put it on the Main thread. It puts it on a **Binder thread** — a background thread from a pool (Android maintains up to ~15 of them).

This means:

```
account app (Main Thread)         com.example.service (Binder Thread)
        │                                    │
        │── service.login() ────────────────►│  ← runs on Binder thread
        │                                    │     NOT on service's Main thread
```

**Why does this matter?** Because in the service, if you want to update any UI or touch anything that requires the Main thread, you must use `Handler(Looper.getMainLooper()).post { ... }`.

### The Callback Direction

When the service calls `callback.onResult(result)` back into your app (account), that call also arrives on a **Binder thread** in the account app:

```kotlin
// In SsoApiClient.kt — this runs on a Binder thread
override fun onResult(result: AuthResult) {
    resultReceived = true
    wasSuccess = result.success
    if (result.fail) {
        mainHandler.post {           // ← switch to Main thread
            onFailure(result.message)
        }
    }
}
```

The code uses `mainHandler.post { }` to switch back to the Main thread before calling `onFailure` or `onSuccess` — because those callbacks update the UI state.

### Where [ensureConnected()](file:///c:/Users/krpra/AndroidStudioProjects/sso-api-lib/ssoapi/src/main/java/com/example/ssoapi/SsoApiClient.kt#67-127) Must Run

```kotlin
private suspend fun ensureConnected(): Boolean = withContext(Dispatchers.Main) {
    //                                                       ^^^^^^^^^^^^^^^^
    //                                         Forces this to run on Main thread
    ...
    context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
}
```

`bindService()` and `unbindService()` **must** be called from the Main thread on Android. That's why [ensureConnected()](file:///c:/Users/krpra/AndroidStudioProjects/sso-api-lib/ssoapi/src/main/java/com/example/ssoapi/SsoApiClient.kt#67-127) forces execution to `Dispatchers.Main` via `withContext`.

### Thread Summary for This Project

| Operation | Thread |
|-----------|--------|
| `bindService()` | Must be Main thread → `withContext(Dispatchers.Main)` |
| [onServiceConnected()](file:///c:/Users/krpra/AndroidStudioProjects/sso-api-lib/ssoapi/src/main/java/com/example/ssoapi/SsoApiClient.kt#52-59) callback | Main thread |
| `service.login()` call | IO thread → `withContext(Dispatchers.IO)` |
| [onResult()](file:///c:/Users/krpra/AndroidStudioProjects/sso-api-lib/ssoapi/src/main/java/com/example/ssoapi/SsoApiClient.kt#159-171) / [onAccountReceived()](file:///c:/Users/krpra/AndroidStudioProjects/sso-api-lib/ssoapi/src/main/java/com/example/ssoapi/SsoApiClient.kt#172-180) in account app | Binder thread → use `mainHandler.post` to switch to Main |
| `onTransact()` in service | Binder thread (in the service process) |

---

## 6. Parcelable

### The Core Problem

Two apps (processes) don't share memory. You cannot just pass a Kotlin object between them — the memory address in one process means nothing in another.

The solution: **serialize** (pack) the object into a byte sequence, send it across, and **deserialize** (unpack) it on the other side.

Android's mechanism for this is `Parcelable`.

### How Account.kt Implements Parcelable

```kotlin
data class Account(
    val guid: String = "",
    val mail: String = "",
    val profileImage: String? = null,
    val sessionToken: String = "",
    val isActive: Boolean = false
) : Parcelable {

    // STEP 1: How to CREATE an Account FROM a Parcel (deserialization)
    constructor(parcel: Parcel) : this(
        parcel.readString() ?: "",    // read guid
        parcel.readString() ?: "",    // read mail
        parcel.readString(),          // read profileImage (nullable)
        parcel.readString() ?: "",    // read sessionToken
        parcel.readByte() != 0.toByte()  // read isActive (1=true, 0=false)
    )

    // STEP 2: How to WRITE an Account INTO a Parcel (serialization)
    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(guid)
        parcel.writeString(mail)
        parcel.writeString(profileImage)
        parcel.writeString(sessionToken)
        parcel.writeByte(if (isActive) 1 else 0)
    }

    override fun describeContents(): Int = 0  // always 0 unless you have file descriptors

    // STEP 3: The CREATOR — Android uses this to reconstruct the object
    companion object CREATOR : Parcelable.Creator<Account> {
        override fun createFromParcel(parcel: Parcel): Account = Account(parcel)
        override fun newArray(size: Int): Array<Account?> = arrayOfNulls(size)
    }
}
```

**Critical Rule:** The order of `readString()` / `writeString()` calls **must match perfectly**. If you write `guid, mail, profileImage, sessionToken, isActive` you must read them in the **exact same order**. If they mismatch, data gets scrambled.

### The Two AIDL Declarations (Account.aidl, AuthResult.aidl)

```aidl
// Account.aidl
parcelable Account;

// AuthResult.aidl
parcelable AuthResult;
```

These tiny files just tell the AIDL compiler: *"Hey, [Account](file:///c:/Users/krpra/AndroidStudioProjects/sso-api-lib/ssoapi/src/main/java/com/example/ssoapi/Account.kt#17-53) and [AuthResult](file:///c:/Users/krpra/AndroidStudioProjects/sso-api-lib/ssoapi/src/main/java/com/example/ssoapi/AuthResult.kt#15-45) are `Parcelable` objects — I'll handle their serialization myself in the .kt files."* Without these stubs, AIDL wouldn't know how to transmit those types.

---

## 7. AIDL Security

### The Threat

Imagine any random app on your phone could call `ssoService.getAllAccounts()` and steal all account tokens. That would be a serious security breach.

### Defence Layer 1 — Package Visibility

Since Android 11, apps cannot see or connect to other apps unless they **explicitly declare** what they're looking for in [AndroidManifest.xml](file:///c:/Users/krpra/AndroidStudioProjects/sso-api-lib/ssoapi/src/main/AndroidManifest.xml):

```xml
<!-- In the account app's AndroidManifest.xml -->
<queries>
    <package android:name="com.example.service" />
    <intent>
        <action android:name="com.example.service.SSO_SERVICE" />
    </intent>
</queries>
```

Without this, `bindService()` silently returns `false`. The service app is completely invisible. This is why we added the `<queries>` block — and it was the biggest bug we fixed.

### Defence Layer 2 — Explicit Intent

In [SsoApiClient.kt](file:///c:/Users/krpra/AndroidStudioProjects/sso-api-lib/ssoapi/src/main/java/com/example/ssoapi/SsoApiClient.kt), notice how the service is bound:

```kotlin
val intent = Intent(SSO_SERVICE_ACTION).apply {
    setPackage(SSO_SERVICE_PACKAGE)       // "com.example.service"
    setClassName(SSO_SERVICE_PACKAGE, SSO_SERVICE_CLASS)  // exact class name
}
context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
```

We set **both** `setPackage` and `setClassName`. This is an **explicit intent** — it targets one exact app and one exact class. No other app can intercept it. Using an implicit intent (just the action, no package) would be a security risk because any app could claim to handle that action.

### Defence Layer 3 — `android:exported` and Permissions

In `com.example.service`'s manifest, the service should be declared with controlled export:

```xml
<service
    android:name=".SsoService"
    android:exported="true"
    android:permission="com.example.service.BIND_SSO_SERVICE" />
```

- `android:exported="true"` — allows other apps to bind
- `android:permission="..."` — only apps that hold this permission can bind

The `account` app would then declare `<uses-permission android:name="com.example.service.BIND_SSO_SERVICE" />` and the service's `AndroidManifest` defines who can grant that permission. This is an optional extra layer of hardening.

### Defence Layer 4 — `Binder.getCallingUid()`

Inside the service's stub implementation, you can verify who is calling:

```kotlin
override fun login(mail: String, password: String, callback: IAuthCallback) {
    val callerUid = Binder.getCallingUid()
    // Check if this UID is allowed — e.g., only your own apps
    if (!isAllowedCaller(callerUid)) {
        throw SecurityException("Unauthorized caller")
    }
    // proceed with login
}
```

`Binder.getCallingUid()` gives you the Linux UID of the calling process. You can trust this value — Android OS sets it, the caller cannot fake it.

---

## 8. suspendCancellableCoroutine

This is the most complex part of the code. Let's break it down fully.

### The Problem It Solves

AIDL callbacks are **callback-based** — they don't return values. The service calls your [onResult()](file:///c:/Users/krpra/AndroidStudioProjects/sso-api-lib/ssoapi/src/main/java/com/example/ssoapi/SsoApiClient.kt#159-171) at some point in the future, on a Binder thread. But our API ([login()](file:///c:/Users/krpra/AndroidStudioProjects/sso-api-lib/ssoapi/src/main/java/com/example/ssoapi/SsoApiClient.kt#183-221), [register()](file:///c:/Users/krpra/AndroidStudioProjects/sso-api-lib/ssoapi/src/main/java/com/example/ssoapi/SsoApiClient.kt#222-260), etc.) is supposed to be a clean `suspend` function that **returns** a value.

How do you convert "here's a callback that fires eventually" into "here's a suspend function that returns a value"?

Answer: `suspendCancellableCoroutine`.

### Mental Model

Imagine a courier system:
1. You hand the courier a **package** (the AIDL call: `service.login(...)`)
2. You sit and **wait** (the coroutine suspends — not blocking the thread)
3. The courier delivers a **response** — your callback fires
4. You **wake up** (the coroutine resumes) with the response in hand

`suspendCancellableCoroutine` gives you a `continuation` — an object that represents the "wake up" action.

### The Actual Code, Annotated Line by Line

```kotlin
suspend fun login(mail: String, password: String): Result<Account> {
    if (!ensureConnected()) {
        return Result.failure(...)  // couldn't connect — give up immediately
    }

    return withContext(Dispatchers.IO) {      // switch to background thread
        withTimeoutOrNull(CALLBACK_TIMEOUT_MS) {  // if nothing happens in 30s → return null
            suspendCancellableCoroutine { continuation ->
                //                        ^^^^^^^^^^^^
                //  'continuation' is the "wake up" handle for this coroutine

                val callback = createCallback(
                    onSuccess = { account ->
                        // This runs when the service calls callback.onAccountReceived()
                        if (continuation.isActive) {
                            continuation.resume(Result.success(account))
                            //  ^^^^^^ WAKE UP the coroutine with a success result
                        }
                    },
                    onFailure = { message ->
                        // This runs when the service calls callback.onResult() with fail=true
                        if (continuation.isActive) {
                            continuation.resume(Result.failure(Exception(message)))
                            //  ^^^^^^ WAKE UP the coroutine with a failure result
                        }
                    }
                )

                try {
                    service.login(mail, password, callback)
                    // ^^^^^ fire-and-forget: sends the call across IPC
                    // The coroutine SUSPENDS here — doesn't block the thread
                    // It will wake up when onSuccess or onFailure is called
                } catch (e: RemoteException) {
                    // AIDL call itself failed (e.g., service died)
                    continuation.resume(Result.failure(e))
                }
            }
        } ?: Result.failure(Exception("Login timeout"))
        //  ^ if withTimeoutOrNull returns null (timeout), report failure
    }
}
```

### Lifecycle of a Suspended Coroutine

```
Thread: IO
   │
   │  service.login(mail, password, callback)  ← fires IPC call
   │  [COROUTINE SUSPENDS]
   │  Thread is now FREE to do other work
   │
   │  ... time passes (100ms, 1s, 5s — whatever the service needs) ...
   │
Thread: Binder (incoming callback from service)
   │
   │  callback.onResult(AuthResult(success=true))  ← service responds
   │  callback.onAccountReceived(account)
   │  mainHandler.post { continuation.resume(Result.success(account)) }
   │
Thread: IO (or Main — wherever continuation.resume is called from)
   │
   │  [COROUTINE RESUMES]
   │  Result<Account> is returned
```

### Why `continuation.isActive`?

The user might navigate away, the ViewModel might be destroyed, or a timeout might trigger. In these cases, the coroutine is **cancelled**. The `isActive` check prevents you from resuming a dead coroutine, which would throw an exception:

```kotlin
if (continuation.isActive) {
    continuation.resume(...)  // safe — coroutine is still alive
}
```

### Why `invokeOnCancellation`?

```kotlin
continuation.invokeOnCancellation {
    pendingConnection = null  // cleanup if the coroutine is cancelled
}
```

If the coroutine is cancelled (e.g., user leaves the screen), we clean up any pending state. Otherwise, if the service connects later, `pendingConnection` would try to resume a cancelled coroutine.

---

## 9. The Complete Login Flow

Let's trace a single login from button tap to screen navigation, touching every file:

```
USER TAPS "SIGN IN"
├── LoginScreen.kt
│   Button(onClick = { viewModel.login(email, password) })
│
│   ↓
├── AccountViewModel.kt
│   fun login(mail: String, password: String) {
│       _loginState.value = LoginState.Loading  ← spinner shows NOW
│       viewModelScope.launch {
│           val result = ssoApiClient.login(mail, password)
│           ← coroutine suspends here
│       }
│   }
│
│   ↓ (Dispatchers.Main)
├── SsoApiClient.kt — ensureConnected()
│   Already connected? → skip
│   Not connected? →
│       create Intent pointing to "com.example.service" / "SsoService"
│       context.bindService(intent, serviceConnection, BIND_AUTO_CREATE)
│       suspendCancellableCoroutine { continuation → ... }
│       Wait up to 5 seconds for onServiceConnected()
│
│   ↓ (onServiceConnected fires on Main thread)
│   ├── serviceConnection.onServiceConnected()
│   │   ssoService = sso.Stub.asInterface(service)
│   │   ← converts raw IBinder to the sso proxy ← KEY LINE
│   │   pendingConnection?.invoke(true)
│   │   ← resumes the ensureConnected() coroutine with true
│
│   ↓ (Dispatchers.IO)
├── SsoApiClient.kt — login() continues
│   suspendCancellableCoroutine { continuation →
│       val callback = createCallback(...)
│       service.login(mail, password, callback)
│       ← AIDL call goes across process boundary to com.example.service
│       ← coroutine suspends
│   }
│
│   ↓ (Inside com.example.service — Binder thread)
├── SsoService.kt (in the service app)
│   override fun login(mail, password, callback) {
│       // POST /get-token with mail + password → get guid + sessionToken
│       // POST /account-info with sessionToken → get full account details
│       // Save to AccountManager + database
│       // Mark as active account
│       callback.onResult(AuthResult(success = true))
│       callback.onAccountReceived(account)
│   }
│
│   ↓ (Binder thread in account app)
├── SsoApiClient.kt — createCallback() methods fire
│   override fun onResult(result: AuthResult):
│       wasSuccess = result.success
│       (if fail: mainHandler.post { onFailure(...) } → resumes coroutine with failure)
│
│   override fun onAccountReceived(account: Account):
│       if (wasSuccess):
│           mainHandler.post {
│               onSuccess(account)
│               ← this calls continuation.resume(Result.success(account))
│               ← login() coroutine WAKES UP
│           }
│
│   ↓ (IO thread → back on Main thread via mainHandler.post)
├── AccountViewModel.kt — launch block resumes
│   result.onSuccess { account →
│       _activeAccount.value = account.copy(isActive = true)
│       _accounts.value = listOf(account) + otherAccounts
│       _loginState.value = LoginState.Success
│       ← StateFlow emits new value
│   }
│
│   ↓ (Main thread — Compose recomposes)
└── LoginScreen.kt
    LaunchedEffect(loginState) {
        if (loginState is LoginState.Success) {
            onLoginSuccess()  ← navigate to AccountScreen
        }
    }
```

---

## 10. Timeouts

Two timeouts exist in [SsoApiClient](file:///c:/Users/krpra/AndroidStudioProjects/sso-api-lib/ssoapi/src/main/java/com/example/ssoapi/SsoApiClient.kt#35-492):

```kotlin
private const val CONNECTION_TIMEOUT_MS = 5000L   // 5 seconds
private const val CALLBACK_TIMEOUT_MS = 30000L    // 30 seconds
```

### CONNECTION_TIMEOUT_MS (5 seconds)

This is for [ensureConnected()](file:///c:/Users/krpra/AndroidStudioProjects/sso-api-lib/ssoapi/src/main/java/com/example/ssoapi/SsoApiClient.kt#67-127) — waiting for `bindService()` to call back via [onServiceConnected()](file:///c:/Users/krpra/AndroidStudioProjects/sso-api-lib/ssoapi/src/main/java/com/example/ssoapi/SsoApiClient.kt#52-59).

```kotlin
withTimeoutOrNull(CONNECTION_TIMEOUT_MS) {
    suspendCancellableCoroutine { continuation ->
        pendingConnection = { success -> continuation.resume(success) }
        context.bindService(...)
        // If nothing happens in 5s → withTimeoutOrNull returns null
    }
}
```

If this is `null` → service isn't running/installed → return `false` → caller gets `Result.failure("Could not connect")`.

### CALLBACK_TIMEOUT_MS (30 seconds)

This is for the actual AIDL operation — how long we wait for the service to send back [onResult()](file:///c:/Users/krpra/AndroidStudioProjects/sso-api-lib/ssoapi/src/main/java/com/example/ssoapi/SsoApiClient.kt#159-171) + [onAccountReceived()](file:///c:/Users/krpra/AndroidStudioProjects/sso-api-lib/ssoapi/src/main/java/com/example/ssoapi/SsoApiClient.kt#172-180).

```kotlin
withTimeoutOrNull(CALLBACK_TIMEOUT_MS) {
    suspendCancellableCoroutine { continuation ->
        service.login(mail, password, callback)
        // If callback never fires in 30s → withTimeoutOrNull returns null
    }
} ?: Result.failure(Exception("Login timeout"))
//  ^ if null: 30 seconds passed, nothing came back → report timeout failure
```

The `?: Result.failure(...)` is the **Elvis operator** — it means "if the left side is null, use the right side instead."

### Why Different Values?

- **Connection** (5s): `bindService()` is a local OS operation. If it hasn't connected in 5 seconds, the service definitely isn't running. 5 seconds is already generous.
- **Callback** (30s): The service needs to make real HTTP calls to your backend. Slow networks could take 10-20 seconds. 30 seconds gives a wide safety net.

---

## 11. serviceConnection

```kotlin
private val serviceConnection = object : ServiceConnection {

    // Called when the service successfully connects
    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
        Log.d(TAG, "Service connected: $name")
        ssoService = sso.Stub.asInterface(service)  // store the proxy
        isBound = true
        pendingConnection?.invoke(true)  // wake up ensureConnected() coroutine
        pendingConnection = null
    }

    // Called when the service crashes or is killed
    override fun onServiceDisconnected(name: ComponentName?) {
        Log.d(TAG, "Service disconnected: $name")
        ssoService = null
        isBound = false
        // NOTE: we do NOT call pendingConnection here.
        // If disconnect happens mid-coroutine, the withTimeoutOrNull will eventually expire.
    }
}
```

`ServiceConnection` is a listener interface. You give it to `bindService()` and Android calls its methods for you:
- [onServiceConnected](file:///c:/Users/krpra/AndroidStudioProjects/sso-api-lib/ssoapi/src/main/java/com/example/ssoapi/SsoApiClient.kt#52-59) — connection established
- [onServiceDisconnected](file:///c:/Users/krpra/AndroidStudioProjects/sso-api-lib/ssoapi/src/main/java/com/example/ssoapi/SsoApiClient.kt#60-65) — service unexpectedly died (note: this is NOT called when you call `unbindService()` yourself)

### What is `pendingConnection`?

```kotlin
private var pendingConnection: ((Boolean) -> Unit)? = null
```

This is a **lambda variable** — a variable that holds a function. It's the bridge between the callback world ([onServiceConnected](file:///c:/Users/krpra/AndroidStudioProjects/sso-api-lib/ssoapi/src/main/java/com/example/ssoapi/SsoApiClient.kt#52-59) fires at some future time) and the coroutine world ([ensureConnected](file:///c:/Users/krpra/AndroidStudioProjects/sso-api-lib/ssoapi/src/main/java/com/example/ssoapi/SsoApiClient.kt#67-127) is suspended, waiting).

Flow:
1. [ensureConnected()](file:///c:/Users/krpra/AndroidStudioProjects/sso-api-lib/ssoapi/src/main/java/com/example/ssoapi/SsoApiClient.kt#67-127) runs: `pendingConnection = { success -> continuation.resume(success) }`
2. OS calls [onServiceConnected()](file:///c:/Users/krpra/AndroidStudioProjects/sso-api-lib/ssoapi/src/main/java/com/example/ssoapi/SsoApiClient.kt#52-59): `pendingConnection?.invoke(true)` → resumes the coroutine with `true`

---

## 12. The Callback Pattern

### Why Two Methods? (onResult + onAccountReceived)

Look at [IAuthCallback.aidl](file:///c:/Users/krpra/AndroidStudioProjects/sso-api-lib/ssoapi/src/main/aidl/com/example/ssoapi/IAuthCallback.aidl):

```aidl
interface IAuthCallback {
    void onResult(in AuthResult result);
    void onAccountReceived(in Account account);
}
```

And the handling in [createCallback()](file:///c:/Users/krpra/AndroidStudioProjects/sso-api-lib/ssoapi/src/main/java/com/example/ssoapi/SsoApiClient.kt#148-182):

```kotlin
private var resultReceived = false
private var wasSuccess = false

override fun onResult(result: AuthResult) {
    resultReceived = true
    wasSuccess = result.success

    if (result.fail) {
        mainHandler.post { onFailure(result.message ?: "Unknown error") }
        // ← resume coroutine with failure IMMEDIATELY if it fails
    }
    // If success → DON'T resume yet. Wait for onAccountReceived.
}

override fun onAccountReceived(account: Account) {
    if (wasSuccess) {
        mainHandler.post { onSuccess(account) }
        // ← resume coroutine with success + the account object
    }
}
```

**Why this two-step approach?**

The service separates concerns:
1. [onResult](file:///c:/Users/krpra/AndroidStudioProjects/sso-api-lib/ssoapi/src/main/java/com/example/ssoapi/SsoApiClient.kt#159-171) tells you **whether** the operation succeeded or failed (boolean outcome + error message)
2. [onAccountReceived](file:///c:/Users/krpra/AndroidStudioProjects/sso-api-lib/ssoapi/src/main/java/com/example/ssoapi/SsoApiClient.kt#172-180) gives you the **actual data** (the account object) — but only on success

The service always calls [onResult](file:///c:/Users/krpra/AndroidStudioProjects/sso-api-lib/ssoapi/src/main/java/com/example/ssoapi/SsoApiClient.kt#159-171) first. Then, if success, it calls [onAccountReceived](file:///c:/Users/krpra/AndroidStudioProjects/sso-api-lib/ssoapi/src/main/java/com/example/ssoapi/SsoApiClient.kt#172-180). In the client:
- On failure: [onResult](file:///c:/Users/krpra/AndroidStudioProjects/sso-api-lib/ssoapi/src/main/java/com/example/ssoapi/SsoApiClient.kt#159-171) fires with `fail=true` → we immediately wake up the coroutine with an error
- On success: [onResult](file:///c:/Users/krpra/AndroidStudioProjects/sso-api-lib/ssoapi/src/main/java/com/example/ssoapi/SsoApiClient.kt#159-171) fires with `success=true` → we wait; [onAccountReceived](file:///c:/Users/krpra/AndroidStudioProjects/sso-api-lib/ssoapi/src/main/java/com/example/ssoapi/SsoApiClient.kt#172-180) fires → now we wake up with the account

This is a safeguard. Even if [onAccountReceived](file:///c:/Users/krpra/AndroidStudioProjects/sso-api-lib/ssoapi/src/main/java/com/example/ssoapi/SsoApiClient.kt#172-180) is never called (a bug in the service), the coroutine will still unblock after 30 seconds via `CALLBACK_TIMEOUT_MS`.

### `wasSuccess` — Why a Member Variable?

`wasSuccess` is stored as a variable inside the callback object (not a local variable) because [onResult](file:///c:/Users/krpra/AndroidStudioProjects/sso-api-lib/ssoapi/src/main/java/com/example/ssoapi/SsoApiClient.kt#159-171) and [onAccountReceived](file:///c:/Users/krpra/AndroidStudioProjects/sso-api-lib/ssoapi/src/main/java/com/example/ssoapi/SsoApiClient.kt#172-180) are two separate method calls. There's no way to pass data from one to the other except through shared state. The callback object is created fresh for each operation, so there's no risk of cross-operation contamination.

---

## 13. Sealed Classes

```kotlin
sealed class AuthCallbackResult {
    data class Success(val account: Account) : AuthCallbackResult()
    data class Failure(val message: String) : AuthCallbackResult()
}
```

A **sealed class** is like an enum, but each variant can carry different data.

When you have a sealed class, the Kotlin compiler forces you to handle **every** possible case in a `when` expression:

```kotlin
when (result) {
    is AuthCallbackResult.Success -> handleSuccess(result.account)
    is AuthCallbackResult.Failure -> handleFailure(result.message)
    // No 'else' needed — compiler knows these are ALL the cases
}
```

Contrast with a regular class or interface where you'd need `else` because there could be unknown subclasses.

In this project, `Result<T>` (Kotlin's built-in sealed class) is used for the same purpose:
- `Result.success(value)` — operation worked, here's the data
- `Result.failure(exception)` — operation failed, here's what went wrong

Usage:
```kotlin
val result = ssoApiClient.login(email, password)
result.onSuccess { account -> /* use account */ }
result.onFailure { error -> /* show error.message */ }
```

---

## 14. Quick Reference Cheat Sheet

### Key Classes

| Class | Where | Purpose |
|-------|-------|---------|
| [SsoApiClient](file:///c:/Users/krpra/AndroidStudioProjects/sso-api-lib/ssoapi/src/main/java/com/example/ssoapi/SsoApiClient.kt#35-492) | ssoapi library | Main API — call this from your app |
| `sso` (AIDL) | ssoapi library | Interface definition for the service |
| `IAuthCallback` (AIDL) | ssoapi library | Callback interface from service → client |
| [Account](file:///c:/Users/krpra/AndroidStudioProjects/sso-api-lib/ssoapi/src/main/java/com/example/ssoapi/Account.kt#17-53) | ssoapi library | User account data (Parcelable) |
| [AuthResult](file:///c:/Users/krpra/AndroidStudioProjects/sso-api-lib/ssoapi/src/main/java/com/example/ssoapi/AuthResult.kt#15-45) | ssoapi library | Success/failure outcome (Parcelable) |
| `sso.Stub` | auto-generated | Server-side implementation base class |
| `sso.Stub.asInterface()` | auto-generated | Converts IBinder → sso proxy on client side |

### Key Concepts

| Concept | One-liner |
|---------|-----------|
| Coroutine | Code that can pause without blocking the thread |
| `suspend` | Marks a function that can pause |
| `withContext(Dispatchers.IO)` | Run this block on a background thread |
| `suspendCancellableCoroutine` | Convert callbacks into suspend functions |
| `continuation.resume(value)` | Wake up the suspended coroutine with a value |
| AIDL | Contract defining methods one app can call on another |
| `Stub` | Server-side base class for AIDL implementation |
| `Stub.asInterface()` | Get a client-side proxy to call the server |
| `Parcelable` | Serialization mechanism for IPC — pack/unpack objects to bytes |
| Binder thread | Thread Android uses for incoming AIDL calls (NOT Main thread) |
| `mainHandler.post {}` | Switch from Binder thread to Main thread |
| `<queries>` in manifest | Android 11+: declare which apps you want to see/connect to |
| `withTimeoutOrNull(ms)` | Returns null if the block doesn't finish in time |

### Common Pitfalls

| Problem | Cause | Fix |
|---------|-------|-----|
| `bindService()` returns `false` | Missing `<queries>` in manifest | Add `<queries>` block |
| "Connection timeout" | Service app not installed | Install the service APK first |
| Data corrupted across IPC | Parcel read/write order mismatch | Ensure [writeToParcel](file:///c:/Users/krpra/AndroidStudioProjects/sso-api-lib/ssoapi/src/main/java/com/example/ssoapi/Account.kt#33-40) and the Parcel constructor read in the same order |
| Crash: "Can't call resume on cancelled coroutine" | Missing `isActive` check | Wrap resume calls in `if (continuation.isActive)` |
| UI not updating from callback | Updating StateFlow from Binder thread | Use `mainHandler.post {}` or `withContext(Dispatchers.Main)` |
| `ViewModel` keeps working after screen closes | Launched coroutine without `viewModelScope` | Always use `viewModelScope.launch {}` |
