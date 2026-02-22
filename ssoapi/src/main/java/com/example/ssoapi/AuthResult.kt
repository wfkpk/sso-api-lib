package com.example.ssoapi

import android.os.Parcel
import android.os.Parcelable

/**
 * AuthResult data class that represents the result of an authentication operation.
 * This class is Parcelable so it can be passed via AIDL callback.
 *
 * Fields:
 * - success: Whether the operation succeeded
 * - fail: Whether the operation failed
 * - message: Optional message (usually error details)
 */
data class AuthResult(
    val success: Boolean = false,
    val fail: Boolean = false,
    val message: String? = null
) : Parcelable {

    constructor(parcel: Parcel) : this(
        parcel.readByte() != 0.toByte(),
        parcel.readByte() != 0.toByte(),
        parcel.readString()
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeByte(if (success) 1 else 0)
        parcel.writeByte(if (fail) 1 else 0)
        parcel.writeString(message)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<AuthResult> {
        override fun createFromParcel(parcel: Parcel): AuthResult {
            return AuthResult(parcel)
        }

        override fun newArray(size: Int): Array<AuthResult?> {
            return arrayOfNulls(size)
        }
    }
}
